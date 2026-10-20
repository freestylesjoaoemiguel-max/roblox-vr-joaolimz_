#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <pthread.h>

#define TAG "PhoneXR-Cardboard"

typedef EGLBoolean (*SwapFn)(EGLDisplay, EGLSurface);

static SwapFn real_swap;
static GLuint program_id;
static GLuint texture_id;
static GLuint camera_texture_id;
static GLuint ui_texture_id;
static GLuint vertex_buffer;
static GLuint capture_fbo;

static GLint attr_position;
static GLint attr_uv;
static GLint uni_texture;
static GLint uni_camera_tex;
static GLint uni_ui_tex;
static GLint uni_eye;
static GLint uni_menu;
static GLint uni_has_camera;
static GLint uni_ui_visible;
static GLint uni_head;
static GLint uni_lens;
/* Headset lens profile: barrel k1, k2 and per-eye image shift (VR settings). */
static volatile float lens_k1 = 0.18f, lens_k2 = 0.04f, lens_shift = 0.012f;
static GLint uni_ui_zoom;
/* Hand skeleton overlay: its own tiny line/point program. */
static GLuint skel_program, skel_vbo;
static GLint skel_attr_pos, skel_uni_color, skel_uni_point;
static pthread_mutex_t skel_lock = PTHREAD_MUTEX_INITIALIZER;
static float skel_points[2][42];
static volatile bool skel_active[2];
static volatile bool skel_enabled;

/* 0 = loading (before the Roblox home), 1 = Roblox home, 2 = in a game. */
static volatile int scene_mode;
static volatile float head_yaw;
static volatile float head_pitch;
static volatile bool has_camera;
static volatile bool ui_visible;
static volatile bool hands_visible = true;
static volatile float ray_left_x = 0.5f;
static volatile float ray_left_y = 0.5f;
static volatile bool ray_left_active = false;
static volatile float ray_right_x = 0.5f;
static volatile float ray_right_y = 0.5f;
static volatile bool ray_right_active = false;
static volatile float hand_left_x = 0.35f, hand_left_y = 0.65f;
static volatile float hand_right_x = 0.65f, hand_right_y = 0.65f;
static volatile float hand_left_angle, hand_right_angle;
static volatile float hand_left_scale = 1.f, hand_right_scale = 1.f;
static volatile bool hand_left_active, hand_right_active;

static void *camera_pixels = NULL;
static int camera_w = 0, camera_h = 0;
static volatile bool camera_pending = false;

static void *ui_pixels = NULL;
static int ui_w = 0, ui_h = 0;
static volatile bool ui_pending = false;
static int ui_tex_w = 0, ui_tex_h = 0, camera_tex_w = 0, camera_tex_h = 0;
/* World-locked panel (VR keyboard): head angles when it opened. */
static volatile float ui_anchor_yaw, ui_anchor_pitch;
static volatile bool ui_world_locked;
static volatile float ui_zoom = 1.6f;
static GLint uni_cursor, uni_cursor_on, uni_aspect, uni_rbx_scale;
/* "Ultrawide screen" setting: show the Roblox frame without horizontal squeeze. */
static volatile bool ultrawide;

static int texture_width;
static int texture_height;
static bool failed;
static bool logged_state;
static EGLContext renderer_context = EGL_NO_CONTEXT;
static bool head_pose_logged;
static volatile bool roblox_pose_enabled;
/* Head rotation in Roblox space, row-major, columns Right/Up/Back. */
static float head_rot[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
/* 6DoF head position in UserCFrame units (ARCore), zero in 3DoF. */
static volatile float head_pos_x, head_pos_y, head_pos_z;
static void push_roblox_pose(void);

/* ---- Head tracking from the Google Cardboard SDK (cardboard_head.cc) ---- */
int pxr_cardboard_start(void);
void pxr_cardboard_pause(void);
void pxr_cardboard_recenter(void);
int pxr_cardboard_pose(long long timestamp_ns, int viewport_orientation, float out_quat[4]);
static volatile bool cb_active;
static volatile int cb_viewport; /* 0 landscape left (ROTATION_90), 1 landscape right (ROTATION_270) */
static volatile float cb_turn_yaw; /* snap/smooth turns, positive = view turned left */
static pthread_mutex_t head_lock = PTHREAD_MUTEX_INITIALIZER;

/* Predicted Cardboard pose -> head_rot (Roblox space, columns Right/Up/Back) and yaw/pitch. */
static void cardboard_update(void) {
    if (!cb_active) return;
    struct timespec now;
    /* Android sensor events (and thus the EKF state) are stamped on CLOCK_BOOTTIME. The
       prediction timestamp MUST use the same clock, or the huge monotonic-vs-boottime gap
       makes PredictRotation extrapolate wildly ("бешеный гироскоп"). Predict 30 ms ahead. */
    clock_gettime(CLOCK_BOOTTIME, &now);
    long long ts = (long long)now.tv_sec * 1000000000LL + now.tv_nsec + 30000000LL;
    float q[4];
    if (!pxr_cardboard_pose(ts, cb_viewport, q)) return;
    float x = q[0], y = q[1], z = q[2], w = q[3];
    /* View rotation (world -> head); the head rotation is its transpose. */
    float v[3][3] = {
        {1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)},
        {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)},
        {2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)}};
    float c = cosf(cb_turn_yaw), s = sinf(cb_turn_yaw);
    float m[9];
    for (int col = 0; col < 3; col++) {
        float hx = v[col][0], hy = v[col][1], hz = v[col][2]; /* head axis col in world */
        m[col] = c * hx + s * hz;       /* rotate about +Y by the turn */
        m[3 + col] = hy;
        m[6 + col] = -s * hx + c * hz;
    }
    float fx = -m[2], fy = -m[5], fz = -m[8];
    pthread_mutex_lock(&head_lock);
    memcpy(head_rot, m, sizeof(m));
    head_yaw = atan2f(fx, -fz);
    head_pitch = asinf(fy < -1.f ? -1.f : (fy > 1.f ? 1.f : fy));
    pthread_mutex_unlock(&head_lock);
}

static const char *vertex_source =
    "attribute vec2 aPosition;\n"
    "attribute vec2 aUv;\n"
    "varying vec2 vUv;\n"
    "void main(){ vUv=aUv; gl_Position=vec4(aPosition,0.0,1.0); }\n";

static const char *fragment_source =
    "precision mediump float;\n"
    "uniform sampler2D uTexture;\n"
    "uniform sampler2D uCameraTex;\n"
    "uniform sampler2D uUiTex;\n"
    "uniform float uEye;\n"
    "uniform vec3 uLens;\n"
    "uniform float uMenu;\n"
    "uniform float uHasCamera;\n"
    "uniform float uUiVisible;\n"
    "uniform vec2 uHead;\n"
    "uniform float uUiZoom;\n"

    "uniform vec4 uCursor;\n"
    "uniform vec2 uCursorOn;\n"
    "uniform float uAspect;\n"
    "uniform float uRbxScaleX;\n"
    "varying vec2 vUv;\n"
    "void main(){\n"
    " vec2 p=vUv*2.0-1.0;\n"
    " float r2=dot(p,p);\n"
    " vec2 q=p*(1.0+uLens.x*r2+uLens.y*r2*r2);\n"
    " vec2 uv=q*0.5+0.5;\n"
    " uv.x += uEye*uLens.z;\n"
    " if(any(lessThan(uv,vec2(0.0)))||any(greaterThan(uv,vec2(1.0)))){\n"
    "  gl_FragColor=vec4(0.0,0.0,0.0,1.0);\n"
    "  return;\n"
    " }\n"
    " vec4 c=vec4(0.0,0.0,0.0,1.0);\n"
    /* uUiZoom > 1 shows the panel smaller than the full view; the UI bitmap is stored
       top row first, so v is flipped (the keyboard used to appear upside down). */
    " vec2 uiUv=(uv-0.5+uHead*0.5-vec2(uEye*0.018,0.0))*uUiZoom+0.5;\n"
    " uiUv.y=1.0-uiUv.y;\n"
    " if(uMenu<0.5){\n"
    /* Loading: black with the stereo Loading text only; the Roblox logo never shows. */
    " }else if(uMenu<1.5){\n"
    /* Roblox home: a plain dark background replaces the (dark) lobby scene; menu panels stay. */
    "  c=vec4(0.035,0.04,0.055,1.0);\n"
    "  vec4 rbx=texture2D(uTexture,vec2((uv.x-0.5)*uRbxScaleX+0.5,uv.y));\n"
    "  float k=smoothstep(0.22,0.36,max(rbx.r,max(rbx.g,rbx.b)));\n"
    "  c=mix(c,rbx,k);\n"
    " }else{\n"
    /* In a game: Roblox only, no camera. */
    /* Ultrawide option: the middle of the wide frame at its true proportions, not the
       whole frame squeezed into one eye. */
    "  c=texture2D(uTexture,vec2((uv.x-0.5)*uRbxScaleX+0.5,uv.y));\n"
    " }\n"
    " if(uUiVisible>0.5 && uiUv.x>=0.0 && uiUv.x<=1.0 && uiUv.y>=0.0 && uiUv.y<=1.0){\n"
    "  vec4 ui=texture2D(uUiTex,uiUv);\n"
    "  c=mix(c,ui,ui.a);\n"
    " }\n"
    /* Pointer dot at the end of each active ray (on our panels). */
    " vec2 sp2=vec2(uv.x-uEye*0.018,uv.y);\n"
    " for(int i=0;i<2;i++){\n"
    "  float on=i==0?uCursorOn.x:uCursorOn.y;\n"
    "  vec2 cp2=i==0?uCursor.xy:uCursor.zw;\n"
    "  if(on>0.5){\n"
    "   float dd=length((sp2-vec2(cp2.x,1.0-cp2.y))*vec2(uAspect,1.0));\n"
    "   c=mix(c,vec4(0.05,0.06,0.1,1.0),smoothstep(0.013,0.010,dd));\n"
    "   c=mix(c,vec4(1.0),smoothstep(0.009,0.006,dd));\n"
    "  }\n"
    " }\n"
    " gl_FragColor=c;\n"
    "}\n";

static GLuint compile_shader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, 0);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), 0, log);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "shader: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static void setup_texture_params(GLuint tex) {
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

static bool init_renderer(void) {
    GLuint vs = compile_shader(GL_VERTEX_SHADER, vertex_source);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fragment_source);
    if (!vs || !fs) return false;
    program_id = glCreateProgram();
    glAttachShader(program_id, vs);
    glAttachShader(program_id, fs);
    glLinkProgram(program_id);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program_id, GL_LINK_STATUS, &ok);
    if (!ok) return false;

    attr_position = glGetAttribLocation(program_id, "aPosition");
    attr_uv = glGetAttribLocation(program_id, "aUv");
    uni_texture = glGetUniformLocation(program_id, "uTexture");
    uni_camera_tex = glGetUniformLocation(program_id, "uCameraTex");
    uni_ui_tex = glGetUniformLocation(program_id, "uUiTex");
    uni_eye = glGetUniformLocation(program_id, "uEye");
    uni_lens = glGetUniformLocation(program_id, "uLens");
    uni_menu = glGetUniformLocation(program_id, "uMenu");
    uni_has_camera = glGetUniformLocation(program_id, "uHasCamera");
    uni_ui_visible = glGetUniformLocation(program_id, "uUiVisible");
    uni_head = glGetUniformLocation(program_id, "uHead");
    uni_ui_zoom = glGetUniformLocation(program_id, "uUiZoom");
    uni_cursor = glGetUniformLocation(program_id, "uCursor");
    uni_cursor_on = glGetUniformLocation(program_id, "uCursorOn");
    uni_aspect = glGetUniformLocation(program_id, "uAspect");
    uni_rbx_scale = glGetUniformLocation(program_id, "uRbxScaleX");
    {
        static const char *skel_vs =
            "attribute vec2 aPos;\n"
            "uniform float uPoint;\n"
            "void main(){ gl_Position=vec4(aPos,0.0,1.0); gl_PointSize=uPoint; }\n";
        static const char *skel_fs =
            "precision mediump float;\n"
            "uniform vec4 uColor;\n"
            "void main(){ gl_FragColor=uColor; }\n";
        GLuint a = compile_shader(GL_VERTEX_SHADER, skel_vs);
        GLuint b = compile_shader(GL_FRAGMENT_SHADER, skel_fs);
        if (a && b) {
            skel_program = glCreateProgram();
            glAttachShader(skel_program, a);
            glAttachShader(skel_program, b);
            glLinkProgram(skel_program);
            GLint linked = 0;
            glGetProgramiv(skel_program, GL_LINK_STATUS, &linked);
            if (!linked) { glDeleteProgram(skel_program); skel_program = 0; }
            skel_attr_pos = glGetAttribLocation(skel_program, "aPos");
            skel_uni_color = glGetUniformLocation(skel_program, "uColor");
            skel_uni_point = glGetUniformLocation(skel_program, "uPoint");
            glGenBuffers(1, &skel_vbo);
        }
        if (a) glDeleteShader(a);
        if (b) glDeleteShader(b);
    }

    glGenTextures(1, &texture_id);
    setup_texture_params(texture_id);

    glGenTextures(1, &camera_texture_id);
    setup_texture_params(camera_texture_id);

    glGenTextures(1, &ui_texture_id);
    setup_texture_params(ui_texture_id);

    glGenFramebuffers(1, &capture_fbo);

    const GLfloat vertices[] = {
        -1.f,-1.f, 0.f,0.f,  1.f,-1.f, 1.f,0.f,
        -1.f, 1.f, 0.f,1.f,  1.f, 1.f, 1.f,1.f
    };
    glGenBuffers(1, &vertex_buffer);
    glBindBuffer(GL_ARRAY_BUFFER, vertex_buffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Cardboard stereo compositor enabled");
    return true;
}

/* MediaPipe hand connections (21 landmarks). */
static const unsigned char SKEL_BONES[] = {
    0,1, 1,2, 2,3, 3,4,  0,5, 5,6, 6,7, 7,8,  5,9, 9,10, 10,11, 11,12,
    9,13, 13,14, 14,15, 15,16,  13,17, 0,17, 17,18, 18,19, 19,20 };

/* Screen point (0..1, y down) to eye NDC: inverse of the lens distortion in the shader. */
static void skel_to_ndc(float sx, float sy, float eye, float *ox, float *oy) {
    float tx = (sx - eye * lens_shift) * 2.f - 1.f, ty = (1.f - sy) * 2.f - 1.f;
    float px = tx, py = ty;
    for (int i = 0; i < 6; i++) {
        float r2 = px * px + py * py;
        float f = 1.f + lens_k1 * r2 + lens_k2 * r2 * r2;
        px = tx / f; py = ty / f;
    }
    *ox = px; *oy = py;
}

static void draw_skeleton(int left_w, int right_w, int height) {
    float pts[2][42];
    bool active[2];
    pthread_mutex_lock(&skel_lock);
    memcpy(pts, skel_points, sizeof(pts));
    active[0] = skel_active[0]; active[1] = skel_active[1];
    pthread_mutex_unlock(&skel_lock);

    GLfloat lines[2 * sizeof(SKEL_BONES)];
    GLfloat joints[42];
    GLint src_rgb, dst_rgb, src_a, dst_a;
    GLfloat line_width = 1.f;
    glGetIntegerv(GL_BLEND_SRC_RGB, &src_rgb); glGetIntegerv(GL_BLEND_DST_RGB, &dst_rgb);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &src_a); glGetIntegerv(GL_BLEND_DST_ALPHA, &dst_a);
    glGetFloatv(GL_LINE_WIDTH, &line_width);
    /* Vertex buffer, not client arrays: Roblox may leave a VAO bound (GLES3). */
    glBindBuffer(GL_ARRAY_BUFFER, skel_vbo);
    glUseProgram(skel_program);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glLineWidth(4.f);
    glEnableVertexAttribArray((GLuint)skel_attr_pos);
    for (int e = 0; e < 2; e++) {
        float eye = e == 0 ? -1.f : 1.f;
        if (e == 0) glViewport(0, 0, left_w, height);
        else glViewport(left_w, 0, right_w, height);
        for (int h = 0; h < 2; h++) {
            if (!active[h]) continue;
            for (int i = 0; i < 21; i++)
                skel_to_ndc(pts[h][i * 2], pts[h][i * 2 + 1], eye, &joints[i * 2], &joints[i * 2 + 1]);
            for (size_t b = 0; b < sizeof(SKEL_BONES); b++) {
                lines[b * 2] = joints[SKEL_BONES[b] * 2];
                lines[b * 2 + 1] = joints[SKEL_BONES[b] * 2 + 1];
            }
            glUniform4f(skel_uni_color, h == 0 ? 0.35f : 0.30f, h == 0 ? 0.80f : 0.65f, 1.f, 0.85f);
            glBufferData(GL_ARRAY_BUFFER, sizeof(lines), lines, GL_STREAM_DRAW);
            glVertexAttribPointer((GLuint)skel_attr_pos, 2, GL_FLOAT, GL_FALSE, 0, (void *)0);
            glDrawArrays(GL_LINES, 0, (GLsizei)sizeof(SKEL_BONES));
            glUniform4f(skel_uni_color, 1.f, 1.f, 1.f, 0.95f);
            glUniform1f(skel_uni_point, 9.f);
            glBufferData(GL_ARRAY_BUFFER, sizeof(joints), joints, GL_STREAM_DRAW);
            glVertexAttribPointer((GLuint)skel_attr_pos, 2, GL_FLOAT, GL_FALSE, 0, (void *)0);
            glDrawArrays(GL_POINTS, 0, 21);
        }
    }
    glDisableVertexAttribArray((GLuint)skel_attr_pos);
    glDisable(GL_BLEND);
    glBlendFuncSeparate((GLenum)src_rgb, (GLenum)dst_rgb, (GLenum)src_a, (GLenum)dst_a);
    glLineWidth(line_width);
}

static volatile bool stereo_on = true;

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setStereoEnabled(JNIEnv *env, jclass type, jboolean on) {
    (void)env; (void)type;
    stereo_on = on;
}

EGLBoolean pxrSwapBuffers(EGLDisplay display, EGLSurface surface) {
    if (!real_swap) real_swap = (SwapFn)dlsym(RTLD_NEXT, "eglSwapBuffers");
    if (!real_swap) return EGL_FALSE;
    if (!stereo_on) return real_swap(display, surface);
    EGLContext current_context = eglGetCurrentContext();
    if (renderer_context != current_context) {
        renderer_context = current_context;
        program_id = texture_id = camera_texture_id = ui_texture_id = vertex_buffer = capture_fbo = 0;
        skel_program = skel_vbo = 0;
        texture_width = texture_height = 0;
        ui_tex_w = ui_tex_h = camera_tex_w = camera_tex_h = 0;
        failed = logged_state = false;
    }
    if (failed) return real_swap(display, surface);
    cardboard_update();

    EGLint width = 0, height = 0;
    eglQuerySurface(display, surface, EGL_WIDTH, &width);
    eglQuerySurface(display, surface, EGL_HEIGHT, &height);
    if (width < 4 || height < 4) return real_swap(display, surface);


    GLint old_program = 0, old_texture = 0, old_active = 0, old_buffer = 0;
    GLint old_framebuffer = 0;
    GLint old_viewport[4];
    glGetIntegerv(GL_CURRENT_PROGRAM, &old_program);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &old_active);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_texture);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &old_buffer);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &old_framebuffer);
    glGetIntegerv(GL_VIEWPORT, old_viewport);
    GLboolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST);
    GLboolean cull = glIsEnabled(GL_CULL_FACE), scissor = glIsEnabled(GL_SCISSOR_TEST);

    if (!program_id && !init_renderer()) {
        failed = true;
        return real_swap(display, surface);
    }
    int frame_width = old_viewport[2];
    int frame_height = old_viewport[3];
    if (frame_width < 1 || frame_height < 1) return real_swap(display, surface);

    glBindTexture(GL_TEXTURE_2D, texture_id);
    if (texture_width != frame_width || texture_height != frame_height) {
        texture_width = frame_width;
        texture_height = frame_height;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, frame_width, frame_height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, 0);
    }
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, capture_fbo);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, texture_id, 0);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_framebuffer);
    glBlitFramebuffer(old_viewport[0], old_viewport[1],
                      old_viewport[0] + frame_width, old_viewport[1] + frame_height,
                      0, 0, frame_width, frame_height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

    if (camera_pending && camera_pixels && camera_texture_id) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, camera_texture_id);
        /* Reuse the texture storage; only reallocate when the size changes. */
        if (camera_tex_w != camera_w || camera_tex_h != camera_h) {
            camera_tex_w = camera_w; camera_tex_h = camera_h;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, camera_w, camera_h, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, camera_pixels);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, camera_w, camera_h,
                            GL_RGBA, GL_UNSIGNED_BYTE, camera_pixels);
        }
        camera_pending = false;
    }

    if (ui_pending && ui_pixels && ui_texture_id) {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, ui_texture_id);
        if (ui_tex_w != ui_w || ui_tex_h != ui_h) {
            ui_tex_w = ui_w; ui_tex_h = ui_h;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ui_w, ui_h, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, ui_pixels);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, ui_w, ui_h,
                            GL_RGBA, GL_UNSIGNED_BYTE, ui_pixels);
        }
        ui_pending = false;
    }

    glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glUseProgram(program_id);
    glBindBuffer(GL_ARRAY_BUFFER, vertex_buffer);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_id);
    glUniform1i(uni_texture, 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, camera_texture_id);
    glUniform1i(uni_camera_tex, 1);

    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, ui_texture_id);
    glUniform1i(uni_ui_tex, 2);

    glUniform1f(uni_menu, (float)scene_mode);
    glUniform4f(uni_cursor, ray_left_x, ray_left_y, ray_right_x, ray_right_y);
    glUniform2f(uni_cursor_on, ray_left_active ? 1.f : 0.f, ray_right_active ? 1.f : 0.f);
    glUniform1f(uni_aspect, (float)(width / 2) / (float)height);
    /* Eye aspect / frame aspect: 1 = whole frame per eye (squeezed), <1 = true proportions. */
    glUniform1f(uni_rbx_scale, ultrawide
            ? ((float)(width / 2) / (float)height) / ((float)frame_width / (float)frame_height) : 1.f);
    glUniform3f(uni_lens, lens_k1, lens_k2, lens_shift);
    glUniform1f(uni_has_camera, has_camera ? 1.f : 0.f);
    glUniform1f(uni_ui_visible, ui_visible ? 1.f : 0.f);

    if (ui_world_locked) {
        /* Panel stays where it opened: shift it by the head turn since then (tan = flat panel). */
        float dy = head_yaw - ui_anchor_yaw, dp = head_pitch - ui_anchor_pitch;
        if (dy > 3.14159265f) dy -= 6.2831853f; else if (dy < -3.14159265f) dy += 6.2831853f;
        dy = dy < -1.3f ? -1.3f : (dy > 1.3f ? 1.3f : dy);
        dp = dp < -1.3f ? -1.3f : (dp > 1.3f ? 1.3f : dp);
        glUniform2f(uni_head, tanf(dy), tanf(dp));
        glUniform1f(uni_ui_zoom, ui_zoom);
    } else {
        float yaw = head_yaw < -0.8f ? -0.8f : (head_yaw > 0.8f ? 0.8f : head_yaw);
        float pitch = head_pitch < -0.6f ? -0.6f : (head_pitch > 0.6f ? 0.6f : head_pitch);
        glUniform2f(uni_head, yaw, pitch);
        glUniform1f(uni_ui_zoom, 1.f);
    }

    glEnableVertexAttribArray((GLuint)attr_position);
    glEnableVertexAttribArray((GLuint)attr_uv);
    glVertexAttribPointer((GLuint)attr_position, 2, GL_FLOAT, GL_FALSE, 4*sizeof(GLfloat), (void *)0);
    glVertexAttribPointer((GLuint)attr_uv, 2, GL_FLOAT, GL_FALSE, 4*sizeof(GLfloat), (void *)(2*sizeof(GLfloat)));

    int half = width / 2;
    glViewport(0, 0, half, height);
    glUniform1f(uni_eye, -1.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glViewport(half, 0, width-half, height);
    glUniform1f(uni_eye, 1.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray((GLuint)attr_position);
    glDisableVertexAttribArray((GLuint)attr_uv);

    if (skel_enabled && skel_program && (skel_active[0] || skel_active[1])) {
        draw_skeleton(half, width - half, height);
    }
    glUseProgram((GLuint)old_program);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)old_framebuffer);
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)old_buffer);
    glBindTexture(GL_TEXTURE_2D, (GLuint)old_texture);
    glActiveTexture((GLenum)old_active);
    glViewport(old_viewport[0], old_viewport[1], old_viewport[2], old_viewport[3]);
    if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
    if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
    if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    static int frames;
    static struct timespec since;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (since.tv_sec == 0) since = now;
    frames++;
    double elapsed = (now.tv_sec - since.tv_sec) + (now.tv_nsec - since.tv_nsec) * 1e-9;
    if (elapsed >= 5.0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Roblox fps=%.1f mode=%d", frames / elapsed, scene_mode);
        frames = 0;
        since = now;
    }
    return real_swap(display, surface);
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setSceneMode(JNIEnv *env, jclass type, jint mode) {
    (void)env; (void)type;
    scene_mode = mode;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setHeadPose(JNIEnv *env, jclass type, jfloat yaw, jfloat pitch) {
    (void)env; (void)type;
    head_yaw = yaw;
    head_pitch = pitch;
    push_roblox_pose();
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setRays(JNIEnv *env, jclass type,
        jfloat lx, jfloat ly, jboolean lActive,
        jfloat rx, jfloat ry, jboolean rActive,
        jboolean hands) {
    (void)env; (void)type;
    ray_left_x = lx;
    ray_left_y = ly;
    ray_left_active = lActive == JNI_TRUE;
    ray_right_x = rx;
    ray_right_y = ry;
    ray_right_active = rActive == JNI_TRUE;
    hands_visible = hands == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setHands(JNIEnv *env, jclass type,
        jfloat lx, jfloat ly, jfloat la, jfloat ls, jboolean lActive,
        jfloat rx, jfloat ry, jfloat ra, jfloat rs, jboolean rActive,
        jboolean visible) {
    (void)env; (void)type;
    hand_left_x = lx; hand_left_y = ly; hand_left_angle = la; hand_left_scale = ls;
    hand_right_x = rx; hand_right_y = ry; hand_right_angle = ra; hand_right_scale = rs;
    hand_left_active = lActive == JNI_TRUE;
    hand_right_active = rActive == JNI_TRUE;
    hands_visible = visible == JNI_TRUE;
    push_roblox_pose();
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setUiBuffer(JNIEnv *env, jclass type,
        jint w, jint h, jobject buffer, jboolean visible) {
    (void)type;
    ui_visible = visible == JNI_TRUE;
    if (buffer != NULL) {
        ui_pixels = (*env)->GetDirectBufferAddress(env, buffer);
        ui_w = w;
        ui_h = h;
        ui_pending = true;
    }
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setLensProfile(JNIEnv *env, jclass type,
        jfloat k1, jfloat k2, jfloat shift) {
    (void)env; (void)type;
    lens_k1 = k1;
    lens_k2 = k2;
    lens_shift = shift;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setSkeletonEnabled(JNIEnv *env, jclass type, jboolean enabled) {
    (void)env; (void)type;
    skel_enabled = enabled == JNI_TRUE;
}

/* 21 landmarks per hand in screen space (x, y in 0..1, y down); null = hand not seen. */
JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setSkeleton(JNIEnv *env, jclass type,
        jfloatArray left, jfloatArray right) {
    (void)type;
    jfloatArray arrays[2] = {left, right};
    float tmp[2][42];
    bool active[2];
    for (int h = 0; h < 2; h++) {
        active[h] = arrays[h] != NULL && (*env)->GetArrayLength(env, arrays[h]) >= 42;
        if (active[h]) (*env)->GetFloatArrayRegion(env, arrays[h], 0, 42, tmp[h]);
    }
    pthread_mutex_lock(&skel_lock);
    for (int h = 0; h < 2; h++) {
        if (active[h]) memcpy(skel_points[h], tmp[h], sizeof(tmp[h]));
        skel_active[h] = active[h];
    }
    pthread_mutex_unlock(&skel_lock);
}

JNIEXPORT jboolean JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setCardboardTracking(JNIEnv *env, jclass type,
        jboolean enabled, jint viewport) {
    (void)env; (void)type;
    cb_viewport = viewport;
    if (enabled == JNI_TRUE) {
        cb_active = pxr_cardboard_start() != 0;
    } else {
        cb_active = false;
        pxr_cardboard_pause();
    }
    return cb_active ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_cardboardRecenter(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    cb_turn_yaw = 0.f;
    pxr_cardboard_recenter();
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_cardboardTurn(JNIEnv *env, jclass type, jfloat radians) {
    (void)env; (void)type;
    cb_turn_yaw += radians;
}

/* Latest Cardboard head for Java (UI anchoring, fallback camera): 9 matrix + yaw + pitch. */
JNIEXPORT jboolean JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_getCardboardHead(JNIEnv *env, jclass type, jfloatArray out) {
    (void)type;
    if (!cb_active || (*env)->GetArrayLength(env, out) < 11) return JNI_FALSE;
    cardboard_update();
    float values[11];
    pthread_mutex_lock(&head_lock);
    memcpy(values, head_rot, sizeof(head_rot));
    values[9] = head_yaw;
    values[10] = head_pitch;
    pthread_mutex_unlock(&head_lock);
    (*env)->SetFloatArrayRegion(env, out, 0, 11, values);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setUltrawide(JNIEnv *env, jclass type, jboolean enabled) {
    (void)env; (void)type;
    ultrawide = enabled == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setUiZoom(JNIEnv *env, jclass type, jfloat zoom) {
    (void)env; (void)type;
    ui_zoom = zoom > 0.5f ? zoom : 1.f;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setUiAnchor(JNIEnv *env, jclass type,
        jfloat yaw, jfloat pitch, jboolean locked) {
    (void)env; (void)type;
    ui_anchor_yaw = yaw;
    ui_anchor_pitch = pitch;
    ui_world_locked = locked == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setCameraBuffer(JNIEnv *env, jclass type,
        jint w, jint h, jobject buffer, jboolean active) {
    (void)type;
    has_camera = active == JNI_TRUE;
    if (buffer != NULL) {
        camera_pixels = (*env)->GetDirectBufferAddress(env, buffer);
        camera_w = w;
        camera_h = h;
        camera_pending = true;
    }
}


/*
 * Roblox VR pose. VRService keeps UserCFrame Head/LeftHand/RightHand at
 * +0x174 + index * 0x30 (row-major 3x3 rotation, then position). Its setters
 * fire Lua signals and must run on the DataModel thread, so the phone pose is
 * buffered here and applied by pxr_frame_hook, which the engine's own frame
 * step calls through a patched trampoline (see patch_native.py).
 * Offsets are for the exact ARM64 Roblox 3120 build and verified at runtime.
 */
#include PXR_PROFILE_H

typedef void (*SetUserCFrameFn)(uint8_t *vr, int type, const float *cframe, int virtual_input);
typedef void (*SetUserCFrameEnabledFn)(uint8_t *vr, int type, int enabled, int virtual_input);
static SetUserCFrameFn rbx_set_user_cframe;
static SetUserCFrameEnabledFn rbx_set_user_cframe_enabled;
void pxr_frame_hook(uint8_t *vr);

static int rbx_state; /* 0 unknown, 1 ready, -1 unsupported */
static volatile uint8_t *vr_flag_ptr; /* VR-mode byte inside libroblox (read by the patched getters) */

static bool resolve_roblox_vr(void) {
    if (rbx_state != 0) return rbx_state > 0;
    rbx_state = -1;
    void *lib = dlopen("libroblox.so", RTLD_NOW | RTLD_NOLOAD);
    if (lib == NULL) { rbx_state = 0; return false; } /* not loaded yet: retry later */
    uintptr_t jni = (uintptr_t)dlsym(lib, "Java_com_roblox_engine_jni_NativeGLInterface_setVRCameraCFrame");
    if (jni == 0) { rbx_state = 0; return false; }
    uintptr_t base = jni - RBX_SET_CAMERA_JNI;
    const struct { uintptr_t offset; uint32_t insn; } checks[] = {
        {RBX_SET_CAMERA_JNI, CHK_SET_CAMERA},  /* sub sp, sp, #N */
        {RBX_USERCFRAME_READ, CHK_USERCFRAME_READ},   /* add x21, x0, #USERCFRAMES */
        {RBX_GET_USERCFRAME_ENABLED + 4, CHK_GET_ENABLED}, /* ldrb w0, [x8, #USERCFRAME_ENABLED] */
        {RBX_SET_USERCFRAME, 0xd10543ffu},             /* sub sp, sp, #0x150 */
        {RBX_SET_USERCFRAME_ENABLED, 0xd100c3ffu},     /* sub sp, sp, #0x30 */
        {RBX_FRAME_TRAMPOLINE, CHK_TRAMPOLINE},           /* adrp x16, slot page */
        /* Reflection getters take VRService+0xc0; both are patched to return true. */
#if RBX_AVATAR_GESTURES_GET
        {RBX_AVATAR_GESTURES_GET, CHK_AVATAR_GET},
#endif
        {RBX_VR_ENABLED_GET, CHK_VR_ENABLED_GET},
    };
    for (size_t i = 0; i < sizeof(checks) / sizeof(checks[0]); i++) {
        if (*(const uint32_t *)(base + checks[i].offset) != checks[i].insn) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                    "Unknown libroblox.so at 0x%lx; Roblox VR pose disabled", (unsigned long)checks[i].offset);
            return false;
        }
    }
    rbx_set_user_cframe = (SetUserCFrameFn)(base + RBX_SET_USERCFRAME);
    rbx_set_user_cframe_enabled = (SetUserCFrameEnabledFn)(base + RBX_SET_USERCFRAME_ENABLED);
    /* The engine calls the trampoline on the DataModel thread every frame. */
    vr_flag_ptr = (volatile uint8_t *)(base + RBX_VR_FLAG_SLOT);
    *(void *volatile *)(base + RBX_FRAME_HOOK_SLOT) = (void *)pxr_frame_hook;
    rbx_state = 1;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Roblox VR pose writer ready");
    return true;
}

typedef struct { float x, y, z; } Vec3;

static Vec3 v3(float x, float y, float z) { Vec3 v = {x, y, z}; return v; }
static Vec3 v_norm(Vec3 v) {
    float n = sqrtf(v.x * v.x + v.y * v.y + v.z * v.z);
    return n > 1e-6f ? v3(v.x / n, v.y / n, v.z / n) : v3(0.f, 0.f, -1.f);
}
static Vec3 v_cross(Vec3 a, Vec3 b) {
    return v3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x);
}

/* CFrame = 3x3 row-major rotation whose columns are Right, Up, Back; then position. */
static void cframe_from_axes(float *out, Vec3 right, Vec3 up, Vec3 back, Vec3 pos) {
    out[0] = right.x; out[1] = up.x; out[2] = back.x;
    out[3] = right.y; out[4] = up.y; out[5] = back.y;
    out[6] = right.z; out[7] = up.z; out[8] = back.z;
    out[9] = pos.x; out[10] = pos.y; out[11] = pos.z;
}

static volatile bool gaze_mode; /* no hands tracked: the right pointer follows the view direction */

static Vec3 mat_mul(const float *m, Vec3 v) {
    return v3(m[0] * v.x + m[1] * v.y + m[2] * v.z,
              m[3] * v.x + m[4] * v.y + m[5] * v.z,
              m[6] * v.x + m[7] * v.y + m[8] * v.z);
}

static void head_cframe(float *out) {
    memcpy(out, head_rot, sizeof(head_rot));
    out[9] = head_pos_x;
    out[10] = head_pos_y;
    out[11] = head_pos_z;
}

/* Hand pose in head space from its normalized screen point, then into VR space. */
static void hand_cframe(float *out, const float *head, float sx, float sy, float angle,
                        float scale, float side) {
    const float tan_h = 0.95f, tan_v = 0.62f;
    /* The camera sits at the eyes: the hand lies on the camera ray, not on a ray from the
       shoulder (that pinned the hands low). Closer hands look bigger, so distance ~ 1/scale. */
    Vec3 ray = v_norm(v3((sx - 0.5f) * 2.f * tan_h, -(sy - 0.5f) * 2.f * tan_v, -1.f));
    float s = scale < 0.65f ? 0.65f : (scale > 1.55f ? 1.55f : scale);
    float dist = 1.5f / s;
    if (dist < 0.9f) dist = 0.9f;
    if (dist > 2.4f) dist = 2.4f;
    Vec3 local = v3(ray.x * dist, ray.y * dist, ray.z * dist);

    /* Controllers aim from the shoulder through the hand (Quest-style), so each hand
       points where it is held instead of both pointing along the camera ray. */
    Vec3 shoulder = (gaze_mode && side > 0.f) ? v3(0.f, 0.f, 0.f) : v3(0.6f * side, -0.9f, 0.1f);
    Vec3 dir = v_norm(v3(local.x - shoulder.x, local.y - shoulder.y, local.z - shoulder.z));
    Vec3 back = v3(-dir.x, -dir.y, -dir.z);
    Vec3 right = v_norm(v_cross(v3(0.f, 1.f, 0.f), back));
    Vec3 up = v_cross(back, right);
    float cr = cosf(-angle), sr = sinf(-angle);
    Vec3 rolled_right = v3(right.x * cr + up.x * sr, right.y * cr + up.y * sr, right.z * cr + up.z * sr);
    Vec3 rolled_up = v_cross(back, rolled_right);

    Vec3 pos = mat_mul(head, local);
    pos.x += head[9]; pos.y += head[10]; pos.z += head[11]; /* hands move with the 6DoF head */
    cframe_from_axes(out, mat_mul(head, rolled_right), mat_mul(head, rolled_up),
                     mat_mul(head, back), pos);
}

static pthread_mutex_t pose_lock = PTHREAD_MUTEX_INITIALIZER;
static float pose_head[12], pose_left[12], pose_right[12];
static bool pose_ready;

/* Sensor/camera threads: compute the newest pose; the frame hook hands it to Roblox. */
static void push_roblox_pose(void) {
    if (!roblox_pose_enabled || rbx_state != 1) return;
    float head[12], left[12], right[12];
    head_cframe(head);
    /* A hand the camera lost rests low in front of the body instead of freezing mid-air. */
    if (hand_left_active) hand_cframe(left, head, hand_left_x, hand_left_y, hand_left_angle, hand_left_scale, -1.f);
    else hand_cframe(left, head, 0.3f, 0.95f, 0.f, 1.f, -1.f);
    if (hand_right_active) hand_cframe(right, head, hand_right_x, hand_right_y, hand_right_angle, hand_right_scale, 1.f);
    else hand_cframe(right, head, 0.7f, 0.95f, 0.f, 1.f, 1.f);
    pthread_mutex_lock(&pose_lock);
    memcpy(pose_head, head, sizeof(head));
    memcpy(pose_left, left, sizeof(left));
    memcpy(pose_right, right, sizeof(right));
    pose_ready = true;
    pthread_mutex_unlock(&pose_lock);
}

/*
 * Runs inside Roblox's frame step on the DataModel thread (via the patched
 * resetUserCFrames trampoline), where firing VRService signals is allowed.
 * Uses the same setters an OpenXR headset session uses, so UserCFrameChanged
 * and UserCFrameEnabled reach the VR camera, laser pointers and the
 * VRAvatarGestures client that moves (and replicates) the avatar's arms.
 */
__attribute__((visibility("default"))) void pxr_frame_hook(uint8_t *vr) {
    if (!roblox_pose_enabled || vr == NULL) return;
    if (vr_flag_ptr == NULL || !*vr_flag_ptr) return; /* phone-menu phase: leave Roblox alone */
    if (cb_active) {
        /* Freshest predicted head for this frame. */
        cardboard_update();
        push_roblox_pose();
    }
    float head[12], left[12], right[12];
    pthread_mutex_lock(&pose_lock);
    bool ready = pose_ready;
    memcpy(head, pose_head, sizeof(head));
    memcpy(left, pose_left, sizeof(left));
    memcpy(right, pose_right, sizeof(right));
    pthread_mutex_unlock(&pose_lock);
    if (!ready) return;

    int virtual_input = vr[VRSERVICE_VIRTUAL_INPUT] ? 1 : 0;
#if VRSERVICE_AVATAR_GESTURES
    vr[VRSERVICE_AVATAR_GESTURES] = 1;
#endif
    /* Enabling fires UserCFrameEnabled in Lua; only do it when a slot is actually off. */
    for (int type = 0; type < 3; type++)
        if (!vr[VRSERVICE_USERCFRAME_ENABLED + type]) rbx_set_user_cframe_enabled(vr, type, 1, virtual_input);
    rbx_set_user_cframe(vr, 0, head, virtual_input);
    rbx_set_user_cframe(vr, 1, left, virtual_input);
    rbx_set_user_cframe(vr, 2, right, virtual_input);

    static int frames;
    if (++frames % 300 == 0) {
        float *slots = (float *)(vr + VRSERVICE_USERCFRAMES);
        __android_log_print(ANDROID_LOG_INFO, TAG,
                "Frame hook: virtual=%d enabled=%d%d%d L=%.2f,%.2f,%.2f R=%.2f,%.2f,%.2f stored R=%.2f,%.2f,%.2f",
                virtual_input, vr[VRSERVICE_USERCFRAME_ENABLED], vr[VRSERVICE_USERCFRAME_ENABLED + 1],
                vr[VRSERVICE_USERCFRAME_ENABLED + 2], left[9], left[10], left[11],
                right[9], right[10], right[11], slots[33], slots[34], slots[35]);
    }
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setGazeMode(JNIEnv *env, jclass type, jboolean on) {
    (void)env; (void)type;
    gaze_mode = on == JNI_TRUE;
}

/* Switches Roblox between the normal phone UI (false) and VR (true) at run time. */
JNIEXPORT jboolean JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setVrMode(JNIEnv *env, jclass type, jboolean on) {
    (void)env; (void)type;
    if (!resolve_roblox_vr()) return JNI_FALSE;
    *vr_flag_ptr = on == JNI_TRUE ? 1 : 0;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setRobloxPoseEnabled(JNIEnv *env, jclass type, jboolean enabled) {
    (void)env; (void)type;
    if (enabled == JNI_TRUE && !resolve_roblox_vr()) {
        roblox_pose_enabled = false;
        return JNI_FALSE;
    }
    roblox_pose_enabled = enabled == JNI_TRUE;
    if (roblox_pose_enabled) push_roblox_pose();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setHeadRotation(JNIEnv *env, jclass type,
        jfloatArray matrix, jfloat yaw, jfloat pitch) {
    (void)type;
    if ((*env)->GetArrayLength(env, matrix) < 9) return;
    float m[9];
    (*env)->GetFloatArrayRegion(env, matrix, 0, 9, m);
    memcpy(head_rot, m, sizeof(m));
    head_yaw = yaw;
    head_pitch = pitch;
    push_roblox_pose();
}

JNIEXPORT void JNICALL
Java_com_phonexr_roblox_PhoneXRBridge_setHeadPosition(JNIEnv *env, jclass type, jfloat x, jfloat y, jfloat z) {
    (void)env; (void)type;
    head_pos_x = x;
    head_pos_y = y;
    head_pos_z = z;
    push_roblox_pose();
}
