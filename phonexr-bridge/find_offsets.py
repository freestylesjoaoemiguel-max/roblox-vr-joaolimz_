#!/usr/bin/env python3
"""Find where code at known offsets of an old libroblox.so moved to in a new build.

For each old offset, a window of ARM64 instructions around it is compared with the new
library, masking the bits that change when code is relinked (branch targets, adrp pages,
literal loads, and the low 12 bits added to an adrp page). A run of instructions that never
change is searched exactly first, then every candidate is scored over the whole window.

usage: find_offsets.py old/libroblox.so new/libroblox.so 0xOFFSET [0xOFFSET ...]
"""

import struct
import sys

BEFORE, AFTER = 40, 40  # instructions around the offset


def masks(words):
    """Per-instruction mask of the bits that survive relinking."""
    out = []
    adrp_regs = {}
    for i, w in enumerate(words):
        m = 0xFFFFFFFF
        if (w & 0x7C000000) == 0x14000000:          # B / BL
            m = 0xFC000000
        elif (w & 0xFF000010) == 0x54000000:        # B.cond
            m = 0xFF00001F
        elif (w & 0x7E000000) == 0x34000000:        # CBZ / CBNZ
            m = 0xFF00001F
        elif (w & 0x7E000000) == 0x36000000:        # TBZ / TBNZ
            m = 0xFFF8001F
        elif (w & 0x1F000000) == 0x10000000:        # ADR / ADRP
            m = 0x9F00001F
            adrp_regs[w & 31] = i
        elif (w & 0x3B000000) == 0x18000000:        # LDR literal
            m = 0xFF00001F
        else:
            rn = (w >> 5) & 31
            near = rn in adrp_regs and i - adrp_regs[rn] <= 4
            if near and ((w & 0x7F800000) == 0x11000000 or (w & 0x3B000000) == 0x39000000):
                m = 0xFFC003FF                     # ADD imm / LDR-STR uimm on an adrp page
        out.append(m)
    return out


def words_at(data, off, n):
    return list(struct.unpack_from("<%dI" % n, data, off))


def find(old, new, offset, text_end, before=BEFORE, after=AFTER):
    start = max(0, offset - before * 4)
    n = (offset - start) // 4 + after
    ow = words_at(old, start, n)
    om = masks(ow)
    centre = (offset - start) // 4
    # Runs of fully-kept instructions, longest first; each is searched exactly.
    runs, run = [], None
    for i, m in enumerate(om + [0]):
        if m == 0xFFFFFFFF:
            run = i if run is None else run
        elif run is not None:
            if i - run >= 3:
                runs.append((run, i))
            run = None
    runs.sort(key=lambda r: (-(r[1] - r[0]), abs((r[0] + r[1]) // 2 - centre)))
    # Instructions near the patch count more: neighbours further away may have changed.
    weight = [3 if abs(i - centre) <= 8 else 1 for i in range(n)]
    total = sum(weight)
    scored = {}
    for a, b in runs[:8]:
        anchor = old[start + a * 4:start + b * 4]
        pos = new.find(anchor)
        hits = 0
        while 0 <= pos < text_end and hits < 4000:
            hits += 1
            if pos % 4 == 0 and pos - a * 4 >= 0:
                cstart = pos - a * 4
                cand = cstart + (offset - start)
                if cand not in scored:
                    nw = words_at(new, cstart, n)
                    scored[cand] = sum(wt for x, y, m, wt in zip(ow, nw, om, weight) if (x & m) == (y & m))
            pos = new.find(anchor, pos + 4)
    if not scored:
        return None, 0, []
    top = sorted(((v, k) for k, v in scored.items()), reverse=True)
    return top[0][1], top[0][0] / total, [(v * 100 // total, k) for v, k in top[:3]]


def main():
    old = open(sys.argv[1], "rb").read()
    new = open(sys.argv[2], "rb").read()
    text_end = 0x638ca10
    for arg in sys.argv[3:]:
        off = int(arg, 16)
        hit, score, top = find(old, new, off, text_end)
        if hit is None:
            print(f"0x{off:08x} -> NOT FOUND")
            continue
        others = ", ".join(f"0x{o:x}:{s}" for s, o in top[1:])
        print(f"0x{off:08x} -> 0x{hit:08x}  match {score:.0%}  "
              f"old {old[off:off+8].hex()} new {new[hit:hit+8].hex()}  [{others}]")


if __name__ == "__main__":
    main()
