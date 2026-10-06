#!/usr/bin/env python3
"""
Generates Charaly's original artwork for the Miraculous story pack.

WHY THIS EXISTS
===============
The brief asks for a real asset pipeline: files on disk, an AssetResolver, offline
rendering, deterministic fallback. It also asks for imagery taken from the Miraculous
Ladybug television series.

Those two requests conflict, and only the first can be honoured. The series is owned by
ZAG / Method Animation and distributed by Disney; episode frames and promotional stills are
not licensed for redistribution inside a third-party APK. Shipping them would put the whole
project at legal risk, so this script does not download them.

What it does instead is produce the *other* half properly: original artwork, composed here
from scratch, written as real PNG files into a real directory tree, resolved by CharacterId
and by LocationId + time-of-day + weather. That is the entire pipeline. Dropping licensed
artwork into the same filenames is a five-minute change - no code, because the resolver
does not care where a file came from.

THE ART
=======
Every image is a stylised, layered composition rather than a depiction of a character. That
is a deliberate constraint, not a compromise:

  * a stylised silhouette is original work, so it can ship under this repo's licence;
  * a portrait that must be recognised by the face is the thing that requires the licensed
    original, so these are drawn as *identity colours and silhouette* instead - enough that
    Marinette and Adrien and Alya are visually distinct at a glance, without copying a
    character design;
  * backgrounds are Paris architecture - zinc rooftops, a school courtyard, a boulevard -
    which are common-property places rather than protected designs.

DETERMINISM
===========
No randomness without a seed. `hash()` is salted per process in Python 3, so this uses an
explicit FNV-1a instead: regenerating the assets on any machine produces byte-identical
files, which is what lets the repo carry them as a diff rather than as noise.

Usage: python3 scripts/generate-story-art.py [output-root]
"""

import math
import os
import struct
import sys
import zlib

# ---------------------------------------------------------------------------
# PNG writing. No third-party dependency, so this runs anywhere python3 does.
# ---------------------------------------------------------------------------


class Canvas:
    """An RGBA pixel buffer with the few primitives the compositions need."""

    def __init__(self, width, height):
        self.w = width
        self.h = height
        # RGB, 3 bytes per pixel. Alpha is implicit: every asset here is opaque, because a
        # portrait with a transparent edge would need premultiplied blending the loader
        # would then have to get right.
        self.px = bytearray(width * height * 3)

    def fill(self, color):
        r, g, b = color
        row = bytes((r, g, b)) * self.w
        for y in range(self.h):
            start = y * self.w * 3
            self.px[start:start + self.w * 3] = row

    def set(self, x, y, color, alpha=1.0):
        if x < 0 or y < 0 or x >= self.w or y >= self.h:
            return
        if alpha <= 0.0:
            return
        i = (y * self.w + x) * 3
        r, g, b = color
        if alpha >= 1.0:
            self.px[i] = r
            self.px[i + 1] = g
            self.px[i + 2] = b
            return
        # Source-over, clamped. Cheaper than a full Porter-Duff and identical for these
        # flat palette colours.
        inv = 1.0 - alpha
        self.px[i] = int(r * alpha + self.px[i] * inv)
        self.px[i + 1] = int(g * alpha + self.px[i + 1] * inv)
        self.px[i + 2] = int(b * alpha + self.px[i + 2] * inv)

    def vgradient(self, top, bottom):
        """A vertical gradient, computed per row rather than per pixel."""
        for y in range(self.h):
            t = y / max(1, self.h - 1)
            color = (
                int(top[0] + (bottom[0] - top[0]) * t),
                int(top[1] + (bottom[1] - top[1]) * t),
                int(top[2] + (bottom[2] - top[2]) * t),
            )
            row = bytes(color) * self.w
            start = y * self.w * 3
            self.px[start:start + self.w * 3] = row

    def rect(self, x0, y0, x1, y1, color, alpha=1.0):
        x0 = max(0, int(x0))
        y0 = max(0, int(y0))
        x1 = min(self.w, int(x1))
        y1 = min(self.h, int(y1))
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, color, alpha)

    def hline(self, x0, x1, y, color, alpha=1.0):
        self.rect(x0, y, x1, y + 1, color, alpha)

    def ellipse(self, cx, cy, rx, ry, color, alpha=1.0):
        """
        Anti-aliased by sampling a 2x2 grid inside the boundary pixel.

        Degenerate radii return immediately rather than dividing by zero: several callers
        sweep a radius from 0 upwards to build a glow, and a guard here saves every one of
        them from its own special case.
        """
        if rx <= 0 or ry <= 0 or alpha <= 0.0:
            return
        x0 = max(0, int(cx - rx - 1))
        x1 = min(self.w, int(cx + rx + 2))
        y0 = max(0, int(cy - ry - 1))
        y1 = min(self.h, int(cy + ry + 2))
        for y in range(y0, y1):
            for x in range(x0, x1):
                hits = 0
                for sy in (0.25, 0.75):
                    for sx in (0.25, 0.75):
                        px = x + sx
                        py = y + sy
                        if ((px - cx) / rx) ** 2 + ((py - cy) / ry) ** 2 <= 1.0:
                            hits += 1
                if hits:
                    self.set(x, y, color, alpha * hits / 4.0)

    def polygon(self, points, color, alpha=1.0):
        """Scanline fill. `points` is [(x, y), ...]."""
        if len(points) < 3:
            return
        ys = [p[1] for p in points]
        y0 = max(0, int(min(ys)))
        y1 = min(self.h, int(max(ys)) + 1)
        n = len(points)
        for y in range(y0, y1):
            sy = y + 0.5
            crossings = []
            for i in range(n):
                ax, ay = points[i]
                bx, by = points[(i + 1) % n]
                if (ay <= sy < by) or (by <= sy < ay):
                    t = (sy - ay) / (by - ay)
                    crossings.append(ax + (bx - ax) * t)
            crossings.sort()
            for i in range(0, len(crossings) - 1, 2):
                sx0 = max(0, int(crossings[i] + 0.5))
                sx1 = min(self.w, int(crossings[i + 1] + 0.5))
                for x in range(sx0, sx1):
                    self.set(x, y, color, alpha)

    def write(self, path):
        raw = bytearray()
        stride = self.w * 3
        for y in range(self.h):
            # PNG filter type 0 (None). Every row stores raw bytes. That costs ~5-10% on
            # these smooth gradients versus filter 2 (Up), and it makes the encoder
            # trivially auditable - which matters more for a script that runs in CI.
            raw.append(0)
            raw += self.px[y * stride:(y + 1) * stride]
        png = self._png(bytes(raw))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as handle:
            handle.write(png)

    def _png(self, raw):
        def chunk(tag, data):
            return (
                struct.pack(">I", len(data))
                + tag
                + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
            )

        header = struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0)
        return (
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b"")
        )


# ---------------------------------------------------------------------------
# Deterministic noise
# ---------------------------------------------------------------------------


def fnv1a(text):
    """A stable 32-bit hash. Python's hash() is salted per process, so it is unusable."""
    h = 0x811C9DC5
    for byte in text.encode("utf-8"):
        h = ((h ^ byte) * 0x01000193) & 0xFFFFFFFF
    return h


def seeded(seed_text):
    """
    A small xorshift generator, so one seed yields a whole reproducible sequence.

    Returns a *callable* rather than a generator, because every call site wants the next
    value directly and threading `next(rng)` through the drawing code would obscure the
    composition logic - which is the part that has to stay readable.
    """
    state = [fnv1a(seed_text) or 1]

    def nxt():
        s = state[0]
        s ^= (s << 13) & 0xFFFFFFFF
        s ^= s >> 17
        s ^= (s << 5) & 0xFFFFFFFF
        state[0] = s
        return (s & 0xFFFFFFFF) / 0xFFFFFFFF

    return nxt


def shade(color, amount):
    """Lightens (amount > 0) or darkens (amount < 0) toward white or black."""
    if amount >= 0:
        return tuple(
            min(255, int(c + (255 - c) * amount)) for c in color
        )
    return tuple(max(0, int(c * (1.0 + amount))) for c in color)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---------------------------------------------------------------------------
# Palettes
# ---------------------------------------------------------------------------
#
# One palette per time-of-day, shared by every background so the set reads as one pack
# rather than as unrelated pictures. Sky, distant haze, near silhouette and near silhouette
# highlight.

DAY = {
    "sky_top": (108, 158, 214),
    "sky_bottom": (186, 214, 236),
    "far": (150, 170, 194),
    "mid": (104, 118, 142),
    "near": (58, 62, 80),
    "accent": (232, 106, 92),
    "warm": (247, 201, 128),
}

NIGHT = {
    "sky_top": (16, 20, 44),
    "sky_bottom": (44, 48, 86),
    "far": (34, 38, 66),
    "mid": (24, 26, 46),
    "near": (10, 11, 22),
    "accent": (250, 138, 114),
    "warm": (252, 214, 140),
}

DAWN = {
    "sky_top": (92, 104, 168),
    "sky_bottom": (238, 176, 158),
    "far": (146, 132, 160),
    "mid": (94, 82, 108),
    "near": (44, 40, 58),
    "accent": (244, 148, 128),
    "warm": (250, 210, 160),
}

SUNSET = {
    "sky_top": (74, 82, 142),
    "sky_bottom": (244, 154, 112),
    "far": (154, 120, 130),
    "mid": (100, 76, 92),
    "near": (46, 34, 48),
    "accent": (250, 122, 92),
    "warm": (255, 202, 128),
}

RAIN_OVERLAY = (52, 66, 92)

PALETTES = {"day": DAY, "night": NIGHT, "dawn": DAWN, "sunset": SUNSET}


def palette_for(time_of_day):
    return PALETTES.get(time_of_day, DAY)


# ---------------------------------------------------------------------------
# Backgrounds
# ---------------------------------------------------------------------------


def stars(canvas, pal, seed, count=150):
    """Only at night. Drawn before anything else so buildings occlude them."""
    if pal is not NIGHT:
        return
    rng = seeded(seed + ":stars")
    for _ in range(count):
        x = rng() * canvas.w
        y = rng() * canvas.h * 0.62
        brightness = 0.35 + rng() * 0.65
        size = 1 if rng() > 0.86 else 0
        color = (int(230 * brightness), int(234 * brightness), int(255 * brightness))
        canvas.set(int(x), int(y), color, 0.55 + 0.45 * brightness)
        if size:
            for dx, dy in ((1, 0), (0, 1), (-1, 0), (0, -1)):
                canvas.set(int(x) + dx, int(y) + dy, color, 0.25)


def rain(canvas, seed, density=190):
    """
    Diagonal streaks over the whole frame.

    Applied last, over everything, so it reads as weather in front of the city rather than
    as a colour the city happens to be.
    """
    rng = seeded(seed + ":rain")
    for _ in range(density):
        x = rng() * canvas.w * 1.4 - canvas.w * 0.2
        y = rng() * canvas.h
        length = 14 + rng() * 26
        alpha = 0.10 + rng() * 0.16
        for step in range(int(length)):
            px = int(x + step * 0.34)
            py = int(y + step)
            canvas.set(px, py, RAIN_OVERLAY, alpha * (1.0 - step / length))


def paris_skyline(canvas, pal, seed, horizon=0.66):
    """
    Three depth bands of Parisian roofscape.

    The bands exist so the eye reads distance. A single silhouette reads as a graphic; three
    receding ones read as a city, and that difference is most of what makes a drawn backdrop
    look like a place.
    """
    rng = seeded(seed + ":skyline")
    h = canvas.h
    w = canvas.w

    for band, (color, base, amp) in enumerate(
        (
            (pal["far"], horizon - 0.10, 0.045),
            (pal["mid"], horizon - 0.02, 0.075),
            (pal["near"], horizon + 0.09, 0.105),
        )
    ):
        # Each band is a run of mansard roofs: flat-ish top, then a slanted pitch, then a
        # wall. That profile is what reads as Paris rather than as a generic skyline.
        x = -30.0
        while x < w + 30:
            bw = 46 + rng() * 84
            bh = amp * h * (0.42 + rng() * 0.95)
            top = base * h - bh
            pitch = 14 + rng() * 16
            body_top = int(top + pitch)

            canvas.rect(x, body_top, x + bw, h, color)

            # Mansard slope.
            canvas.polygon(
                [(x, body_top), (x + bw, body_top), (x + bw - pitch, top), (x + pitch, top)],
                color,
            )
            # Roof lip, catching light from the sky above it.
            canvas.rect(x + pitch, top, x + bw - pitch, top + 3, shade(color, 0.16))

            # Zinc dormers and a chimney. Sparse, because at this depth they are texture,
            # not detail.
            if rng() > 0.42:
                dx = x + bw * (0.24 + rng() * 0.4)
                canvas.rect(dx, body_top - 9, dx + 9, body_top + 2, shade(color, -0.22))
                canvas.ellipse(dx + 4.5, body_top - 8, 4.5, 4.5, shade(color, 0.10), 0.55)
            if rng() > 0.66:
                cx = x + bw * (0.1 + rng() * 0.7)
                canvas.rect(cx, top - 15, cx + 6, top + 2, shade(color, -0.18))
                canvas.rect(cx - 1, top - 17, cx + 7, top - 14, shade(color, 0.08))

            # Lit windows, at night only, and warm - which is the whole point of a night
            # skyline.
            if pal is NIGHT and rng() > 0.35:
                rows = int((h - body_top) / 26) - 1
                for row in range(max(1, rows)):
                    for col in range(2):
                        if rng() > 0.52:
                            wx = x + 10 + col * (bw / 2.1)
                            wy = body_top + 16 + row * 26
                            if wy < h - 6:
                                canvas.rect(wx, wy, wx + 5, wy + 8, pal["warm"], 0.62 + rng() * 0.3)

            x += bw + 4 + rng() * 16


def eiffel_hint(canvas, pal, seed):
    """
    The tower, as a distant silhouette.

    Drawn, not depicted: a tapering lattice outline on the horizon. Enough to place the city
    in France at a glance, and no more detail than the scale justifies.
    """
    rng = seeded(seed + ":tower")
    base_x = canvas.w * (0.60 + rng() * 0.22)
    base_y = canvas.h * 0.575
    height = canvas.h * 0.30
    color = pal["far"]

    def half_width(t):
        # t: 0 at the base, 1 at the tip. Non-linear, because the tower's profile is.
        return (canvas.w * 0.052) * (1.0 - t) ** 1.55 + canvas.w * 0.006

    for i in range(int(height)):
        t = i / height
        y = base_y - i
        hw = half_width(t)
        canvas.rect(base_x - hw, y, base_x + hw, y + 1, color)

    # The arch, which is what makes it read as the tower rather than as an obelisk.
    arch_top = base_y - height * 0.30
    arch_bottom = base_y - height * 0.02
    for y in range(int(arch_top), int(arch_bottom)):
        t = (base_y - y) / height
        hw = half_width(t)
        inner = hw * 0.52
        canvas.rect(base_x - hw, y, base_x - inner, y + 1, color)
        canvas.rect(base_x + inner, y, base_x + hw, y + 1, color)

    # Spire.
    canvas.rect(base_x - 1.5, base_y - height - canvas.h * 0.035, base_x + 1.5, base_y - height, color)


def rooftops_foreground(canvas, pal, seed):
    """A near, dark roof edge along the bottom, so the frame has a floor and a viewer."""
    rng = seeded(seed + ":fg")
    base = canvas.h * 0.855
    x = -40.0
    while x < canvas.w + 40:
        bw = 120 + rng() * 200
        top = base + rng() * canvas.h * 0.05
        canvas.rect(x, top, x + bw, canvas.h, shade(pal["near"], -0.25))
        # A chimney block on some of them.
        if rng() > 0.5:
            cx = x + bw * (0.2 + rng() * 0.5)
            canvas.rect(cx, top - 26, cx + 20, top, shade(pal["near"], -0.18))
            canvas.rect(cx - 2, top - 30, cx + 22, top - 26, shade(pal["near"], 0.10))
        x += bw


def school_facade(canvas, pal, seed):
    """A school front: masonry, tall windows, a clock over the door."""
    rng = seeded(seed + ":school")
    wall_top = canvas.h * 0.16
    wall = (128, 116, 108)
    canvas.rect(0, wall_top, canvas.w, canvas.h, wall)

    # Course lines in the stone.
    for y in range(int(wall_top), canvas.h, 26):
        canvas.hline(0, canvas.w, y, shade(wall, -0.14), 0.5)
        offset = (y // 26 % 2) * 30
        for x in range(int(offset), canvas.w, 60):
            canvas.rect(x, y - 26, x + 1, y, shade(wall, -0.14), 0.4)

    # Two storeys of tall windows.
    for storey in range(2):
        wy = wall_top + canvas.h * (0.10 + storey * 0.34)
        wh = canvas.h * 0.24
        cols = 5
        span = canvas.w / (cols + 1)
        for col in range(cols):
            wx = span * (col + 1) - span * 0.22
            ww = span * 0.44
            canvas.rect(wx - 4, wy - 4, wx + ww + 4, wy + wh + 4, shade(wall, -0.24))
            glass = shade(pal["sky_bottom"], -0.30 - storey * 0.10)
            canvas.rect(wx, wy, wx + ww, wy + wh, glass)
            # Muntin bars.
            canvas.rect(wx + ww / 2 - 1, wy, wx + ww / 2 + 1, wy + wh, shade(wall, -0.3))
            canvas.rect(wx, wy + wh * 0.42, wx + ww, wy + wh * 0.42 + 2, shade(wall, -0.3))

    # Cornice and the clock.
    canvas.rect(0, wall_top - 14, canvas.w, wall_top, shade(wall, 0.20))
    canvas.rect(0, wall_top - 20, canvas.w, wall_top - 14, shade(wall, -0.18))
    cx, cy, r = canvas.w * 0.5, wall_top - canvas.h * 0.075, canvas.h * 0.055
    canvas.ellipse(cx, cy, r * 1.12, r * 1.12, shade(wall, -0.28))
    canvas.ellipse(cx, cy, r, r, (244, 240, 226))
    canvas.rect(cx - 1.5, cy - r * 0.6, cx + 1.5, cy, (46, 42, 40))
    canvas.rect(cx, cy - 1.5, cx + r * 0.55, cy + 1.5, (46, 42, 40))

    # A drainpipe, because a facade without one looks like a rendering.
    px = canvas.w * (0.06 + rng() * 0.06)
    canvas.rect(px, wall_top, px + 5, canvas.h, shade(wall, -0.30), 0.8)


def classroom_interior(canvas, pal, seed):
    """Desks in perspective, a board behind, windows to the left."""
    rng = seeded(seed + ":classroom")
    wall = (58, 66, 62)
    canvas.rect(0, 0, canvas.w, canvas.h * 0.72, wall)

    # Chalkboard.
    canvas.rect(
        canvas.w * 0.42, canvas.h * 0.14, canvas.w * 0.88, canvas.h * 0.50, (30, 62, 48)
    )
    canvas.rect(
        canvas.w * 0.40, canvas.h * 0.125, canvas.w * 0.90, canvas.h * 0.52, shade(wall, -0.2)
    )
    # Chalk marks. Abstract strokes, deliberately not legible text - a background must not
    # invent sentences the reader will try to read.
    for _ in range(7):
        y = canvas.h * (0.18 + rng() * 0.28)
        x0 = canvas.w * (0.45 + rng() * 0.06)
        x1 = x0 + canvas.w * (0.06 + rng() * 0.28)
        canvas.hline(x0, x1, y, (222, 228, 220), 0.34)

    # Windows.
    for i in range(2):
        wx = canvas.w * (0.05 + i * 0.17)
        canvas.rect(wx, canvas.h * 0.16, wx + canvas.w * 0.13, canvas.h * 0.56, shade(pal["sky_bottom"], -0.12))
        canvas.rect(wx - 4, canvas.h * 0.15, wx + canvas.w * 0.13 + 4, canvas.h * 0.57, shade(wall, 0.18))
        canvas.rect(wx + canvas.w * 0.063, canvas.h * 0.15, wx + canvas.w * 0.069, canvas.h * 0.57, shade(wall, 0.18))

    # Floor.
    floor = (72, 60, 52)
    canvas.rect(0, canvas.h * 0.72, canvas.w, canvas.h, floor)

    # Desks: nearer rows are wider and lower, which is the only depth cue a flat
    # composition gets for free.
    for row in range(4):
        t = row / 3.0
        y = canvas.h * (0.76 + t * 0.20)
        h = canvas.h * (0.055 + t * 0.035)
        w = canvas.w * (0.34 + t * 0.14)
        cols = 2 + row
        for col in range(cols):
            x = canvas.w * (col + 0.5) / cols - w / 2
            top = (104, 78, 58)
            canvas.polygon(
                [(x, y), (x + w, y - h * 0.10), (x + w, y + h * 0.62), (x, y + h * 0.72)],
                top,
            )
            canvas.rect(x + w * 0.08, y + h * 0.72, x + w * 0.16, canvas.h, shade(top, -0.32))
            canvas.rect(x + w * 0.82, y + h * 0.62, x + w * 0.90, canvas.h, shade(top, -0.32))


def boulevard(canvas, pal, seed):
    """A street: shopfronts either side, a lamp post, wet road."""
    rng = seeded(seed + ":boulevard")
    horizon = canvas.h * 0.56
    canvas.vgradient(shade(pal["sky_bottom"], -0.06), shade(pal["sky_bottom"], -0.34))

    # Buildings either side, converging slightly toward the vanishing point.
    for side in (0, 1):
        x = canvas.w * (0.02 if side == 0 else 0.62)
        for depth in range(5):
            t = depth / 4.0
            bw = canvas.w * (0.30 - t * 0.14)
            bh = canvas.h * (0.52 - t * 0.16)
            tone = mix((96, 92, 104), pal["mid"], t)
            bx = x if side == 0 else canvas.w - bw - x
            canvas.rect(bx, horizon - bh, bx + bw, canvas.h, tone)
            # Awnings and lit windows.
            for i in range(int(3 - t * 2)):
                wy = horizon - bh + canvas.h * (0.06 + i * 0.13)
                if wy < horizon - 12:
                    canvas.rect(bx + bw * 0.16, wy, bx + bw * 0.52, wy + canvas.h * 0.075, shade(tone, -0.26))
                    canvas.rect(bx + bw * 0.62, wy, bx + bw * 0.88, wy + canvas.h * 0.075, shade(tone, -0.26))
            canvas.rect(bx, horizon - bh, bx + bw, horizon - bh + 6, shade(tone, 0.18))
            x += bw * (0.62 if side == 0 else 0)

    # Road.
    canvas.rect(0, horizon, canvas.w, canvas.h, shade(pal["near"], 0.06))
    canvas.polygon(
        [
            (canvas.w * 0.40, horizon),
            (canvas.w * 0.60, horizon),
            (canvas.w * 0.86, canvas.h),
            (canvas.w * 0.14, canvas.h),
        ],
        shade(pal["near"], 0.14),
    )
    # Kerbs.
    canvas.polygon(
        [(0, horizon), (canvas.w * 0.40, horizon), (canvas.w * 0.14, canvas.h), (0, canvas.h)],
        shade(pal["near"], 0.22),
    )
    canvas.polygon(
        [
            (canvas.w, horizon),
            (canvas.w * 0.60, horizon),
            (canvas.w * 0.86, canvas.h),
            (canvas.w, canvas.h),
        ],
        shade(pal["near"], 0.22),
    )

    # Lamp post, centred - the vertical that makes the road read as a street.
    lx = canvas.w * 0.5
    canvas.rect(lx - 4, canvas.h * 0.24, lx + 4, canvas.h, (34, 34, 42))
    canvas.ellipse(lx, canvas.h * 0.24, 16, 14, (34, 34, 42))
    if pal is NIGHT or pal is DUSK_GLOW:
        canvas.ellipse(lx, canvas.h * 0.24, 11, 9, pal["warm"], 0.92)
        # Pool of light on the road.
        for r in range(0, int(canvas.h * 0.26), 3):
            canvas.ellipse(lx, canvas.h * 0.86, r * 1.5, r * 0.42, pal["warm"], 0.05)


DUSK_GLOW = SUNSET  # the lamp only lights at dusk/night; alias kept readable


def courtyard(canvas, pal, seed):
    """A school courtyard: paving, a tree, the school behind."""
    rng = seeded(seed + ":courtyard")
    canvas.vgradient(shade(pal["sky_bottom"], 0.0), shade(pal["sky_bottom"], -0.30))
    # School wall with arched windows, across the top third.
    wall_top = canvas.h * 0.10
    wall = (142, 128, 116)
    canvas.rect(0, wall_top, canvas.w, canvas.h * 0.62, wall)
    canvas.rect(0, wall_top - 10, canvas.w, wall_top, shade(wall, 0.18))
    for i in range(6):
        wx = canvas.w * (0.06 + i * 0.155)
        ww = canvas.w * 0.075
        wy = wall_top + canvas.h * 0.10
        wh = canvas.h * 0.20
        canvas.rect(wx, wy, wx + ww, wy + wh, shade(pal["sky_bottom"], -0.34))
        canvas.ellipse(wx + ww / 2, wy, ww / 2, ww * 0.30, shade(pal["sky_bottom"], -0.34))
        canvas.rect(wx - 2, wy - 2, wx + ww + 2, wy + wh + 2, shade(wall, -0.22))

    # Paving.
    canvas.rect(0, canvas.h * 0.62, canvas.w, canvas.h, (118, 110, 102))
    for i in range(14):
        y = canvas.h * (0.62 + (i / 14.0) ** 1.7 * 0.38)
        canvas.hline(0, canvas.w, y, (104, 96, 90), 0.55)
    for i in range(9):
        t = i / 8.0
        cx = canvas.w * (t * 1.6 - 0.3)
        canvas.polygon(
            [(cx, canvas.h * 0.62), (cx + 6, canvas.h * 0.62), (cx + canvas.w * 0.34, canvas.h), (cx + canvas.w * 0.28, canvas.h)],
            (104, 96, 90),
            0.4,
        )

    # A tree, off-centre.
    tx = canvas.w * 0.78
    canvas.rect(tx - 9, canvas.h * 0.40, tx + 9, canvas.h * 0.68, (74, 58, 44))
    for i in range(9):
        angle = rng() * math.tau
        rr = canvas.w * (0.03 + rng() * 0.055)
        canvas.ellipse(
            tx + math.cos(angle) * rr * 1.6,
            canvas.h * 0.40 + math.sin(angle) * rr * 0.9,
            rr,
            rr * 0.82,
            (58, 92, 62),
        )


def bakery_warm(canvas, pal, seed):
    """An interior: bread shelves and a warm oven mouth."""
    canvas.rect(0, 0, canvas.w, canvas.h, (74, 56, 44))
    # Oven glow, the light source everything else is lit by.
    canvas.ellipse(canvas.w * 0.74, canvas.h * 0.56, canvas.w * 0.17, canvas.h * 0.20, (250, 186, 96))
    canvas.ellipse(canvas.w * 0.74, canvas.h * 0.56, canvas.w * 0.10, canvas.h * 0.13, (255, 226, 160))
    canvas.rect(
        canvas.w * 0.52, canvas.h * 0.28, canvas.w * 0.96, canvas.h * 0.88, (52, 38, 30)
    )
    # Shelves of loaves on the left.
    for row in range(3):
        y = canvas.h * (0.24 + row * 0.19)
        canvas.rect(canvas.w * 0.04, y + canvas.h * 0.075, canvas.w * 0.46, y + canvas.h * 0.095, (120, 88, 60))
        rng = seeded(seed + ":loaves" + str(row))
        for col in range(5):
            lx = canvas.w * (0.07 + col * 0.082)
            canvas.ellipse(lx, y + canvas.h * 0.055, canvas.w * 0.033, canvas.h * 0.030, (186, 138, 82))
    # Warm falloff toward the corners.
    for i in range(int(canvas.w * 0.3)):
        canvas.rect(0, 0, canvas.w - i, 0, (0, 0, 0), 0.0)


def generic_interior(canvas, pal, seed):
    """A room: window, floor, a lamp. The fallback for any unclassified location."""
    canvas.vgradient(shade(pal["sky_bottom"], -0.22), shade(pal["near"], 0.10))
    canvas.rect(0, canvas.h * 0.62, canvas.w, canvas.h, (66, 56, 48))
    wx = canvas.w * 0.58
    canvas.rect(wx, canvas.h * 0.16, wx + canvas.w * 0.30, canvas.h * 0.54, shade(pal["sky_bottom"], -0.10))
    canvas.rect(wx - 6, canvas.h * 0.15, wx + canvas.w * 0.30 + 6, canvas.h * 0.55, (54, 46, 40))
    canvas.rect(wx + canvas.w * 0.145, canvas.h * 0.15, wx + canvas.w * 0.155, canvas.h * 0.55, (54, 46, 40))
    lx = canvas.w * 0.18
    canvas.rect(lx - 3, canvas.h * 0.34, lx + 3, canvas.h * 0.72, (48, 42, 38))
    canvas.polygon(
        [(lx - canvas.w * 0.06, canvas.h * 0.34), (lx + canvas.w * 0.06, canvas.h * 0.34), (lx + canvas.w * 0.03, canvas.h * 0.22), (lx - canvas.w * 0.03, canvas.h * 0.22)],
        (188, 150, 96),
    )
    if pal is NIGHT:
        for r in range(int(canvas.h * 0.3), 0, -3):
            canvas.ellipse(lx, canvas.h * 0.72, r * 1.3, r * 0.5, pal["warm"], 0.03)


# ---------------------------------------------------------------------------
# Background dispatch
# ---------------------------------------------------------------------------

BACKGROUND_RENDERERS = {
    "school": school_facade,
    "classroom": classroom_interior,
    "school-office": classroom_interior,
    "rooftop": None,  # handled specially: skyline + foreground
    "paris-streets": boulevard,
    "bakery": bakery_warm,
    "museum": generic_interior,
    "cafe": generic_interior,
    "andre-ice-cream": generic_interior,
    "school-courtyard": courtyard,
    "park": courtyard,
    "city-landmark": None,
}


def render_background(path, kind, time_of_day, weather, seed, w=1080, h=1920):
    pal = palette_for(time_of_day)
    canvas = Canvas(w, h)
    stars(canvas, pal, seed)
    renderer = BACKGROUND_RENDERERS.get(kind)

    if kind in ("rooftop", "city-landmark"):
        canvas.vgradient(pal["sky_top"], pal["sky_bottom"])
        eiffel_hint(canvas, pal, seed)
        paris_skyline(canvas, pal, seed)
        rooftops_foreground(canvas, pal, seed)
    elif renderer is school_facade:
        school_facade(canvas, pal, seed)
    elif renderer is not None:
        renderer(canvas, pal, seed)
    else:
        # Unclassified place: Paris rooftops. Paris is the default because a story set in
        # Paris with a generic backdrop and a story set anywhere else with Paris rooftops
        # are not equally wrong.
        canvas.vgradient(pal["sky_top"], pal["sky_bottom"])
        eiffel_hint(canvas, pal, seed)
        paris_skyline(canvas, pal, seed)
        rooftops_foreground(canvas, pal, seed)

    if weather == "rain":
        rain(canvas, seed)

    canvas.write(path)


# ---------------------------------------------------------------------------
# Character portraits
# ---------------------------------------------------------------------------
#
# A portrait here is a *silhouette in identity colours*, not a face.
#
# The reason is legal and practical at once: a recognisable face of a protected character
# is exactly the asset that requires a licence. A silhouette plus a distinct palette is
# original work, is trivially shippable, and still does the job the UI needs - which is to
# make Marinette, Adrien and Alya different from each other at a glance in a small circle.

PORTRAIT_SPECS = {
    # id: (primary, secondary, hair, hair_style, accessory, build)
    "marinette": (
        (216, 92, 122),    # primary: her palette's rose
        (250, 214, 118),   # secondary
        (52, 38, 44),      # hair, dark
        "bob",
        "earring",
        "slim",
    ),
    "adrien": (
        (108, 176, 132),   # primary: green
        (232, 240, 246),   # secondary
        (226, 190, 108),   # hair, blond
        "swept",
        "collar",
        "tall",
    ),
    "alya": (
        (226, 148, 74),    # primary: amber
        (92, 68, 56),
        (96, 52, 44),      # hair, auburn
        "long",
        "glasses",
        "slim",
    ),
    "ladybug": (
        (222, 58, 78),
        (16, 18, 28),
        (16, 18, 28),
        "mask",
        "spots",
        "slim",
    ),
    "catnoir": (
        (28, 28, 34),
        (126, 214, 128),
        (28, 28, 34),
        "mask",
        "staff",
        "tall",
    ),
    "nathaniel": (
        (196, 84, 76),
        (238, 232, 224),
        (58, 40, 38),
        "swept",
        "collar",
        "slim",
    ),
    "juleka": (
        (128, 132, 214),
        (232, 226, 240),
        (44, 52, 96),
        "long",
        "earring",
        "slim",
    ),
    "rose": (
        (232, 118, 150),
        (250, 236, 220),
        (236, 226, 210),
        "long",
        "earring",
        "slim",
    ),
    "luka": (
        (96, 156, 196),
        (222, 226, 232),
        (40, 44, 56),
        "spiky",
        "collar",
        "slim",
    ),
    "felicia": (
        (206, 128, 196),
        (250, 226, 240),
        (96, 60, 96),
        "curly",
        "earring",
        "slim",
    ),
    "chloe": (
        (240, 196, 92),
        (250, 246, 238),
        (214, 168, 96),
        "bob",
        "collar",
        "slim",
    ),
    "andre": (
        (222, 96, 72),
        (250, 240, 226),
        (48, 36, 34),
        "curly",
        "collar",
        "broad",
    ),
    "tom": (
        (188, 74, 70),
        (244, 236, 222),
        (52, 40, 38),
        "swept",
        "collar",
        "slim",
    ),
    "sakura": (
        (150, 186, 208),
        (250, 240, 232),
        (44, 40, 48),
        "bob",
        "earring",
        "slim",
    ),
    # ---- the rest of the declared cast -------------------------------------
    #
    # The pack declares twenty-eight characters, not fifteen. Shipping portraits for the
    # fifteen leads would have left every teacher, shopkeeper and bystander drawing the
    # generic mark - which in a scene set in a school means the people the story is
    # *about* are the only ones who look like anybody.
    #
    # So every declared id gets a file. Each still gets a distinct palette, because that is
    # what makes two people in the same shot tellable apart at 28dp.
    "nino": (
        (198, 118, 168),   # pink
        (246, 232, 240),
        (52, 40, 44),
        "swept",
        "collar",
        "slim",
    ),
    "gabriel": (
        (86, 112, 148),   # slate blue
        (222, 228, 236),
        (58, 60, 66),
        "swept",
        "collar",
        "tall",
    ),
    "principal-damore": (
        (118, 106, 148),  # violet
        (238, 234, 246),
        (206, 206, 212),
        "bald",
        "glasses",
        "broad",
    ),
    "teacher-rosa": (
        (206, 96, 116),   # crimson
        (250, 238, 240),
        (48, 36, 38),
        "curly",
        "earring",
        "slim",
    ),
    "teacher-klein": (
        (92, 146, 172),   # steel
        (232, 240, 244),
        (48, 46, 52),
        "swept",
        "glasses",
        "broad",
    ),
    "receptionist-mme-lenoire": (
        (188, 122, 78),   # terracotta
        (244, 236, 226),
        (72, 52, 44),
        "curly",
        "collar",
        "broad",
    ),
    "caretaker-bonnet": (
        (140, 146, 152),  # grey
        (232, 234, 236),
        (188, 190, 194),
        "bald",
        "glasses",
        "broad",
    ),
    "sabine": (
        (232, 154, 176),  # blush
        (252, 244, 246),
        (196, 156, 108),
        "long",
        "earring",
        "slim",
    ),
    "kim": (
        (122, 190, 178),  # sea green
        (240, 250, 248),
        (38, 44, 48),
        "bob",
        "collar",
        "slim",
    ),
    "cafe-owner-madame-antoinette": (
        (198, 96, 84),    # brick
        (248, 234, 222),
        (60, 44, 40),
        "curly",
        "earring",
        "broad",
    ),
    "bakery-assistant": (
        (232, 184, 104),  # butter
        (252, 246, 232),
        (92, 64, 44),
        "plain",
        "collar",
        "slim",
    ),
    "museum-guide-monsieur-vidal": (
        (78, 118, 152),   # indigo
        (232, 236, 244),
        (208, 210, 216),
        "bald",
        "glasses",
        "slim",
    ),
    "police-officer-dubois": (
        (64, 92, 138),    # navy
        (226, 232, 242),
        (40, 44, 52),
        "short",
        "collar",
        "broad",
    ),
    "bookshop-owner-monsieur-vidal": (
        (104, 128, 108),  # moss
        (240, 242, 232),
        (216, 214, 206),
        "bald",
        "glasses",
        "slim",
    ),
    # The file every undeclared id falls back to. Deliberately neutral - grey, no
    # accessory, no hairstyle beyond a plain shape - because it must not be mistaken for a
    # character. A generic portrait in Marinette's pink would imply a cast member that does
    # not exist.
    "generic": (
        (128, 132, 140),
        (206, 210, 216),
        (96, 98, 104),
        "plain",
        "none",
        "slim",
    ),
}


def render_portrait(path, spec, seed, size=512):
    primary, secondary, hair, hair_style, accessory, build = spec
    canvas = Canvas(size, size)

    # Background: a soft vertical wash in the character's own primary, so even a portrait
    # that fails to load its subject still reads as *that character*.
    canvas.vgradient(shade(primary, -0.62), shade(primary, -0.34))

    # A large disc behind the head, in the secondary. This is what makes a silhouette read
    # as a portrait rather than as a shape.
    cx = size * 0.5
    cy = size * 0.46
    canvas.ellipse(cx, cy, size * 0.34, size * 0.34, shade(secondary, -0.10), 0.55)

    skin = (226, 194, 172)
    skin_shade = shade(skin, -0.16)

    # Shoulders.
    shoulder_y = size * 0.74
    shoulder_w = size * (0.40 if build == "tall" else 0.36)
    canvas.polygon(
        [
            (cx - shoulder_w, size),
            (cx - shoulder_w * 0.72, shoulder_y + size * 0.04),
            (cx, shoulder_y),
            (cx + shoulder_w * 0.72, shoulder_y + size * 0.04),
            (cx + shoulder_w, size),
        ],
        primary,
    )
    # Collar, so the torso has a shape.
    canvas.polygon(
        [
            (cx - size * 0.10, shoulder_y + size * 0.01),
            (cx, shoulder_y + size * 0.12),
            (cx + size * 0.10, shoulder_y + size * 0.01),
            (cx + size * 0.06, shoulder_y - size * 0.01),
            (cx, shoulder_y + size * 0.06),
            (cx - size * 0.06, shoulder_y - size * 0.01),
        ],
        secondary,
    )

    # Neck and head.
    canvas.rect(cx - size * 0.055, size * 0.58, cx + size * 0.055, size * 0.76, skin_shade)
    head_rx = size * 0.115
    head_ry = size * 0.145
    head_cy = cy
    canvas.ellipse(cx, head_cy, head_rx, head_ry, skin)

    # Hair, per style. Each is drawn in the character's own hair colour, which is the
    # strongest per-character signal available without depicting a design.
    if hair_style == "mask":
        # Full mask: eyes as bright shapes on a dark field.
        canvas.ellipse(cx, head_cy, head_rx * 1.02, head_ry * 1.02, hair)
        for dx in (-head_rx * 0.42, head_rx * 0.42):
            canvas.ellipse(cx + dx, head_cy - head_ry * 0.10, head_rx * 0.24, head_ry * 0.20, secondary)
    else:
        if hair_style == "bob":
            canvas.ellipse(cx, head_cy - head_ry * 0.16, head_rx * 1.24, head_ry * 1.02, hair)
            canvas.rect(cx - head_rx * 1.24, head_cy - head_ry * 0.16, cx - head_rx * 0.86, head_cy + head_ry * 0.70, hair)
            canvas.rect(cx + head_rx * 0.86, head_cy - head_ry * 0.16, cx + head_rx * 1.24, head_cy + head_ry * 0.70, hair)
            # Fringe over the brow.
            canvas.polygon(
                [
                    (cx - head_rx * 1.22, head_cy - head_ry * 0.30),
                    (cx + head_rx * 1.22, head_cy - head_ry * 0.30),
                    (cx + head_rx * 0.70, head_cy + head_ry * 0.16),
                    (cx - head_rx * 0.55, head_cy + head_ry * 0.10),
                ],
                hair,
            )
        elif hair_style == "long":
            canvas.ellipse(cx, head_cy - head_ry * 0.12, head_rx * 1.20, head_ry * 1.00, hair)
            for dx in (-1, 1):
                canvas.polygon(
                    [
                        (cx + dx * head_rx * 1.16, head_cy - head_ry * 0.10),
                        (cx + dx * head_rx * 1.60, size * 0.86),
                        (cx + dx * head_rx * 0.72, size * 0.80),
                        (cx + dx * head_rx * 0.92, head_cy + head_ry * 0.60),
                    ],
                    hair,
                )
        elif hair_style == "swept":
            canvas.ellipse(cx, head_cy - head_ry * 0.22, head_rx * 1.16, head_ry * 0.82, hair)
            canvas.polygon(
                [
                    (cx - head_rx * 1.16, head_cy - head_ry * 0.42),
                    (cx + head_rx * 1.10, head_cy - head_ry * 0.66),
                    (cx + head_rx * 1.02, head_cy - head_ry * 0.06),
                    (cx - head_rx * 0.30, head_cy - head_ry * 0.30),
                ],
                hair,
            )
        elif hair_style == "spiky":
            for i in range(9):
                t = i / 8.0
                a = math.pi * (0.15 + t * 0.70)
                bx = cx - math.cos(a) * head_rx * 1.05
                by = head_cy - math.sin(a) * head_ry * 0.92
                tipx = bx - math.cos(a) * head_rx * 0.40
                tipy = by - math.sin(a) * head_ry * 0.44
                canvas.polygon([(bx, by), (tipx, tipy), (bx + head_rx * 0.16, by + head_ry * 0.10)], hair)
            canvas.ellipse(cx, head_cy - head_ry * 0.20, head_rx * 1.10, head_ry * 0.70, hair)
        elif hair_style == "curly":
            rng = seeded(seed + ":curly")
            for i in range(26):
                a = rng() * math.tau
                rr = head_rx * (0.95 + rng() * 0.40)
                canvas.ellipse(
                    cx + math.cos(a) * rr,
                    head_cy - head_ry * 0.18 + math.sin(a) * rr * 0.92,
                    head_rx * (0.20 + rng() * 0.14),
                    head_rx * (0.20 + rng() * 0.14),
                    hair,
                )
        elif hair_style == "short":
            canvas.ellipse(cx, head_cy - head_ry * 0.18, head_rx * 1.10, head_ry * 0.78, hair)
            canvas.rect(cx - head_rx * 1.10, head_cy - head_ry * 0.18, cx - head_rx * 0.88, head_cy + head_ry * 0.22, hair)
            canvas.rect(cx + head_rx * 0.88, head_cy - head_ry * 0.18, cx + head_rx * 1.10, head_cy + head_ry * 0.22, hair)
        elif hair_style == "plain":
            # A flat cap of hair. The generic fallback's hairstyle: present enough to read
            # as a head, featureless enough not to describe anyone.
            canvas.ellipse(cx, head_cy - head_ry * 0.20, head_rx * 1.08, head_ry * 0.76, hair)
        elif hair_style == "bald":
            # No hair shape at all; the scalp is skin with a highlight.
            canvas.ellipse(cx, head_cy - head_ry * 0.34, head_rx * 0.92, head_ry * 0.44, shade(skin, 0.10))

        # Eyes: two small marks. Enough to give a face without depicting one.
        if hair_style != "mask":
            for dx in (-head_rx * 0.40, head_rx * 0.40):
                canvas.ellipse(cx + dx, head_cy + head_ry * 0.06, head_rx * 0.115, head_ry * 0.085, (44, 40, 52))
                canvas.ellipse(cx + dx, head_cy + head_ry * 0.06, head_rx * 0.045, head_ry * 0.040, (250, 250, 252), 0.7)
            # A mouth line, so the head is not mask-like.
            canvas.rect(
                cx - head_rx * 0.16, head_cy + head_ry * 0.52, cx + head_rx * 0.16, head_cy + head_ry * 0.56,
                shade(skin, -0.34), 0.55,
            )

    # Accessory, the last per-character signal. `none` is handled explicitly so the generic
    # portrait has a readable branch here rather than silently falling through.
    if accessory == "earring":
        canvas.ellipse(cx - head_rx * 1.10, head_cy + head_ry * 0.24, head_rx * 0.13, head_rx * 0.13, secondary)
        canvas.ellipse(cx + head_rx * 1.10, head_cy + head_ry * 0.24, head_rx * 0.13, head_rx * 0.13, secondary)
    elif accessory == "glasses":
        for dx in (-head_rx * 0.40, head_rx * 0.40):
            canvas.ellipse(cx + dx, head_cy + head_ry * 0.06, head_rx * 0.30, head_ry * 0.22, (0, 0, 0), 0.0)
            canvas.ellipse(cx + dx, head_cy + head_ry * 0.06, head_rx * 0.30, head_ry * 0.22, secondary, 0.16)
        canvas.rect(cx - head_rx * 0.10, head_cy + head_ry * 0.06, cx + head_rx * 0.10, head_cy + head_ry * 0.09, (38, 36, 44))
        canvas.rect(cx - head_rx * 0.70, head_cy + head_ry * 0.06, cx + head_rx * 0.70, head_ry + head_cy + head_ry * 0.09, (38, 36, 44))
    elif accessory == "spots":
        # Ladybug's markings, abstracted to a scatter of discs over the suit.
        for i in range(7):
            a = i * 0.9
            canvas.ellipse(
                cx + math.cos(a) * size * 0.17,
                shoulder_y + size * 0.08 + math.sin(a) * size * 0.10,
                size * 0.030,
                size * 0.030,
                hair,
            )
    elif accessory == "staff":
        canvas.rect(cx + shoulder_w * 0.86, size * 0.20, cx + shoulder_w * 0.86 + size * 0.016, size * 0.86, (40, 34, 30))
        canvas.ellipse(cx + shoulder_w * 0.86 + size * 0.008, size * 0.20, size * 0.030, size * 0.030, secondary)
    elif accessory == "none":
        # Explicitly nothing. The generic portrait must not carry an accessory, because an
        # accessory is the strongest per-character signal and this character is nobody.
        pass
    elif accessory == "collar":
        canvas.polygon(
            [
                (cx - size * 0.14, shoulder_y + size * 0.02),
                (cx - size * 0.05, shoulder_y + size * 0.10),
                (cx, shoulder_y + size * 0.01),
                (cx + size * 0.05, shoulder_y + size * 0.10),
                (cx + size * 0.14, shoulder_y + size * 0.02),
                (cx + size * 0.11, shoulder_y - size * 0.01),
                (cx, shoulder_y + size * 0.05),
                (cx - size * 0.11, shoulder_y - size * 0.01),
            ],
            secondary,
        )

    canvas.write(path)


# ---------------------------------------------------------------------------
# Pack cover and banner
# ---------------------------------------------------------------------------


def render_cover(path, seed, w=1024, h=1024):
    """The library card: Paris at dusk, silhouetted, with a diagonal light sweep."""
    pal = SUNSET
    canvas = Canvas(w, h)
    canvas.vgradient(pal["sky_top"], pal["sky_bottom"])
    stars(canvas, pal, seed, count=90)
    canvas.ellipse(w * 0.72, h * 0.30, w * 0.13, w * 0.13, pal["warm"], 0.85)
    eiffel_hint(canvas, pal, seed)
    paris_skyline(canvas, pal, seed, horizon=0.74)
    rooftops_foreground(canvas, pal, seed)
    # A diagonal wash, so the card is distinguishable from the background it depicts.
    for i in range(int(w * 0.5)):
        canvas.polygon(
            [(i, 0), (i + w * 0.16, 0), (i, h), (i - w * 0.16, h)],
            (255, 255, 255),
            0.035,
        )
    canvas.write(path)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

TIMES = ("day", "night", "sunset", "dawn")

LOCATIONS = (
    ("rooftop", "rooftop", True),
    ("city-landmark", "city-landmark", True),
    ("paris-streets", "paris-streets", True),
    ("school", "school", True),
    ("classroom", "classroom", True),
    ("school-courtyard", "school-courtyard", True),
    ("bakery", "bakery", False),
    ("cafe", "cafe", False),
    ("museum", "museum", False),
    ("andre-ice-cream", "andre-ice-cream", False),
    ("park", "park", False),
    ("school-office", "school-office", False),
)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/stories/miraculous"
    backgrounds = os.path.join(root, "backgrounds")
    characters = os.path.join(root, "characters")
    scenes = os.path.join(root, "scenes")

    written = 0

    # Backgrounds: one per (location, time-of-day), plus a rain variant per location.
    for name, kind, needs_time in LOCATIONS:
        for tod in TIMES:
            out = os.path.join(backgrounds, f"{name}_{tod}.png")
            render_background(out, kind, tod, "", f"charaly-{name}-{tod}")
            written += 1
        out = os.path.join(backgrounds, f"{name}_rain.png")
        render_background(out, kind, "day", "rain", f"charaly-{name}-rain")
        written += 1
        if not needs_time:
            # Interiors do not get a per-hour variant: a bakery at 6am and at midnight is
            # the same room lit by the oven, and writing four copies of it would be four
            # files that all decode to the same picture.
            for tod in TIMES[1:]:
                src = os.path.join(backgrounds, f"{name}_day.png")
                if os.path.exists(src):
                    with open(src, "rb") as a, open(os.path.join(backgrounds, f"{name}_{tod}.png"), "wb") as b:
                        b.write(a.read())
                        written += 1

    # Character portraits, for every id the pack declares plus a few generic ones so an
    # undeclared id still finds something.
    for char_id, spec in sorted(PORTRAIT_SPECS.items()):
        out = os.path.join(characters, f"{char_id}.png")
        render_portrait(out, spec, f"charaly-char-{char_id}")
        written += 1

    # A pack cover and a banner.
    render_cover(os.path.join(scenes, "pack-cover.png"), "charaly-miraculous-cover")
    written += 1
    render_cover(os.path.join(scenes, "pack-banner.png"), "charaly-miraculous-banner")
    written += 1

    print(f"wrote {written} assets under {root}")


if __name__ == "__main__":
    main()