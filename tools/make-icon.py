#!/usr/bin/env python3
"""ii tools launcher icon: one geometry, written as the adaptive icon's vector layers,
SVGs and PNGs, so the app and the artwork never drift apart.

The back of a phone rising from the icon's bottom edge, cropped by the mask. The ii
mark's four points are its Pixel-style cameras (the asterisk and two lenses in the
visor, a flash on the band), and the shell's ring, with end4's arrow loose in its gap,
sits where a phone's own logo would.

Usage: tools/make-icon.py   (needs rsvg-convert and Pillow)
"""
import math
import os
import re
import subprocess
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'app/src/main/res')
ART = os.path.join(ROOT, '.github/assets')

# Shell palette (assets/icons/ii-p3drovfx.png)
BG = '#201332'
BODY = ('#6c40ae', '#8a60c8')
LENS = ('#8459c2', '#a684e0')
BAND = '#a27fda'
EMBLEM = '#5f379f'

# end4's mark, in its own 1635 viewBox (centre 817.5)
RING = ("m 817.5,1446 c 0,22.09 17.925,40.12 39.977,38.8 128.601,-7.7 252.553,-52.43 356.713,-129.22 114.91,-84.72 199.68,-203.99 241.9,-340.37 42.23,-136.376 39.68,-282.688 -7.26,-417.514 C 1401.89,462.871 1313.02,346.614 1195.23,265.947 1077.44,185.279 936.924,144.439 794.247,149.405 651.57,154.37 514.23,204.882 402.335,293.543 290.441,382.205 209.868,504.359 172.415,642.122 c -33.947,124.867 -30.854,256.612 8.447,379.298 6.739,21.04 30.033,31.24 50.63,23.25 L 392.68,982.187 c 20.597,-7.985 30.621,-31.123 24.652,-52.393 -20.233,-72.099 -20.675,-148.592 -0.899,-221.331 23.285,-85.651 73.38,-161.598 142.948,-216.721 69.568,-55.124 154.956,-86.528 243.662,-89.615 88.706,-3.088 176.071,22.304 249.307,72.457 73.23,50.153 128.48,122.433 157.67,206.258 29.18,83.825 30.76,174.791 4.51,259.58 -26.25,84.788 -78.95,158.948 -150.4,211.618 -60.67,44.73 -132.152,71.97 -206.69,79.16 -21.99,2.12 -39.94,19.83 -39.94,41.92 z")
ASTERISK = ("m 783.094,1083 c -5.523,0 -10,-4.48 -10,-10 V 901.072 c 0,-3.084 -3.343,-5.008 -6.009,-3.458 l -148.816,86.453 c -4.761,2.766 -10.862,1.163 -13.648,-3.586 l -34.539,-58.865 c -2.803,-4.778 -1.188,-10.924 3.601,-13.707 l 149.428,-86.823 c 2.655,-1.542 2.654,-5.376 -10e-4,-6.918 L 574.155,727.702 c -4.778,-2.773 -6.401,-8.896 -3.626,-13.673 l 34.107,-58.69 c 2.774,-4.774 8.894,-6.396 13.669,-3.622 l 148.78,86.433 c 2.666,1.549 6.009,-0.375 6.009,-3.459 V 562 c 0,-5.523 4.477,-10 10,-10 h 67.804 c 5.523,0 10,4.477 10,10 v 172.691 c 0,3.084 3.343,5.008 6.01,3.459 l 148.782,-86.433 c 4.77,-2.774 10.89,-1.152 13.67,3.622 l 34.1,58.684 c 2.78,4.779 1.15,10.905 -3.63,13.677 l -149.187,86.467 c -2.658,1.541 -2.659,5.379 -10e-4,6.921 l 149.668,86.823 c 4.79,2.781 6.41,8.931 3.6,13.711 l -34.54,58.86 c -2.78,4.749 -8.89,6.351 -13.65,3.586 L 866.908,897.614 c -2.667,-1.55 -6.01,0.374 -6.01,3.458 V 1073 c 0,5.52 -4.477,10 -10,10 z")
E4C, E4_RING_R, E4_TRACK, E4_GAP = 817.5, 668, 541, 125  # centre, outer radius, track radius, gap angle


# --- path flattening: every shape ends up in absolute 108 dp coordinates ---------------

class Affine:
    """Uniform scale, rotation and translation (all an icon needs; keeps arcs exact)."""
    def __init__(self, s=1.0, deg=0.0, tx=0.0, ty=0.0):
        self.s, self.r, self.tx, self.ty = s, math.radians(deg), tx, ty

    def pt(self, x, y):
        c, n = math.cos(self.r) * self.s, math.sin(self.r) * self.s
        return c * x - n * y + self.tx, n * x + c * y + self.ty


def centred(cx, cy, s, ox=E4C, oy=E4C):
    """Maps (ox, oy) to (cx, cy), scaled by s."""
    return Affine(s, 0, cx - s * ox, cy - s * oy)


NUM = re.compile(r'[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
ARGS = dict(M=2, L=2, H=1, V=1, C=6, S=4, Q=4, T=2, A=7, Z=0)


def f(v):
    s = f'{v:.3f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def flatten(d, t=Affine()):
    toks = NUM.findall(d)
    out, i, cmd = [], 0, None
    x = y = sx = sy = 0.0
    P = lambda a, b: ','.join(f(v) for v in t.pt(a, b))
    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]; i += 1
        U, rel = cmd.upper(), cmd.islower()
        if U == 'Z':
            out.append('Z'); x, y = sx, sy
            continue
        a = [float(v) for v in toks[i:i + ARGS[U]]]; i += ARGS[U]
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if U == 'M':
            x, y = a[0] + ox, a[1] + oy; sx, sy = x, y
            out.append('M' + P(x, y)); cmd = 'l' if rel else 'L'
        elif U == 'L':
            x, y = a[0] + ox, a[1] + oy; out.append('L' + P(x, y))
        elif U == 'H':
            x = a[0] + (x if rel else 0); out.append('L' + P(x, y))
        elif U == 'V':
            y = a[0] + (y if rel else 0); out.append('L' + P(x, y))
        elif U in 'CSQT':
            pts = [(a[k] + ox, a[k + 1] + oy) for k in range(0, len(a), 2)]
            out.append(U + ' '.join(P(*p) for p in pts)); x, y = pts[-1]
        elif U == 'A':
            x, y = a[5] + ox, a[6] + oy
            out.append(f'A{f(a[0] * t.s)},{f(a[1] * t.s)} {f(a[2] + math.degrees(t.r))} '
                       f'{int(a[3])} {int(a[4])} {P(x, y)}')
    return ''.join(out)


# --- shapes -----------------------------------------------------------------------------

def pill(x0, y0, x1, y1):
    r = (y1 - y0) / 2
    return (f'M{f(x0 + r)},{f(y0)}H{f(x1 - r)}A{f(r)},{f(r)} 0 0 1 {f(x1 - r)},{f(y1)}'
            f'H{f(x0 + r)}A{f(r)},{f(r)} 0 0 1 {f(x0 + r)},{f(y0)}Z')


def circle(cx, cy, r):
    return (f'M{f(cx - r)},{f(cy)}A{f(r)},{f(r)} 0 1 0 {f(cx + r)},{f(cy)}'
            f'A{f(r)},{f(r)} 0 1 0 {f(cx - r)},{f(cy)}Z')


def rounded_poly(V, radii):
    cuts = []
    for i, v in enumerate(V):
        p, q = V[i - 1], V[(i + 1) % len(V)]
        a, b = (p[0] - v[0], p[1] - v[1]), (q[0] - v[0], q[1] - v[1])
        la, lb = math.hypot(*a), math.hypot(*b)
        d = radii[i] / math.tan(math.acos((a[0] * b[0] + a[1] * b[1]) / (la * lb)) / 2)
        cuts.append(((v[0] + a[0] * d / la, v[1] + a[1] * d / la),
                     (v[0] + b[0] * d / lb, v[1] + b[1] * d / lb)))
    s = f'M{f(cuts[0][1][0])},{f(cuts[0][1][1])}'
    for i in list(range(1, len(V))) + [0]:
        (x0, y0), (x1, y1) = cuts[i]
        s += f'L{f(x0)},{f(y0)}A{radii[i]},{radii[i]} 0 0 1 {f(x1)},{f(y1)}'
    return s + 'Z'


def arrow():
    """end4's arrow, loose in the ring's gap, on its track, pointing at the centre."""
    W, L, tip_r, base_r, inset = 360, 440, 50, 80, 30
    g = math.radians(E4_GAP)
    cx, cy = E4C + (E4_TRACK - inset) * math.cos(g), E4C + (E4_TRACK - inset) * math.sin(g)
    rot = math.radians(E4_GAP + 180 + 90)
    V = [(cx + x * math.cos(rot) - y * math.sin(rot), cy + x * math.sin(rot) + y * math.cos(rot))
         for x, y in ((0, -2 * L / 3), (W / 2, L / 3), (-W / 2, L / 3))]
    return rounded_poly(V, [tip_r, base_r, base_r])


# Geometry in the 108 dp adaptive canvas (72 dp visible, 66 dp safe zone)
X0, X1, TOP, CORNER, BOTTOM = 27, 81, 29, 13, 112
DOT = 2.7
emblem = centred(54, 76.5, 10 / E4_RING_R)
ast_h = 10
SHAPES = {
    'body':   f'M{X0},{BOTTOM}V{TOP + CORNER}A{CORNER},{CORNER} 0 0 1 {X0 + CORNER},{TOP}'
              f'H{X1 - CORNER}A{CORNER},{CORNER} 0 0 1 {X1},{TOP + CORNER}V{BOTTOM}Z',
    'band':   pill(31, 35, 77, 53),
    'visor':  pill(33.2, 37.2, 66, 50.8),
    'asterisk': flatten(ASTERISK, centred(40.5, 44, ast_h / 531)),
    'lens1':  circle(50.8, 44, DOT),
    'lens2':  circle(58.6, 44, DOT),
    'flash':  circle(71.2, 44, DOT),
    'ring':   flatten(RING, emblem),
    'arrow':  flatten(arrow(), emblem),
}
# Gradients in absolute coordinates, so the vector and the SVG match exactly
AST_BOX = (40.5 - ast_h * 494 / 531 / 2, 44 + ast_h / 2, 40.5 + ast_h * 494 / 531 / 2, 44 - ast_h / 2)
LAYERS = [  # name, fill (colour or (stops, x1, y1, x2, y2))
    ('body', (BODY, X0, BOTTOM, X1, TOP)),
    ('band', BAND),
    ('visor', BG),
    ('asterisk', (LENS, *AST_BOX)),
    ('lens1', (LENS, 50.8 - DOT, 44 + DOT, 50.8 + DOT, 44 - DOT)),
    ('lens2', (LENS, 58.6 - DOT, 44 + DOT, 58.6 + DOT, 44 - DOT)),
    ('flash', BG),
    ('ring', EMBLEM),
    ('arrow', EMBLEM),
]
# Monochrome: one even-odd path, so the visor, band and emblem are real holes
# (body > band > visor > lenses nest; the flash sits in the band, the emblem in the body).
MONO = ''.join(SHAPES[k] for k in ('body', 'band', 'visor', 'asterisk', 'lens1', 'lens2',
                                   'flash', 'ring', 'arrow'))


# --- writers ----------------------------------------------------------------------------

def argb(c):
    return '#FF' + c.lstrip('#').upper()


def vector_foreground():
    paths = []
    for name, fill in LAYERS:
        d = SHAPES[name]
        if isinstance(fill, str):
            paths.append(f'    <path\n        android:fillColor="{argb(fill)}"\n'
                         f'        android:pathData="{d}" />')
        else:
            (c0, c1), x1, y1, x2, y2 = fill
            paths.append(
                f'    <path android:pathData="{d}">\n'
                f'        <aapt:attr name="android:fillColor">\n'
                f'            <gradient android:type="linear" android:startX="{f(x1)}" '
                f'android:startY="{f(y1)}" android:endX="{f(x2)}" android:endY="{f(y2)}">\n'
                f'                <item android:offset="0" android:color="{argb(c0)}" />\n'
                f'                <item android:offset="1" android:color="{argb(c1)}" />\n'
                f'            </gradient>\n        </aapt:attr>\n    </path>')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by tools/make-icon.py: edit the script, not this file. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            + '\n'.join(paths) + '\n</vector>\n')


def vector_monochrome():
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by tools/make-icon.py: edit the script, not this file. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            '    <path\n        android:fillColor="#FF000000"\n        android:fillType="evenOdd"\n'
            f'        android:pathData="{MONO}" />\n</vector>\n')


def svg(background=True, mono=None):
    defs, body = [], []
    if mono:
        body.append(f'<path fill="{mono}" fill-rule="evenodd" d="{MONO}"/>')
    else:
        for i, (name, fill) in enumerate(LAYERS):
            if isinstance(fill, str):
                body.append(f'<path fill="{fill}" d="{SHAPES[name]}"/>')
            else:
                (c0, c1), x1, y1, x2, y2 = fill
                defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" '
                            f'x1="{f(x1)}" y1="{f(y1)}" x2="{f(x2)}" y2="{f(y2)}">'
                            f'<stop offset="0" stop-color="{c0}"/><stop offset="1" stop-color="{c1}"/>'
                            f'</linearGradient>')
                body.append(f'<path fill="url(#g{i})" d="{SHAPES[name]}"/>')
    bg = f'<rect width="108" height="108" fill="{BG}"/>' if background else ''
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">'
            f'<defs>{"".join(defs)}</defs>{bg}{"".join(body)}</svg>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as fh:
        fh.write(text)


def render(svg_path, px):
    out = svg_path[:-4] + f'-{px}.png'
    subprocess.run(['rsvg-convert', '-w', str(px), '-h', str(px), svg_path, '-o', out], check=True)
    return out


def visible(png, px, shape=None):
    """The 72 dp a launcher shows, optionally masked (circle or squircle)."""
    im = Image.open(png).convert('RGBA')
    k = im.width / 108
    im = im.crop((round(18 * k), round(18 * k), round(90 * k), round(90 * k))).resize((px, px), Image.LANCZOS)
    if shape:
        big = px * 4
        m = Image.new('L', (big, big), 0)
        d = ImageDraw.Draw(m)
        if shape == 'circle':
            d.ellipse((0, 0, big - 1, big - 1), fill=255)
        else:
            d.rounded_rectangle((0, 0, big - 1, big - 1), radius=round(big * 0.3), fill=255)
        im.putalpha(Image.composite(im.getchannel('A'), Image.new('L', (px, px), 0),
                                    m.resize((px, px), Image.LANCZOS)))
    return im


def main():
    write(os.path.join(RES, 'drawable/ic_launcher_foreground.xml'), vector_foreground())
    write(os.path.join(RES, 'drawable/ic_launcher_monochrome.xml'), vector_monochrome())

    full, fg, mono = (os.path.join(ART, n) for n in
                      ('ii-tools.svg', 'ii-tools-foreground.svg', 'ii-tools-monochrome.svg'))
    write(full, svg())
    write(fg, svg(background=False))
    write(mono, svg(background=False, mono='#000000'))

    src = render(full, 1296)          # 12x the canvas, cropped and downsampled below
    for px in (1024, 512):            # 512: Play Store (square, the store rounds it)
        visible(src, px).save(os.path.join(ART, f'ii-tools-{px}.png'))
    visible(src, 512, 'circle').save(os.path.join(ART, 'ii-tools-circle-512.png'))
    visible(src, 512, 'squircle').save(os.path.join(ART, 'ii-tools-squircle-512.png'))
    os.remove(src)
    m = render(mono, 1296)
    visible(m, 512).save(os.path.join(ART, 'ii-tools-monochrome-512.png'))
    os.remove(m)
    render(fg, 432)                    # the full adaptive layer at xxxhdpi (108 dp x 4)


if __name__ == '__main__':
    main()
