# Keymap Overlay - features

## Marker types (overlay: + Add)
| Marker | What it does |
|---|---|
| Tap / key | Mode **hold** (finger follows the key), **tap**, **toggle** (sprint/crouch), **double**, **long** (hold N ms), **turbo** (auto-fire, N taps/s). Size + opacity sliders. |
| Direction pad | WASD / arrows / ESDF / IJKL presets or any 4 keys. |
| Aim | Mouse look. Sensitivity X/Y, acceleration, dead zone, invert Y, ADS key + ADS sensitivity, range, hand-off reset, crosshair (style, color, size). |
| Swipe | One key press glides the finger from the marker to the purple end dot (duration slider). |
| Skill-shot | Hold key, aim with the mouse inside the ring, release to cast (MOBA). Ring size + aim sensitivity. |

Any tap/swipe marker can use the **mouse wheel** (`Roll up` / `Roll down` in the key picker).

## Editor
Undo / redo (toolbar arrows), snap-to-grid (More > Grid & snap), duplicate, templates (FPS, MOBA, racing),
sliders with a live value, -/+ buttons for single steps.

## PC app
* **Profiles tab** - save the phone layout per game, activate, rename, duplicate, import / export.
* **Wireless & Tools** - wireless ADB (connect, pair, USB to Wi-Fi), Back/Home/Recents/Volume/Power, screenshot,
  keep screen on, always on top, ADB ping in the header.

### Hotkeys while the mouse is captured
| Keys | Action |
|---|---|
| `` ` `` | switch cursor / aim mode |
| Esc | release the mouse |
| Ctrl+Alt+P | pause / resume (mouse and keyboard free) |
| Ctrl+Alt+H | show / hide overlay buttons |
| Ctrl+Alt+1..9 | switch profile (order shown in the Profiles tab) |
| Ctrl+Alt+S | screenshot |
| Ctrl+Alt+Backspace / Home / End | Back / Home / Recents |
| Ctrl+Alt+Up / Down | volume up / down |

## keymap.json (version 2, additions)
* `keyMapNodes[]`: `KMT_CLICK` (+`mode`, `turboHz`, `longMs`, `size`), `KMT_SWIPE` (`pos`, `endPos`, `durationMs`),
  `KMT_SKILL` (`pos`, `radius` as fraction of screen width, `skillSens`), `KMT_STEER_WHEEL`.
* `mouseMoveMap`: `invertY`, `deadZone`, `accel`, `adsKey`, `adsMult`, `crosshair{style,size,color}`.
* root: `grid{on,size}`. Old files still load.
