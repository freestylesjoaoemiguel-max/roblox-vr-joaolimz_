#!/usr/bin/env python3
"""Regenerates profiles/3222/profile.json from a stock 3222 libroblox.so (arm64).
usage: gen_profile_3222.py path/to/libroblox.so"""
import json, struct, sys
d = open(sys.argv[1], "rb").read()

def adrp(pc, t, rd=16):
    imm = ((t >> 12) - (pc >> 12)) & 0x1fffff
    return 0x90000000 | ((imm & 3) << 29) | ((imm >> 2) << 5) | rd
def ldrb(rt, rn, off): return 0x39400000 | (off << 10) | (rn << 5) | rt
def b(frm, to): return 0x14000000 | (((to - frm) // 4) & 0x3ffffff)
def bl(frm, to): return 0x94000000 | (((to - frm) // 4) & 0x3ffffff)
NOP, RET = 0xd503201f, 0xd65f03c0

TRAMP, SLOT, FLAG = 0x4a28dac, 0x71b6ff0, 0x71b6ff8
CAVE0, CAVE1 = 0x4a28dc0, 0x4a28dcc        # inside the dead tail of resetUserCFrames
P = []
def add(off, words):
    a = struct.pack("<%dI" % len(words), *words)
    P.append({"off": hex(off), "before": d[off:off + len(a)].hex(), "after": a.hex()})

ldr = 0xf9400000 | ((SLOT & 0xfff) // 8 << 10) | (16 << 5) | 16
add(TRAMP, [adrp(TRAMP, SLOT), ldr, 0xb4000050, 0xd61f0200, RET])      # frame hook trampoline
add(CAVE0, [adrp(CAVE0, FLAG), ldrb(0, 16, FLAG & 0xfff), RET,         # w0 = vr_flag
            adrp(CAVE1, FLAG), ldrb(1, 16, FLAG & 0xfff), RET])        # w1 = vr_flag
add(0x263bed0, [b(0x263bed0, CAVE0)])                  # UserInputService.VREnabled getter
add(0x24a0114, [b(0x24a0114, CAVE0), NOP])             # VRService.VREnabled getter (this-adjust thunk)
add(0x272fc90, [bl(0x272fc90, CAVE1)])                 # per-frame setVREnabled(false) -> setVREnabled(flag)
for o in (0x5981184, 0x59812b0, 0x59813dc, 0x598157c): add(o, [NOP])   # VR virtual-input entry points
add(0x2bb1614, [NOP, 0x52800020])
add(0x259b66c, [0x52800028]); add(0x259c440, [0x52800028])             # prefer GLES
json.dump({"versionCode": 3222, "lib": "libphonexrvr_3222.so", "patches": P}, open("profiles/3222/profile.json", "w"), indent=1)
for p in P: print(p)
