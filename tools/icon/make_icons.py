#!/usr/bin/env python3
"""Generates the Android icon resources from tools/icon/app-icon.svg (1024x1024 artwork).

  python3 tools/icon/make_icons.py          (needs: pip install shapely)

Writes
  drawable/ic_launcher_background.xml  teal gradient (adaptive icon background, full bleed)
  drawable/ic_launcher_foreground.xml  calendar + AI bubble in colour (also the splash icon)
  drawable/ic_launcher_monochrome.xml  one-colour silhouette for themed icons (Android 13+)
  drawable/ic_notification.xml         white 24dp status-bar icon (Android only uses its alpha)

The geometry below is copied from app-icon.svg; change both together.
Monochrome versions have no overlapping shapes: the calendar is cut back around the bubble with a gap,
and the header line, the tick and the sparkle are holes, so they stay readable in a single colour.
"""
import math, re
from pathlib import Path
from shapely.geometry import LineString, Polygon, box
from shapely.ops import unary_union

RES = Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"

# ---- artwork (SVG coordinates, 1024 canvas) -------------------------------------------------------------
CAL = "M308,304H660A76,76 0 0 1 736,380V698A76,76 0 0 1 660,774H308A76,76 0 0 1 232,698V380A76,76 0 0 1 308,304Z"
CAL_SHADOW = "M308,322H660A76,76 0 0 1 736,398V716A76,76 0 0 1 660,792H308A76,76 0 0 1 232,716V398A76,76 0 0 1 308,322Z"
HEADER = "M232,446H736"
TABS = "M360,274V340M574,274V340"
TICK = "M363,590L443,670L599,514"
BUBBLE = "M641,208H786Q844,208 844,266V366Q844,424 786,424H718L656,474V424H641Q583,424 583,366V266Q583,208 641,208Z"
BUBBLE_SHADOW = "M641,222H786Q844,222 844,280V380Q844,438 786,438H718L656,488V438H641Q583,438 583,380V280Q583,222 641,222Z"
SPARKLE = "M713,250C722,290 738,306 778,315C738,324 722,340 713,380C704,340 688,324 648,315C688,306 704,290 713,250Z"
PLUS = "M790,251V279M776,265H804"

# Adaptive icon: the 1024 tile maps onto the visible 72dp of the 108dp canvas, so proportions match the artwork
# and the bubble's outer corner stays inside the 66dp safe circle (about 31.6dp from the centre).
S, T = 72 / 1024, 18.0


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def transform_path(d, s, tx, ty):
    """Uniform scale + translate of an absolute SVG path (M L H V Q C A Z)."""
    out = []
    for cmd, args in re.findall(r"([MLHVQCAZ])([^MLHVQCAZ]*)", d):
        n = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?", args)]
        if cmd == "H":
            n = [x * s + tx for x in n]
        elif cmd == "V":
            n = [y * s + ty for y in n]
        elif cmd == "A":
            n = [n[0] * s, n[1] * s, n[2], n[3], n[4], n[5] * s + tx, n[6] * s + ty]
            out.append("A" + ",".join(fmt(n[i]) for i in (0, 1)) + " " + " ".join(f"{int(n[i])}" for i in (2, 3, 4)) + " " + fmt(n[5]) + "," + fmt(n[6]))
            continue
        else:
            n = [v * s + (tx if i % 2 == 0 else ty) for i, v in enumerate(n)]
        out.append(cmd + " ".join(",".join(fmt(v) for v in n[i:i + 2]) for i in range(0, len(n), 2)))
    return "".join(out)


# ---- path → polygon (for the monochrome boolean operations) ---------------------------------------------
def sample(d, steps=24):
    pts, subpaths, cur = [], [], (0.0, 0.0)
    for cmd, args in re.findall(r"([MLHVQCAZ])([^MLHVQCAZ]*)", d):
        n = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?", args)]
        if cmd == "M":
            if pts:
                subpaths.append(pts)
            cur = (n[0], n[1]); pts = [cur]
        elif cmd == "L":
            cur = (n[0], n[1]); pts.append(cur)
        elif cmd == "H":
            cur = (n[0], cur[1]); pts.append(cur)
        elif cmd == "V":
            cur = (cur[0], n[0]); pts.append(cur)
        elif cmd == "Q":
            (x0, y0), (x1, y1), (x2, y2) = cur, n[0:2], n[2:4]
            for k in range(1, steps + 1):
                t = k / steps
                pts.append(((1 - t) ** 2 * x0 + 2 * (1 - t) * t * x1 + t * t * x2, (1 - t) ** 2 * y0 + 2 * (1 - t) * t * y1 + t * t * y2))
            cur = (x2, y2)
        elif cmd == "C":
            (x0, y0), (x1, y1), (x2, y2), (x3, y3) = cur, n[0:2], n[2:4], n[4:6]
            for k in range(1, steps + 1):
                t, u = k / steps, 1 - k / steps
                pts.append((u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3, u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3))
            cur = (x3, y3)
        elif cmd == "A":  # only quarter-circle corners of rounded rectangles occur here
            r, x2, y2 = n[0], n[5], n[6]
            x0, y0 = cur
            # centre: corner arcs turn clockwise (sweep 1)
            dx, dy = x2 - x0, y2 - y0
            cx, cy = (x0, y2) if dx * dy > 0 else (x2, y0)
            a0, a1 = math.atan2(y0 - cy, x0 - cx), math.atan2(y2 - cy, x2 - cx)
            if a1 < a0:
                a1 += 2 * math.pi
            for k in range(1, steps + 1):
                a = a0 + (a1 - a0) * k / steps
                pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
            cur = (x2, y2)
    if pts:
        subpaths.append(pts)
    return subpaths


def poly(d):
    return unary_union([Polygon(p) for p in sample(d) if len(p) > 2])


def stroke(d, width, cap=1):
    return unary_union([LineString(p).buffer(width / 2, cap_style=cap, join_style=1, quad_segs=12) for p in sample(d)])


def silhouette(gap, with_plus):
    calendar = unary_union([poly(CAL), stroke(TABS, 44)])
    bubble = poly(BUBBLE)
    calendar = calendar.difference(box(232, 446 - 12, 736, 446 + 12)).difference(stroke(TICK, 52)).difference(bubble.buffer(gap, join_style=1))
    bubble = bubble.difference(poly(SPARKLE))
    if with_plus:
        bubble = bubble.difference(stroke(PLUS, 10))
    parts = []
    for g in (calendar, bubble):
        parts += list(getattr(g, "geoms", [g]))
    return [p for p in parts if p.area > 900]  # drop slivers (e.g. what is left of the tab under the bubble)


def polys_to_path(polys, s, tx, ty, tol):
    out = []
    for p in polys:
        p = p.simplify(tol)
        for ring in [p.exterior, *p.interiors]:
            c = list(ring.coords)[:-1]
            out.append("M" + "L".join(f"{fmt(x * s + tx)},{fmt(y * s + ty)}" for x, y in c) + "Z")
    return "".join(out)


# ---- writers ---------------------------------------------------------------------------------------------
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/icon/make_icons.py from tools/icon/app-icon.svg. Do not edit by hand. -->\n'


def vector(w, vp, body, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return (HEAD + f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            f'    android:width="{w}dp"\n    android:height="{w}dp"\n    android:viewportWidth="{vp}"\n    android:viewportHeight="{vp}">\n{body}</vector>\n')


def gradient(x1, y1, x2, y2, c1, c2):
    return (f'        <aapt:attr name="android:fillColor">\n          <gradient android:type="linear" android:startX="{fmt(x1)}" android:startY="{fmt(y1)}"'
            f' android:endX="{fmt(x2)}" android:endY="{fmt(y2)}" android:startColor="{c1}" android:endColor="{c2}" />\n        </aapt:attr>\n')


def tp(d):
    return transform_path(d, S, T, T)


def main():
    # background: gradient runs across the visible tile (18..90dp), like the SVG's tile gradient
    bg = ("    <path android:pathData=\"M0,0h108v108h-108z\">\n" + gradient(T, T, 108 - T, 108 - T, "#FF29D8CD", "#FF08A9AD") + "    </path>\n")
    (RES / "ic_launcher_background.xml").write_text(vector(108, 108, bg, aapt=True))

    w = lambda v: fmt(v * S)
    fg = (
        f'    <path android:fillColor="#FF006C80" android:fillAlpha="0.12" android:pathData="{tp(CAL_SHADOW)}" />\n'
        f'    <path android:fillColor="#FFFFFFFF" android:pathData="{tp(CAL)}" />\n'
        f'    <path android:strokeColor="#FF12B9B9" android:strokeWidth="{w(24)}" android:pathData="{tp(HEADER)}" />\n'
        f'    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{w(44)}" android:strokeLineCap="round" android:pathData="{tp(TABS)}" />\n'
        f'    <path android:strokeColor="#FF0BB4B5" android:strokeWidth="{w(52)}" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{tp(TICK)}" />\n'
        f'    <path android:fillColor="#FF4D329C" android:fillAlpha="0.13" android:pathData="{tp(BUBBLE_SHADOW)}" />\n'
        f'    <path android:pathData="{tp(BUBBLE)}">\n' + gradient(583 * S + T, 208 * S + T, 844 * S + T, 474 * S + T, "#FF9675F6", "#FF7044D8") + '    </path>\n'
        f'    <path android:fillColor="#FFFFFFFF" android:pathData="{tp(SPARKLE)}" />\n'
        f'    <path android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.85" android:strokeWidth="{w(10)}" android:strokeLineCap="round" android:pathData="{tp(PLUS)}" />\n'
    )
    (RES / "ic_launcher_foreground.xml").write_text(vector(108, 108, fg, aapt=True))

    mono = polys_to_path(silhouette(gap=24, with_plus=True), S, T, T, tol=0.6)
    (RES / "ic_launcher_monochrome.xml").write_text(vector(108, 108, f'    <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="{mono}" />\n'))

    # status bar: artwork bounds 232..844 x 208..792 fitted into 22dp, centred in 24dp; wider gap, no plus
    s = 22 / 612
    tx, ty = 1 - 232 * s, (24 - 584 * s) / 2 - 208 * s
    notif = polys_to_path(silhouette(gap=34, with_plus=False), s, tx, ty, tol=1.2)
    (RES / "ic_notification.xml").write_text(vector(24, 24, f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{notif}" />\n'))

    # previews for review (not used by the app)
    prev = Path(__file__).resolve().parent / "preview"
    prev.mkdir(exist_ok=True)
    (prev / "monochrome.svg").write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="#DDE7E6"/><circle cx="54" cy="54" r="33" fill="none" stroke="#f55" stroke-width=".3"/><path fill="#21524F" fill-rule="evenodd" d="{mono}"/></svg>')
    (prev / "notification.svg").write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24" fill="#222"/><path fill="#fff" fill-rule="evenodd" d="{notif}"/></svg>')
    print("ok")


if __name__ == "__main__":
    main()
