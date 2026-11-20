"""Writes the README's architecture diagram, light and dark.

usage: python3 tools/readme/architecture.py [output directory, docs/images by default]
"""
import pathlib
import sys
from html import escape

THEMES = {
    "light": dict(
        bg="#FBFAFE", border="#E4DFF0", text="#1C1B1F", muted="#5A5560", line="#6E6878", pill="#FFFFFF",
        client=("#EFE9FA", "#6750A4"), shared=("#E1F3EC", "#0B6E4F"), server=("#E6EEF9", "#1F5FA8"),
        service=("#FFFFFF", "#B9CBE6"), storage=("#FBF0DC", "#8A5A00"), external=("#F1F0F3", "#77737D"),
    ),
    "dark": dict(
        bg="#17161B", border="#2E2B36", text="#ECE7F1", muted="#B5AFBE", line="#9A94A4", pill="#17161B",
        client=("#2A2343", "#B9A2FF"), shared=("#14332A", "#6FD3AE"), server=("#162A45", "#8DB7F5"),
        service=("#1D2F4A", "#3E5F8D"), storage=("#372A12", "#EFC27D"), external=("#25242A", "#8E8996"),
    ),
}

W, H = 1200, 744
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"


def box(x, y, w, h, colors, lines, title_size=17, radius=16, dashed=False, align="middle", title_color=None):
    fill, stroke = colors
    dash = ' stroke-dasharray="6 5"' if dashed else ""
    out = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="1.5"{dash}/>']
    tx = x + w / 2 if align == "middle" else x + 20
    # Lines: the first is the title, in the box's color.
    total = title_size + (len(lines) - 1) * 20
    ty = y + (h - total) / 2 + title_size - 3
    for i, line in enumerate(lines):
        if i == 0:
            out.append(f'<text x="{tx}" y="{ty}" text-anchor="{align}" font-size="{title_size}" font-weight="700" fill="{title_color or stroke}">{escape(line)}</text>')
            ty += 23
        else:
            out.append(f'<text x="{tx}" y="{ty}" text-anchor="{align}" font-size="13.5" fill="{{muted}}">{escape(line)}</text>')
            ty += 19
    return "\n".join(out)


def arrow(x1, y1, x2, y2, label=None, label_y=None):
    out = [f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{{line}}" stroke-width="1.6" marker-end="url(#arrow)"/>']
    if label:
        width = len(label) * 6.6 + 18
        ly = label_y if label_y is not None else (y1 + y2) / 2
        lx = x1 + (x2 - x1) * ((ly - y1) / (y2 - y1) if y2 != y1 else 0.5)
        out.append(f'<rect x="{lx - width / 2:.1f}" y="{ly - 11}" width="{width:.1f}" height="22" rx="11" fill="{{pill}}" stroke="{{border}}"/>')
        out.append(f'<text x="{lx:.1f}" y="{ly + 4.5}" text-anchor="middle" font-size="12.5" fill="{{muted}}">{escape(label)}</text>')
    return "\n".join(out)


def link(x, y1, y2):
    """A client built on the shared modules: a plain connector."""
    return f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{{shared_stroke}}" stroke-width="2" stroke-linecap="round"/>'


def diagram(t):
    parts = []
    # Clients.
    clients = [
        ("Client web", "navigateur", "HTML + htmx"),
        ("Android", "Compose Multiplatform", "Material 3 Expressive"),
        ("Desktop", "Compose Multiplatform", "macOS, Windows, Linux"),
        ("iPhone · iPad · Mac", "SwiftUI", "Liquid Glass"),
        ("Terminal", "CLI Clikt", "TUI Mosaic"),
        ("Clients tiers", "AntennaPod, Kasts", "gPodder, newsboat"),
    ]
    xs = [36 + i * 192 for i in range(6)]
    for x, lines in zip(xs, clients):
        colors = t["external"] if lines[0] == "Clients tiers" else t["client"]
        parts.append(box(x, 36, 172, 100, colors, lines, title_size=16.5, dashed=lines[0] == "Clients tiers"))
    centers = [x + 86 for x in xs]

    # Shared Kotlin modules under the four apps.
    for c in centers[1:5]:
        parts.append(link(c, 136, 178))
    parts.append(box(228, 178, 748, 76, t["shared"], [
        ":shared · :player — Kotlin Multiplatform",
        "modèles, SDK qui suit les liens de l'API, textes FR/EN, lecteur audio, comptes multiserveurs",
    ], title_size=16.5))
    parts.append(f'<text x="{centers[3] + 8}" y="162" font-size="12" fill="{{muted}}">PodcastKit (SKIE)</text>')

    # Server.
    parts.append(f'<rect x="36" y="304" width="1132" height="256" rx="22" fill="{t["server"][0]}" stroke="{t["server"][1]}" stroke-width="1.5"/>')
    parts.append(f'<text x="146" y="338" font-size="18" font-weight="700" fill="{t["server"][1]}">Serveur Ktor</text>')
    parts.append(f'<text x="272" y="338" font-size="13.5" fill="{{muted}}">service multi-utilisateurs, une seule API</text>')
    parts.append(box(60, 356, 640, 74, t["service"], ["API hypermédia", "HAL-FORMS en JSON pour les apps · HTML + htmx pour le navigateur"], title_size=15.5, radius=12, title_color=t["server"][1]))
    parts.append(box(724, 356, 420, 74, t["service"], ["Interopérabilité", "API gPodder · Open Podcast API · flux RSS · OPML"], title_size=15.5, radius=12, title_color=t["server"][1]))
    services = [
        ("Comptes et droits", "Argon2id, jetons par appareil", "partages lecture ou modification"),
        ("Flux et fichiers", "lecture des flux, téléchargements", "purge, pochettes réduites"),
        ("Lecture", "positions, file d'attente", "liens audio signés"),
        ("Recherche", "Apple Podcasts, Podcast Index", "épisodes multicritères"),
    ]
    for i, lines in enumerate(services):
        parts.append(box(60 + i * 275, 448, 259, 92, t["service"], lines, title_size=15, radius=12, title_color=t["server"][1]))

    # Requests from the clients.
    parts.append(arrow(centers[0], 136, centers[0], 354, "cookie de session", 236))
    parts.append(arrow(602, 254, 602, 354, "JSON · jeton Bearer", 280))
    parts.append(arrow(centers[5], 136, centers[5], 354, "mot de passe d'application", 236))

    # Storage and the outside world.
    parts.append(box(60, 620, 300, 88, t["storage"], ["PostgreSQL", "Flyway · SQLDelight"], title_size=16))
    parts.append(box(384, 620, 300, 88, t["storage"], ["Fichiers", "audio mutualisé · pochettes"], title_size=16))
    parts.append(box(844, 620, 300, 88, t["external"], ["Internet", "flux RSS des podcasts · annuaires"], title_size=16, dashed=True))
    parts.append(arrow(210, 560, 210, 618))
    parts.append(arrow(534, 560, 534, 618))
    parts.append(arrow(994, 560, 994, 618, "garde SSRF", 589))

    body = "\n".join(parts)
    body = body.replace("{muted}", t["muted"]).replace("{line}", t["line"]).replace("{pill}", t["pill"]) \
        .replace("{border}", t["border"]).replace("{shared_stroke}", t["shared"][1])
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<title>Architecture : les clients, le SDK Kotlin partagé, le serveur Ktor et ses données</title>
<defs>
<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
<path d="M0 0 L10 5 L0 10 z" fill="{t["line"]}"/>
</marker>
</defs>
<rect x="0.75" y="0.75" width="{W - 1.5}" height="{H - 1.5}" rx="24" fill="{t["bg"]}" stroke="{t["border"]}" stroke-width="1.5"/>
{body}
</svg>
'''


out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "docs/images")
out.mkdir(parents=True, exist_ok=True)
for name, theme in THEMES.items():
    (out / f"architecture-{name}.svg").write_text(diagram(theme))
    print(out / f"architecture-{name}.svg")
