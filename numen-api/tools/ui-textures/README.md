# Numen API UI textures

Source files for the sprites under
`numen-api/common/src/main/resources/assets/numen_api/textures/gui/sprites/`. Most of the UI is drawn in code and
tinted by the theme at draw time; the look and the rules are in `docs/ui-design-rules.md`.

| Source | Output sprite(s) |
|---|---|
| `chevron.html` | `chevron_up.png`, `chevron_down.png` (white, tinted at draw time) |
| `pixelarticons.py` | the `icon_*.png` set, converted from pixelarticons (MIT); credits in `licenses/ASSETS.txt` |

`chevron.html` is a [HyperFrames](https://hyperframes.heygen.com) composition that places 1px divs on an
explicit pixel grid: pixel art with no antialiasing. Render one transparently and copy it into the assets:

```bash
cd numen-api/tools/ui-textures
cp <source>.html index.html                     # HyperFrames renders index.html
npx hyperframes render --format png-sequence --fps 1 -o renders/<name>
cp renders/<name>/frame_000001.png ../../common/src/main/resources/assets/numen_api/textures/gui/sprites/<name>.png
```

Requires Node ≥ 22, FFmpeg and a headless browser (all resolved by `npx`); `npx hyperframes doctor` checks them.
