#!/usr/bin/env python3
"""Rendert play/icon.svg nach play/icon-512.png (Play-Store-Icon, 512 x 512, RGBA).

Kein SVG-Renderer nötig: Verlauf und Herzpfad werden mit Pillow gezeichnet –
in 4-facher Auflösung und danach verkleinert, damit die Kanten weich sind.
Geometrie und Farben kommen aus icon.svg, damit beide nicht auseinanderlaufen.
Unterstützt genau das, was icon.svg nutzt: linearer Verlauf von oben nach
unten, ein Pfad mit M/L/l/C/c/z und translate+scale.

Aufruf aus dem Repo-Root: python3 play/render_icon.py
"""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

HIER = Path(__file__).parent
GROESSE = 512
FAKTOR = 4
NS = {"s": "http://www.w3.org/2000/svg"}


def hex_rgb(farbe):
    farbe = farbe.lstrip("#")
    return tuple(int(farbe[i:i + 2], 16) for i in (0, 2, 4))


def bezier(p0, p1, p2, p3, schritte=48):
    for i in range(1, schritte + 1):
        t = i / schritte
        u = 1 - t
        yield (
            u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
        )


def pfad_zu_punkten(d):
    """Flacht einen SVG-Pfad (M, L, l, C, c, z) zu einem Polygon ab."""
    teile = re.findall(r"[MLlCcZz]|-?\d*\.?\d+", d)
    punkte, pos, befehl, i = [], (0.0, 0.0), None, 0
    while i < len(teile):
        if re.fullmatch(r"[A-Za-z]", teile[i]):
            befehl = teile[i]
            i += 1
            if befehl in "Zz":
                continue
        zahl = lambda k: float(teile[i + k])
        if befehl in "ML":
            pos = (zahl(0), zahl(1))
            punkte.append(pos)
            i += 2
        elif befehl == "l":
            pos = (pos[0] + zahl(0), pos[1] + zahl(1))
            punkte.append(pos)
            i += 2
        elif befehl in "Cc":
            k = [(zahl(j), zahl(j + 1)) for j in (0, 2, 4)]
            if befehl == "c":
                k = [(pos[0] + x, pos[1] + y) for x, y in k]
            punkte.extend(bezier(pos, *k))
            pos = k[2]
            i += 6
        else:
            raise ValueError(f"Pfadbefehl {befehl} nicht unterstützt")
    return punkte


def main():
    svg = ET.parse(HIER / "icon.svg").getroot()
    vx, vy, vb, vh = (float(v) for v in svg.get("viewBox").split())
    stopps = [(float(s.get("offset")), hex_rgb(s.get("stop-color"))) for s in svg.iterfind(".//s:stop", NS)]
    gruppe = svg.find(".//s:g", NS)
    tx, ty, sk = (float(v) for v in re.findall(r"-?\d*\.?\d+", gruppe.get("transform")))
    herz = gruppe.find("s:path", NS)

    px = GROESSE * FAKTOR
    massstab = px / vb
    bild = Image.new("RGBA", (px, px))
    zeichnen = ImageDraw.Draw(bild)

    # Verlauf: Stopps beziehen sich auf y = viewBox-Oberkante bis -Unterkante.
    for y in range(px):
        t = (y + 0.5) / px
        for (o1, f1), (o2, f2) in zip(stopps, stopps[1:]):
            if o1 <= t <= o2:
                a = (t - o1) / (o2 - o1)
                farbe = tuple(round(c1 + (c2 - c1) * a) for c1, c2 in zip(f1, f2))
                break
        zeichnen.line([(0, y), (px, y)], fill=farbe + (255,))

    polygon = [
        (((tx + x * sk) - vx) * massstab, ((ty + y * sk) - vy) * massstab)
        for x, y in pfad_zu_punkten(herz.get("d"))
    ]
    zeichnen.polygon(polygon, fill=hex_rgb(herz.get("fill")) + (255,))

    ziel = HIER / "icon-512.png"
    bild.resize((GROESSE, GROESSE), Image.LANCZOS).save(ziel, optimize=True)
    print(ziel)


if __name__ == "__main__":
    main()
