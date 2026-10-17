// Head tracking from the Google Cardboard SDK (native/cardboard, Apache 2.0): its EKF sensor
// fusion with gyroscope bias estimation (uncalibrated gyro when available) and pose
// prediction. Exposed to cardboard_swap.c as plain C.

#include <array>
#include <memory>
#include <mutex>

#include "head_tracker.h"

namespace {
std::mutex g_lock;
std::unique_ptr<cardboard::HeadTracker> g_tracker;
bool g_running = false;
}  // namespace

extern "C" {

// Starts (or resumes) the Cardboard head tracker. Returns 1 on success.
int pxr_cardboard_start(void) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (!g_tracker) g_tracker.reset(new cardboard::HeadTracker());
  if (!g_running) g_tracker->Resume();
  g_running = true;
  return 1;
}

void pxr_cardboard_pause(void) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (g_tracker && g_running) g_tracker->Pause();
  g_running = false;
}

void pxr_cardboard_recenter(void) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (g_tracker) g_tracker->Recenter();
}

// Predicted head orientation for timestamp_ns (CLOCK_MONOTONIC) as the Cardboard SDK returns
// it: a view rotation quaternion (x, y, z, w) in display space. Returns 0 while not running.
int pxr_cardboard_pose(long long timestamp_ns, int viewport_orientation, float out_quat[4]) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (!g_tracker || !g_running) return 0;
  std::array<float, 3> position;
  std::array<float, 4> orientation;
  g_tracker->GetPose(timestamp_ns, static_cast<CardboardViewportOrientation>(viewport_orientation),
                     position, orientation);
  for (int i = 0; i < 4; i++) out_quat[i] = orientation[i];
  return 1;
}

}  // extern "C"
