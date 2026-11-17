# Builds Worm icon v2 assets from the user's compound path (design/worm-icon-v2-source.svg).
import json, sys, os
sub = json.load(open(sys.argv[1])); res = sys.argv[2]; design = sys.argv[3]
groups = {
    'headphones': [15],
    'waves': [13, 14],
    'worm': [3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
    'book': [0, 1, 2],
}
d = {k: ''.join(sub[i]['d'] for i in v) for k, v in groups.items()}
MINT, VIOLET, PINK, PLUM, INK = '#98E8DE', '#8E1EA2', '#D552A3', '#542177', '#2A0E3A'
# (x1,y1,x2,y2, start, center, end)
grads = {
    'headphones': (300, 0, 300, 220, MINT, '#9F8FD0', VIOLET),
    'waves': (400, 160, 500, 100, VIOLET, '#B07FCB', MINT),
    'worm': (230, 70, 360, 450, PINK, VIOLET, PINK),
    'book': (300, 340, 300, 580, VIOLET, '#9A7FCB', MINT),
}
order = ['book', 'headphones', 'waves', 'worm']

def svg(bg=True, outline=False):
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 600" width="1024" height="1024"><defs>']
    out.append('<radialGradient id="bg" cx="300" cy="260" r="420" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#3A1650"/><stop offset="1" stop-color="#160A21"/></radialGradient>')
    for k, (x1, y1, x2, y2, a, b, c) in grads.items():
        out.append(f'<linearGradient id="g{k}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{a}"/><stop offset=".5" stop-color="{b}"/><stop offset="1" stop-color="{c}"/></linearGradient>')
    out.append('</defs>')
    if bg: out.append('<rect width="600" height="600" fill="url(#bg)"/>')
    for k in order:
        st = f' stroke="{INK}" stroke-width="8" stroke-linejoin="round" paint-order="stroke"' if outline else ''
        out.append(f'<path d="{d[k]}" fill="url(#g{k})"{st}/>')
    out.append('</svg>')
    return '\n'.join(out)

open(os.path.join(design, 'worm-icon.svg'), 'w').write(svg(bg=True))
open(os.path.join(design, 'worm-icon-transparent.svg'), 'w').write(svg(bg=False, outline=True))

def vd(scale, colored=True, outline=False, keys=order, viewport=600, translate=(0, 0)):
    tx, ty = translate
    L = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Generated from design/worm-icon.svg (user design) by mkicon2.py -->',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
         f'    android:width="108dp" android:height="108dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">',
         f'    <group android:pivotX="300" android:pivotY="300" android:scaleX="{scale}" android:scaleY="{scale}" android:translateX="{tx}" android:translateY="{ty}">']
    for k in keys:
        if outline:
            L.append(f'        <path android:pathData="{d[k]}" android:strokeColor="#FF2A0E3A" android:strokeWidth="14" android:strokeLineJoin="round" />')
    for k in keys:
        if colored:
            x1, y1, x2, y2, a, b, c = grads[k]
            L.append(f'        <path android:pathData="{d[k]}">')
            L.append('            <aapt:attr name="android:fillColor">')
            L.append(f'                <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}" android:startColor="#FF{a[1:]}" android:centerColor="#FF{b[1:]}" android:endColor="#FF{c[1:]}" />')
            L.append('            </aapt:attr>')
            L.append('        </path>')
        else:
            L.append(f'        <path android:pathData="{d[k]}" android:fillColor="#FFFFFFFF" />')
    L += ['    </group>', '</vector>']
    return '\n'.join(L) + '\n'

dr = os.path.join(res, 'drawable')
# Adaptive foreground: art centred and shrunk into the 66dp safe circle.
open(os.path.join(dr, 'ic_launcher_foreground.xml'), 'w').write(vd(0.56, translate=(1, 8)))
open(os.path.join(dr, 'ic_launcher_monochrome.xml'), 'w').write(vd(0.56, colored=False, translate=(1, 8)))
# In-app logo: full size, no outline (contrast on light themes under review).
open(os.path.join(dr, 'worm_logo.xml'), 'w').write(vd(0.96, outline=False, translate=(0, 6)))
# Status bar: worm + headphones only, white.
open(os.path.join(dr, 'ic_stat_worm.xml'), 'w').write(vd(1.05, colored=False, keys=['headphones', 'worm'], translate=(40, 80)))
open(os.path.join(dr, 'ic_launcher_background.xml'), 'w').write('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="47"
                android:gradientRadius="76"
                android:startColor="#FF3A1650" android:centerColor="#FF24102F"
                android:endColor="#FF160A21" />
        </aapt:attr>
    </path>
</vector>
''')
print('ok')
