# Numen API UI textures

Source files for the few hand-drawn sprites under
`numen-api/common/src/main/resources/assets/numen_api/textures/gui/sprites/`. Most of the UI is drawn in code and
tinted by the theme at draw time; the look and the rules are in `docs/ui-design-rules.md`.

| Source | Output sprite(s) |
|---|---|
| `card.html` | `card.png` |
| `slot.html`, `slot_alt.html` | `slot.png`, `slot_alt.png` |
| `eye.html`, `eye_off.html` | `eye.png`, `eye_off.png` |
| `chevron.html` | `chevron_up.png`, `chevron_down.png` (white, tinted at draw time) |
| `pxgen.tmpl` | 9×9 pixel patterns (`heart_*`, `food_*`): fill `@PAL@` (palette) and `@PAT@` (rows) |
| `pixelarticons.py` | the `icon_*.png` set, converted from pixelarticons (MIT); credits in `licenses/ASSETS.txt` |

The `.html` sources are [HyperFrames](https://hyperframes.heygen.com) compositions that place 1px divs on an
explicit pixel grid: pixel art with no antialiasing. Render one transparently and copy it into the assets:

```bash
cd numen-api/tools/ui-textures
cp <source>.html index.html                     # HyperFrames renders index.html
npx hyperframes render --format png-sequence --fps 1 -o renders/<name>
cp renders/<name>/frame_000001.png ../../common/src/main/resources/assets/numen_api/textures/gui/sprites/<name>.png
```

Requires Node ≥ 22, FFmpeg and a headless browser (all resolved by `npx`); `npx hyperframes doctor` checks them.
