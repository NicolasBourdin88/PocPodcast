"""Builds the README's screenshot boards: the same screen on the six clients.

usage: python3 tools/readme/boards.py <captures directory> [date shown on the boards]

The directory holds, for each page (home, library): web-<page>.png, mac-<page>.png,
desktop-<page>.png, android-<page>.png, iphone-<page>.png and terminal-<page>.txt (the
terminal's text, `$ command` lines included). The boards go to docs/images/<board>.webp;
tools/readme/README.md says how to take the captures.
"""
import html
import pathlib
import subprocess
import sys

CAPTURES = pathlib.Path(sys.argv[1]).resolve()
DATE = sys.argv[2] if len(sys.argv) > 2 else "vendredi 20 novembre 2026, 9 h 41"
TOOLS = pathlib.Path(__file__).resolve().parent
OUT = TOOLS.parent.parent / "docs" / "images"

BOARDS = {
    "accueil": dict(page="home", title="Accueil", url="localhost:8080"),
    "bibliotheque": dict(page="library", title="Bibliothèque", url="localhost:8080/subscriptions"),
}

STYLE = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
html, body { margin: 0; background: transparent; }
body { font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, sans-serif; }
.canvas {
  width: 1600px; padding: 40px 48px 46px; border-radius: 28px; color: #ECE7F1;
  background:
    radial-gradient(900px 520px at 8% -10%, rgba(126, 96, 214, .38), transparent 62%),
    radial-gradient(800px 520px at 105% 110%, rgba(40, 120, 150, .30), transparent 60%),
    #121117;
}
header { display: flex; align-items: baseline; gap: 18px; margin-bottom: 30px; }
h1 { margin: 0; font-size: 38px; font-weight: 750; letter-spacing: -.5px; }
.sub { color: #B9B2C4; font-size: 19px; }
.sub b { color: #ECE7F1; font-weight: 600; }
.top { display: grid; grid-template-columns: repeat(3, 1fr); gap: 40px; align-items: start; }
.bottom { display: grid; grid-template-columns: auto auto 1fr; gap: 56px; align-items: start; margin-top: 36px; }
figure { margin: 0; display: flex; flex-direction: column; align-items: center; gap: 14px; }
figcaption { font-size: 17px; font-weight: 650; color: #ECE7F1; }
figcaption span { color: #A9A2B4; font-weight: 450; }
.window, .shot {
  width: 100%; border-radius: 12px; overflow: hidden; background: #1C1B22;
  box-shadow: 0 22px 48px rgba(0, 0, 0, .5), 0 0 0 1px rgba(255, 255, 255, .09);
}
.bar { height: 34px; display: flex; align-items: center; gap: 8px; padding: 0 13px; background: #2A2931; position: relative; }
.dot { width: 12px; height: 12px; border-radius: 50%; flex: none; }
.r { background: #FF5F57; } .y { background: #FEBC2E; } .g { background: #28C840; }
.bar .title { position: absolute; inset: 0; display: grid; place-items: center; font-size: 13px; color: #B9B2C4; }
.url {
  position: absolute; left: 50%; transform: translateX(-50%); width: 56%; height: 22px; border-radius: 7px;
  background: #1B1A20; color: #C9C3D3; font-size: 12.5px; display: grid; place-items: center;
}
.window img, .shot img { display: block; width: 100%; }
.phone {
  height: 660px; padding: 9px; border-radius: 48px; background: #0A0A0D;
  box-shadow: 0 22px 48px rgba(0, 0, 0, .55), 0 0 0 1.5px rgba(255, 255, 255, .14);
}
.phone img { display: block; height: 100%; border-radius: 39px; }
.terminal { height: 660px; display: flex; flex-direction: column; }
.terminal pre {
  flex: 1; margin: 0; padding: 14px 16px; overflow: hidden; white-space: pre-wrap; word-break: break-word;
  font: 12.4px/1.55 "SF Mono", Menlo, Consolas, monospace; color: #DCD6E3; background: #16151B;
}
.prompt { color: #8FD6B4; }
"""

DOTS = '<span class="dot r"></span><span class="dot y"></span><span class="dot g"></span>'


def terminal(page: str) -> str:
    lines = (CAPTURES / f"terminal-{page}.txt").read_text().rstrip("\n").split("\n")
    return "\n".join(
        f'<span class="prompt">$</span> {html.escape(line[2:])}' if line.startswith("$ ") else html.escape(line) for line in lines
    )


def board(spec: dict) -> str:
    page = spec["page"]
    img = lambda client: f'<img src="{(CAPTURES / f"{client}-{page}.png").as_uri()}">'
    return f"""<!doctype html><html lang="fr"><head><meta charset="utf-8"><style>{STYLE}</style></head><body>
<div class="canvas">
  <header>
    <h1>{spec["title"]}</h1>
    <div class="sub">le compte <b>Alice</b> sur le serveur de démo · six clients · {DATE}</div>
  </header>
  <div class="top">
    <figure><div class="window"><div class="bar">{DOTS}<div class="url">{spec["url"]}</div></div>{img("web")}</div>
      <figcaption>Client web <span>· navigateur, htmx</span></figcaption></figure>
    <figure><div class="shot">{img("mac")}</div>
      <figcaption>Mac <span>· SwiftUI</span></figcaption></figure>
    <figure><div class="window"><div class="bar">{DOTS}<div class="title">Podcast</div></div>{img("desktop")}</div>
      <figcaption>Desktop <span>· Compose Multiplatform</span></figcaption></figure>
  </div>
  <div class="bottom">
    <figure><div class="phone">{img("android")}</div><figcaption>Android <span>· Compose Multiplatform</span></figcaption></figure>
    <figure><div class="phone">{img("iphone")}</div><figcaption>iPhone <span>· SwiftUI</span></figcaption></figure>
    <figure style="width: 100%"><div class="window terminal"><div class="bar">{DOTS}<div class="title">Terminal</div></div><pre>{terminal(page)}</pre></div>
      <figcaption>Terminal <span>· CLI</span></figcaption></figure>
  </div>
</div>
</body></html>"""


webshot = CAPTURES / "webshot"
if not webshot.exists():
    subprocess.run(["swiftc", "-O", "-o", str(webshot), str(TOOLS / "webshot.swift")], check=True)
OUT.mkdir(parents=True, exist_ok=True)
for name, spec in BOARDS.items():
    page = CAPTURES / f"{name}.html"
    page.write_text(board(spec))
    png = CAPTURES / f"{name}.png"
    # Height 1: as tall as the board; a transparent page keeps the rounded corners.
    subprocess.run([str(webshot), page.as_uri(), str(png), "1600", "1", "dark", "1.5", "transparent"], check=True)
    subprocess.run(["cwebp", "-quiet", "-q", "84", "-alpha_q", "100", "-m", "6", "-resize", "2400", "0", str(png), "-o", str(OUT / f"{name}.webp")], check=True)
    print(OUT / f"{name}.webp")
