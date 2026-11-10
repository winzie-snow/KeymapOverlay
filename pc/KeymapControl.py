"""Keymap Control - Windows control panel for the Keymap Overlay APK, using ADB."""
import ctypes, json, math, os, queue, re, shutil, socket, struct, subprocess, sys, threading, time, urllib.request, zipfile
import tkinter as tk
from collections import deque
from types import SimpleNamespace
from tkinter import filedialog, messagebox, simpledialog, ttk
from tkinter.scrolledtext import ScrolledText

PKG = "com.winzie.keymapoverlay"
SVC = f"{PKG}/.OverlayService"
REMOTE_DIR = f"/sdcard/Android/data/{PKG}/files"
REMOTE_JSON = f"{REMOTE_DIR}/keymap.json"
PT_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
BASE = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
CFG = os.path.join(BASE, "keymapcontrol.json")
NOWIN = 0x08000000 if os.name == "nt" else 0
MODE_TEXT = {
    "cursor": "CURSOR MODE - cursor visible on the phone\nPress {k} to switch to aim  |  Esc = release",
    "game": "AIM MODE - mouse moves the camera, keys and buttons are active\nPress {k} to bring back the cursor  |  Esc = release",
    "pause": "PAUSED - your mouse and keyboard are free\nCtrl+Alt+P to resume  |  Esc = release",
}
MODE_BG = {"cursor": "#0d47a1", "game": "#1b5e20", "pause": "#6d4c41"}
AIM_KEY = "Key_QuoteLeft"   # the ` key on the PC always toggles cursor <-> aim mode
JOY_SLIDE = 0.07            # seconds for the direction finger to slide from the centre to the rim
AIM_GAP = 0.03              # seconds the camera finger stays lifted when it hits the range edge
AIM_SETTLE = 0.012          # seconds after touch-down before the camera finger starts moving
AIM_HANDOFF = True          # endless smooth turning: at the range edge a 2nd finger lands at the aim point BEFORE the
                            # 1st lifts (no gap, no jump). Set False to go back to lift / wait / re-press.
AIM_OVERLAP = 0.006         # seconds both camera fingers are down during a handoff
JOY_STEP = 0.006           # minimum seconds between two direction move events
SERVER_VER = "5.0.1"
SERVER_URL = f"https://github.com/Genymobile/scrcpy/releases/download/v{SERVER_VER}/scrcpy-server-v{SERVER_VER}"
SERVER_JAR = os.path.join(BASE, "scrcpy-server.jar")
BUNDLED_JAR = os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "scrcpy-server.jar")


def get_server_jar(say):
    """Bundled copy first, then one next to the exe, then a download with retries."""
    for p in (BUNDLED_JAR, SERVER_JAR):
        if os.path.isfile(p) and os.path.getsize(p) > 100000:
            return p
    for attempt in range(1, 4):
        try:
            say(f"Downloading scrcpy server v{SERVER_VER} (try {attempt}/3)...")
            req = urllib.request.Request(SERVER_URL, headers={"User-Agent": "KeymapControl"})
            with urllib.request.urlopen(req, timeout=30) as r, open(SERVER_JAR, "wb") as f:
                shutil.copyfileobj(r, f)
            return SERVER_JAR
        except Exception as e:
            say(f"Download failed: {e}")
            time.sleep(2)
    say(f"Download scrcpy-server-v{SERVER_VER} manually from {SERVER_URL}")
    say("and save it as scrcpy-server.jar next to this program.")
    return None
SERVER_REMOTE = "/data/local/tmp/scrcpy-server.jar"
if os.name == "nt":
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def load_cfg():
    try:
        with open(CFG) as f:
            return json.load(f)
    except Exception:
        return {}


def save_cfg(c):
    try:
        with open(CFG, "w") as f:
            json.dump(c, f)
    except Exception:
        pass


def find_adb(cfg):
    exe = "adb.exe" if os.name == "nt" else "adb"
    for p in (cfg.get("adb"), os.path.join(BASE, "platform-tools", exe), shutil.which("adb")):
        if p and os.path.isfile(p):
            return p
    return None


def parse_devices(text):
    """Parse `adb devices -l` output into [(serial, state, model)]."""
    out = []
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2 or line.startswith("*"):
            continue
        model = next((p.split(":", 1)[1] for p in parts[2:] if p.startswith("model:")), "")
        out.append((parts[0], parts[1], model))
    return out


def aim_from_json(mm):
    """mouseMoveMap dict from keymap.json -> aim settings dict (or None when there is no aim marker)."""
    if not mm or "range" not in mm:
        return None
    sr = mm.get("speedRatio", {})
    ads = mm.get("adsKey") or ""
    return {"start": (mm["startPos"]["x"], mm["startPos"]["y"]),
            "speed": (sr.get("x", 2.0), sr.get("y", 2.0)),
            "range": (mm["range"]["w"], mm["range"]["h"]),
            "reset": bool(mm.get("reset", True)),
            "invert": bool(mm.get("invertY", False)),
            "dz": int(mm.get("deadZone", 0)),
            "accel": int(mm.get("accel", 0)),
            "ads_vk": vk_for(ads) if ads else None,
            "ads_mult": float(mm.get("adsMult", 60)) / 100.0}


class TouchLink:
    """Sends touch events to the phone through the scrcpy server (control channel only, no video)."""

    def __init__(self, app):
        self.app = app
        self.sock = None
        self.proc = None
        self.port = None
        self.alive = False

    def start(self, serial):
        a = self.app
        jar = get_server_jar(a.say)
        if not jar:
            return False
        base = [a.adb, "-s", serial]
        run = lambda args: subprocess.run(base + args, capture_output=True, text=True, timeout=30, creationflags=NOWIN)
        r = run(["push", jar, SERVER_REMOTE])
        if r.returncode != 0:
            a.say("Push server failed: " + (r.stdout + r.stderr).strip())
            return False
        scid = int.from_bytes(os.urandom(3), "big") | 0x100000
        r = run(["forward", "tcp:0", f"localabstract:scrcpy_{scid:08x}"])
        try:
            self.port = int(r.stdout.strip().splitlines()[0])
        except Exception:
            a.say("adb forward failed: " + (r.stdout + r.stderr).strip())
            return False
        args = ["shell", f"CLASSPATH={SERVER_REMOTE}", "app_process", "/", "com.genymobile.scrcpy.Server",
                SERVER_VER, f"scid={scid:08x}", "log_level=warn", "video=false", "audio=false", "control=true",
                "tunnel_forward=true", "send_device_meta=false", "send_dummy_byte=true", "cleanup=false",
                "clipboard_autosync=false"]
        self.proc = subprocess.Popen(base + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, creationflags=NOWIN)
        threading.Thread(target=self._read_proc, daemon=True).start()
        for _ in range(40):
            try:
                s = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                s.settimeout(1.5)
                if s.recv(1):
                    s.settimeout(None)
                    self.sock = s
                    self.alive = True
                    a.say("Touch link ready")
                    return True
                s.close()
            except Exception:
                pass
            if self.proc.poll() is not None:
                break
            time.sleep(0.25)
        a.say("Could not connect to the scrcpy server on the phone")
        self.stop()
        return False

    def _read_proc(self):
        try:
            for line in self.proc.stdout:
                if line.strip():
                    self.app.say("[server] " + line.strip())
        except Exception:
            pass
        self.alive = False

    def send(self, action, pid, x, y, w, h, pressure=0xFFFF):
        """action: 0 down, 1 up, 2 move. x, y are pixels on the phone screen."""
        if not self.alive:
            return
        x = max(0, min(int(x), w - 1))
        y = max(0, min(int(y), h - 1))
        try:
            self.sock.sendall(struct.pack(">BBqiiHHHii", 2, action, pid, x, y, w, h, pressure, 0, 0))
        except Exception:
            self.alive = False

    def stop(self):
        self.alive = False
        for fn in (lambda: self.sock.close(), lambda: self.proc.kill()):
            try:
                fn()
            except Exception:
                pass
        if self.port and self.app.adb:
            try:
                subprocess.run([self.app.adb, "forward", "--remove", f"tcp:{self.port}"],
                               capture_output=True, timeout=5, creationflags=NOWIN)
            except Exception:
                pass
        self.sock = self.proc = self.port = None


class JoySlide:
    """Direction-pad finger that SLIDES between the centre and the rim instead of jumping.

    Keys only change the target; the finger glides towards it. It is lifted only after it has glided
    back to the centre, so W -> D (which passes through "no key" for a few ms) never lifts the finger.
    update() returns the touch events to send as (action, x, y): 0 down, 2 move, 1 up."""

    def __init__(self, cx, cy, rx, ry, slide=JOY_SLIDE, step=JOY_STEP):
        self.cx, self.cy, self.rx, self.ry = cx, cy, rx, ry
        self.slide, self.min_step = slide, step
        self.on = False
        self.fx = self.fy = 0.0      # finger offset in units of the radius (-1..1)
        self.last = 0.0

    @property
    def pos(self):
        return self.cx + self.fx * self.rx, self.cy + self.fy * self.ry

    def update(self, vx, vy, now):
        ev = []
        if vx or vy:
            n = math.hypot(vx, vy)
            tx, ty = vx / n, vy / n
            if not self.on:
                self.on = True
                self.fx = self.fy = 0.0
                self.last = now
                ev.append((0,) + self.pos)
        else:
            tx = ty = 0.0
        if not self.on:
            return ev
        dt = now - self.last
        if dt < self.min_step:
            return ev
        self.last = now
        step = dt / self.slide
        dx, dy = tx - self.fx, ty - self.fy
        d = math.hypot(dx, dy)
        if d > 1e-6:
            if d <= step:
                self.fx, self.fy = tx, ty
            else:
                self.fx += dx / d * step
                self.fy += dy / d * step
            ev.append((2,) + self.pos)
        elif not (vx or vy):         # settled at the centre with no key held -> lift
            self.on = False
            ev.append((1,) + self.pos)
        return ev

    def release(self):
        """Lift immediately (leaving game mode). Returns the up event or None."""
        if not self.on:
            return None
        self.on = False
        p = self.pos
        self.fx = self.fy = 0.0
        return (1,) + p


class OverlayLink:
    """Talks to the overlay app on the phone to draw the simulated mouse cursor."""

    def __init__(self, app):
        self.app = app
        self.sock = None
        self.port = None
        self.alive = False

    def start(self, serial):
        a = self.app
        base = [a.adb, "-s", serial]
        try:
            # make sure the overlay is running and locked (markers must not eat injected touches)
            subprocess.run(base + ["shell", "am", "start-foreground-service", "-n", SVC, "-a", "LOCK"],
                           capture_output=True, timeout=15, creationflags=NOWIN)
            r = subprocess.run(base + ["forward", "tcp:0", "localabstract:keymap_overlay_cursor"],
                               capture_output=True, text=True, timeout=15, creationflags=NOWIN)
            self.port = int(r.stdout.strip().splitlines()[0])
        except Exception as e:
            a.say(f"Overlay link failed: {e}")
            return False
        for _ in range(14):
            try:
                s = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                s.settimeout(1.0)
                if b"OK" in s.recv(8):
                    s.settimeout(None)
                    self.sock = s
                    self.alive = True
                    return True
                s.close()
            except Exception:
                pass
            time.sleep(0.25)
        return False

    def send(self, text):
        if not self.alive:
            return
        try:
            self.sock.sendall((text + "\n").encode())
        except Exception:
            self.alive = False

    def stop(self):
        self.alive = False
        try:
            self.sock.close()
        except Exception:
            pass
        if self.port and self.app.adb:
            try:
                subprocess.run([self.app.adb, "forward", "--remove", f"tcp:{self.port}"],
                               capture_output=True, timeout=5, creationflags=NOWIN)
            except Exception:
                pass
        self.sock = self.port = None


if os.name == "nt":
    class POINT(ctypes.Structure):
        _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long), ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


MOUSE_VK = {"Button_Left": 1, "Button_Right": 2, "Button_Middle": 4, "Button_Back": 5, "Button_Forward": 6}
SPECIAL_VK = {"Space": 0x20, "Shift": 0x10, "Control": 0x11, "Alt": 0x12, "Tab": 0x09, "Return": 0x0D,
              "Up": 0x26, "Down": 0x28, "Left": 0x25, "Right": 0x27, "QuoteLeft": 0xC0}
LABELS = {"Button_Left": "LMB", "Button_Right": "RMB", "Button_Middle": "MMB", "Button_Back": "M4",
          "Button_Forward": "M5", "Key_QuoteLeft": "`", "Key_Control": "Ctrl", "Key_Return": "Enter",
          "Key_Escape": "Esc"}


def vk_for(key):
    """Windows virtual-key code for a keymap key name (None if unsupported; Esc is reserved for release)."""
    if key in MOUSE_VK:
        return MOUSE_VK[key]
    if not key or not key.startswith("Key_"):
        return None
    n = key[4:]
    if len(n) == 1 and n.isalnum():
        return ord(n.upper())
    if n in SPECIAL_VK:
        return SPECIAL_VK[n]
    if n[0] == "F" and n[1:].isdigit() and 1 <= int(n[1:]) <= 12:
        return 0x70 + int(n[1:]) - 1
    return None


def key_label(key):
    return LABELS.get(key) or (key or "").replace("Key_", "").replace("Button_", "")


def parse_wm_size(text):
    """Return (w, h) in natural orientation from `wm size`, preferring the override size."""
    found = dict((k.lower(), (int(w), int(h))) for k, w, h in re.findall(r"(\w+) size:\s*(\d+)x(\d+)", text))
    return found.get("override") or found.get("physical")


# ---------- keymap markers: what each one does while you play ----------
TAP_TIME = 0.04             # seconds a quick tap keeps the finger down
SWIPE_STEP = 0.008          # seconds between two move events of a swipe
WHEEL_KEYS = {"Wheel_Up": 1, "Wheel_Down": -1}
CLICK_MODES = ("hold", "tap", "toggle", "double", "long", "turbo")


class Bind:
    """One marker bound to a key / mouse button / wheel direction. `out(action, x, y)` sends one touch event
    (0 down, 1 up, 2 move) for this marker's own finger.

    kind "click": mode hold (finger follows the key), tap, toggle, double, long (press-and-hold for N ms), turbo
    kind "swipe": the finger glides from the marker to its end point when the key is pressed
    kind "skill": press = finger down at the marker, mouse movement drags it (clamped to the radius), release = cast"""

    def __init__(self, kind, key, x, y, pid, mode="hold", hz=12.0, long_ms=600.0,
                 ex=None, ey=None, dur=0.2, radius=0.0, skill_sens=1.0):
        self.kind, self.key, self.x, self.y, self.pid = kind, key, x, y, pid
        self.mode = mode if mode in CLICK_MODES else "hold"
        self.hz = max(1.0, float(hz))
        self.long_s = max(0.05, float(long_ms) / 1000.0)
        self.ex, self.ey, self.dur = ex, ey, max(0.03, float(dur))
        self.radius, self.skill_sens = float(radius), float(skill_sens)
        self.vk = vk_for(key)
        self.wheel = WHEEL_KEYS.get(key)
        self.out = lambda act, x, y: None
        self.lx, self.ly = x, y
        self.down = False       # key state seen last tick
        self.sent = False       # a finger of this marker is on the screen
        self.on = False         # toggle state
        self.held = False       # turbo: key is held
        self.todo = []          # [(time, action)]: 0 finger down, 1 finger up, 3 key released
        self.next_turbo = 0.0
        self.sw_t0 = None
        self.sw_last = 0.0
        self.skill = False
        self.ox = self.oy = 0.0
        self.sk_last = 0.0

    # --- low level ---
    def _emit(self, act, x, y):
        self.lx, self.ly = x, y
        self.out(act, x, y)

    def _dn(self):
        self._emit(0, self.x, self.y)
        self.sent = True

    def _up(self):
        if self.sent:
            self._emit(1, self.lx, self.ly)
            self.sent = False

    def _at(self, now, delay, act):
        self.todo.append((now + delay, act))
        self.todo.sort(key=lambda t: t[0])

    # --- key edges ---
    def press(self, now, from_wheel=False):
        if self.kind == "swipe":
            if self.sw_t0 is None:
                self._dn()
                self.sw_t0 = self.sw_last = now
            return
        if self.kind == "skill":
            self.skill = True
            self.ox = self.oy = 0.0
            self._dn()
            return
        mode = self.mode
        if from_wheel and mode in ("hold", "turbo", "toggle"):
            mode = "tap"
        if mode == "hold":
            self._dn()
        elif mode == "tap":
            self._dn()
            self._at(now, TAP_TIME, 1)
        elif mode == "double":
            self._dn()
            self._at(now, TAP_TIME, 1)
            self._at(now, TAP_TIME + 0.05, 0)
            self._at(now, 2 * TAP_TIME + 0.05, 1)
        elif mode == "long":
            self._dn()
            self._at(now, self.long_s, 1)
        elif mode == "turbo":
            self.held = True
            self.next_turbo = now
        elif mode == "toggle":
            self.on = not self.on
            if self.on:
                self._dn()
            else:
                self._up()

    def release(self, now):
        if self.kind == "skill":
            if self.skill:
                self._up_at_offset()
            self.skill = False
            return
        if self.kind == "swipe":
            return
        if self.mode == "hold":
            self._up()
        elif self.mode == "turbo":
            self.held = False

    def _up_at_offset(self):
        if self.sent:
            self._emit(1, self.x + self.ox, self.y + self.oy)
            self.sent = False

    def trigger(self, now):
        """Mouse-wheel notch: behaves like a quick key press."""
        self.press(now, from_wheel=True)
        if self.kind == "click" and self.mode in ("hold", "turbo", "toggle"):
            self._at(now, 0.05, 3)

    # --- per tick ---
    def tick(self, st, now, mdx=0.0, mdy=0.0):
        if self.wheel is None:
            if st and not self.down:
                self.press(now)
            elif not st and self.down:
                self.release(now)
            self.down = st
        while self.todo and self.todo[0][0] <= now:
            _, act = self.todo.pop(0)
            if act == 0:
                self._dn()
            elif act == 1:
                self._up()
            else:
                self.release(now)
        if self.held and now >= self.next_turbo:
            self._dn()
            self._at(now, min(0.03, 0.4 / self.hz), 1)
            self.next_turbo = now + 1.0 / self.hz
        if self.sw_t0 is not None and now - self.sw_last >= SWIPE_STEP:
            f = min(1.0, (now - self.sw_t0) / self.dur)
            self.sw_last = now
            if f >= 1.0:
                self._emit(1, self.ex, self.ey)
                self.sent = False
                self.sw_t0 = None
            else:
                self._emit(2, self.x + (self.ex - self.x) * f, self.y + (self.ey - self.y) * f)
        if self.skill and (mdx or mdy):
            self.ox += mdx * self.skill_sens
            self.oy += mdy * self.skill_sens
            d = math.hypot(self.ox, self.oy)
            if d > self.radius > 0:
                self.ox *= self.radius / d
                self.oy *= self.radius / d
            if now - self.sk_last >= 0.006:
                self._emit(2, self.x + self.ox, self.y + self.oy)
                self.sk_last = now

    def release_all(self):
        """Lift whatever is down and forget all pending actions (leaving game mode / switching profile)."""
        if self.sent:
            if self.skill:
                self._up_at_offset()
            else:
                self._up()
        self.sent = False
        self.todo = []
        self.held = self.on = self.skill = False
        self.sw_t0 = None
        self.ox = self.oy = 0.0

    def reset_key(self, state):
        self.down = state

    @property
    def grabs_mouse(self):
        return self.skill


def build_runtime(data, w, h, sens, fb_ax, fb_ay, aim_vk):
    """keymap.json dict -> everything the capture loop needs: binds, direction pad, camera settings."""
    data = data or {}
    binds, wheel = [], None
    for i, n in enumerate(data.get("keyMapNodes", [])):
        t, pid = n.get("type"), 20 + i
        try:
            if t == "KMT_STEER_WHEEL":
                wheel = {"center": (n["centerPos"]["x"], n["centerPos"]["y"]),
                         "keys": [n["upKey"], n["leftKey"], n["downKey"], n["rightKey"]],
                         "off": (n.get("rightOffset", 0.07), n.get("downOffset", 0.12))}
                continue
            px, py = n["pos"]["x"] * w, n["pos"]["y"] * h
            if t == "KMT_CLICK":
                b = Bind("click", n["key"], px, py, pid, n.get("mode", "hold"), n.get("turboHz", 12), n.get("longMs", 600))
            elif t == "KMT_SWIPE":
                b = Bind("swipe", n["key"], px, py, pid, ex=n["endPos"]["x"] * w, ey=n["endPos"]["y"] * h,
                         dur=n.get("durationMs", 200) / 1000.0)
            elif t == "KMT_SKILL":
                b = Bind("skill", n["key"], px, py, pid, radius=n.get("radius", 0.07) * w,
                         skill_sens=n.get("skillSens", 100) / 100.0)
            else:
                continue
        except (KeyError, TypeError, ValueError):
            continue
        if b.kind == "skill" and b.wheel is not None:
            continue
        if b.wheel is None and (b.vk is None or b.vk == aim_vk):
            continue
        binds.append(b)
    joy, wvk = None, []
    if wheel:
        wvk = [vk_for(k) for k in wheel["keys"]]
        joy = JoySlide(wheel["center"][0] * w, wheel["center"][1] * h, wheel["off"][0] * w, wheel["off"][1] * h)
    aim = aim_from_json(data.get("mouseMoveMap") or {})
    cam = SimpleNamespace(invert=False, dz=0, accel=0, ads_vk=None, ads_mult=1.0)
    if aim:
        cam.ax, cam.ay = aim["start"]
        cam.sx = aim["speed"][0] * sens / 2.0
        cam.sy = aim["speed"][1] * sens / 2.0
        cam.xmin, cam.xmax = max(0.0, cam.ax - aim["range"][0] / 2), min(1.0, cam.ax + aim["range"][0] / 2)
        cam.ymin, cam.ymax = max(0.0, cam.ay - aim["range"][1] / 2), min(1.0, cam.ay + aim["range"][1] / 2)
        cam.reset = aim["reset"]
        cam.invert, cam.dz, cam.accel = aim["invert"], aim["dz"], aim["accel"]
        cam.ads_vk = aim["ads_vk"] if aim["ads_vk"] != aim_vk else None
        cam.ads_mult = aim["ads_mult"]
    else:
        cam.ax, cam.ay = fb_ax, fb_ay
        cam.sx = cam.sy = sens
        cam.xmin, cam.xmax, cam.ymin, cam.ymax, cam.reset = 0.05, 0.95, 0.05, 0.95, True
    return {"binds": binds, "wheel_binds": [b for b in binds if b.wheel is not None], "key_binds": [b for b in binds if b.wheel is None],
            "joy": joy, "wvk": wvk, "cam": cam, "aim": aim, "has_wheel": bool(wheel)}


# ---------- profiles (stored next to the exe in /profiles) ----------
PROFILE_DIR = os.path.join(BASE, "profiles")
SHOT_DIR = os.path.join(BASE, "screenshots")
HOTKEY_HELP = ("While the mouse is captured:\n"
               "  `  switch cursor / aim        Esc  release the mouse\n"
               "  Ctrl+Alt+P  pause / resume   Ctrl+Alt+H  show / hide overlay buttons\n"
               "  Ctrl+Alt+1..9  switch profile   Ctrl+Alt+S  screenshot\n"
               "  Ctrl+Alt+Backspace  Back   +Home  Home   +End  Recents   +Up / Down  volume")


def list_profiles():
    try:
        return sorted(os.path.splitext(f)[0] for f in os.listdir(PROFILE_DIR) if f.lower().endswith(".json"))
    except OSError:
        return []


def profile_path(name):
    return os.path.join(PROFILE_DIR, name + ".json")


def clean_name(name):
    return re.sub(r'[\\/:*?"<>|]+', "_", (name or "").strip())[:60]


# ---------- look & feel ----------
BG, PANEL, BORDER = "#12161c", "#1b212a", "#2c3542"
FG, MUTED, ACCENT, ACCENT_HI = "#e8edf3", "#8b98a8", "#29b6f6", "#4fc3f7"
GOOD, BAD = "#43a047", "#e53935"


def apply_theme(root):
    root.configure(bg=BG)
    st = ttk.Style(root)
    st.theme_use("clam")
    st.configure(".", background=BG, foreground=FG, fieldbackground=PANEL, bordercolor=BORDER,
                 lightcolor=BG, darkcolor=BG, troughcolor=PANEL, focuscolor=BG, font=("Segoe UI", 10))
    st.configure("TLabel", background=BG, foreground=FG)
    st.configure("Muted.TLabel", foreground=MUTED, font=("Segoe UI", 9))
    st.configure("Title.TLabel", font=("Segoe UI", 15, "bold"))
    st.configure("Status.TLabel", font=("Segoe UI", 10, "bold"))
    st.configure("TLabelframe", background=BG, bordercolor=BORDER, relief="solid", borderwidth=1)
    st.configure("TLabelframe.Label", background=BG, foreground=ACCENT, font=("Segoe UI", 10, "bold"))
    st.configure("TButton", background=PANEL, foreground=FG, bordercolor=BORDER, padding=(10, 6), relief="flat")
    st.map("TButton", background=[("disabled", BG), ("pressed", BORDER), ("active", "#252d38")],
           foreground=[("disabled", "#4b5563")], bordercolor=[("active", ACCENT)])
    st.configure("Accent.TButton", background=ACCENT, foreground="#06232f", bordercolor=ACCENT,
                 font=("Segoe UI", 10, "bold"), padding=(10, 8))
    st.map("Accent.TButton", background=[("disabled", BG), ("pressed", "#0288d1"), ("active", ACCENT_HI)],
           foreground=[("disabled", "#4b5563")], bordercolor=[("disabled", BORDER)])
    st.configure("Danger.TButton", background="#3a1c1f", foreground="#ff8a80", bordercolor="#5a2a2e")
    st.map("Danger.TButton", background=[("disabled", BG), ("active", BAD)],
           foreground=[("disabled", "#4b5563"), ("active", "white")])
    st.configure("Step.TButton", padding=(0, 0), width=2, font=("Segoe UI", 11, "bold"))
    st.configure("TEntry", fieldbackground=PANEL, foreground=FG, insertcolor=FG, bordercolor=BORDER, padding=4)
    st.configure("TSpinbox", fieldbackground=PANEL, foreground=ACCENT, insertcolor=FG, bordercolor=BORDER,
                 arrowcolor=MUTED, background=PANEL, padding=3)
    st.configure("TCombobox", fieldbackground=PANEL, foreground=FG, background=PANEL, arrowcolor=MUTED,
                 bordercolor=BORDER, selectbackground=PANEL, selectforeground=FG, padding=4)
    st.map("TCombobox", fieldbackground=[("readonly", PANEL)], foreground=[("readonly", FG)])
    root.option_add("*TCombobox*Listbox.background", PANEL)
    root.option_add("*TCombobox*Listbox.foreground", FG)
    root.option_add("*TCombobox*Listbox.selectBackground", ACCENT)
    root.option_add("*TCombobox*Listbox.selectForeground", "#06232f")
    st.configure("TCheckbutton", background=BG, foreground=FG, indicatorbackground=PANEL,
                 indicatorforeground=ACCENT, indicatorcolor=PANEL)
    st.map("TCheckbutton", indicatorcolor=[("selected", ACCENT)], background=[("active", BG)])
    st.configure("Horizontal.TScale", background=BG, troughcolor=BORDER, bordercolor=BG, lightcolor=ACCENT,
                 darkcolor=ACCENT)
    st.map("Horizontal.TScale", background=[("active", ACCENT_HI)])
    st.configure("TNotebook", background=BG, bordercolor=BORDER, tabmargins=(0, 0, 0, 0))
    st.configure("TNotebook.Tab", background=PANEL, foreground=MUTED, padding=(14, 7), bordercolor=BORDER)
    st.map("TNotebook.Tab", background=[("selected", BG), ("active", "#252d38")],
           foreground=[("selected", ACCENT), ("active", FG)])
    st.configure("Vertical.TScrollbar", background=PANEL, troughcolor=BG, bordercolor=BG, arrowcolor=MUTED)


class SliderField(ttk.Frame):
    """[-] ---slider--- [+] [ value ]  - the number is editable, so you can drag, click +/- or just type."""

    def __init__(self, parent, var, lo, hi, step, fmt="{:.1f}", length=170, on_change=None):
        super().__init__(parent)
        self.var, self.lo, self.hi, self.step, self.fmt, self.on_change = var, lo, hi, step, fmt, on_change
        self.txt = tk.StringVar(value=fmt.format(var.get()))
        ttk.Button(self, text="\u2212", style="Step.TButton", command=lambda: self.nudge(-1)).pack(side="left")
        self.scale = ttk.Scale(self, from_=lo, to=hi, variable=var, length=length, command=self._drag)
        self.scale.pack(side="left", padx=6)
        ttk.Button(self, text="+", style="Step.TButton", command=lambda: self.nudge(1)).pack(side="left")
        self.box = ttk.Spinbox(self, from_=lo, to=hi, increment=step, textvariable=self.txt, width=6,
                               justify="center", command=self._typed)
        self.box.pack(side="left", padx=(8, 0))
        self.box.bind("<Return>", lambda e: self._typed())
        self.box.bind("<FocusOut>", lambda e: self._typed())

    def _set(self, v):
        v = min(self.hi, max(self.lo, round(round(v / self.step) * self.step, 4)))
        self.var.set(v)
        self.txt.set(self.fmt.format(v))
        if self.on_change:
            self.on_change(v)

    def _drag(self, raw):
        self._set(float(raw))

    def _typed(self):
        try:
            self._set(float(self.txt.get().replace(",", ".")))
        except ValueError:
            self.txt.set(self.fmt.format(self.var.get()))

    def nudge(self, d):
        self._set(self.var.get() + d * self.step)


class App:
    def __init__(self, root):
        self.root = root
        self.cfg = load_cfg()
        self.adb = find_adb(self.cfg)
        self.q = queue.Queue()
        self.devices = []
        self.running = True
        root.title("Keymap Control")
        root.geometry("660x820")
        root.minsize(600, 700)
        apply_theme(root)
        self.wheel_q = deque(maxlen=64)
        root.bind_all("<MouseWheel>", self.on_wheel)
        root.attributes("-topmost", bool(self.cfg.get("top", False)))
        self.build_ui()
        threading.Thread(target=self.poll_loop, daemon=True).start()
        self.root.after(100, self.pump)
        root.protocol("WM_DELETE_WINDOW", self.close)

    # ---------- UI ----------
    def build_ui(self):
        head = ttk.Frame(self.root, padding=(14, 12, 14, 4))
        head.pack(fill="x")
        ttk.Label(head, text="Keymap Control", style="Title.TLabel").pack(side="left")
        ttk.Label(head, text="mouse + keyboard for your phone", style="Muted.TLabel").pack(side="left", padx=10, pady=(6, 0))
        self.ping = ttk.Label(head, text="", style="Muted.TLabel")
        self.ping.pack(side="right", pady=(6, 0))

        top = ttk.Frame(self.root, padding=(14, 6))
        top.pack(fill="x")
        self.dot = tk.Canvas(top, width=22, height=22, highlightthickness=0, bg=BG)
        self.dot.pack(side="left")
        self.led = self.dot.create_oval(3, 3, 19, 19, fill=BAD, outline="")
        self.status = ttk.Label(top, text="Starting...", style="Status.TLabel")
        self.status.pack(side="left", padx=8)
        self.combo = ttk.Combobox(top, state="readonly", width=24)
        self.combo.pack(side="right")

        adbrow = ttk.Frame(self.root, padding=(14, 0))
        adbrow.pack(fill="x")
        self.adb_label = ttk.Label(adbrow, text=self.adb_text(), style="Muted.TLabel")
        self.adb_label.pack(side="left")
        ttk.Button(adbrow, text="Download ADB", command=lambda: self.bg(self.download_adb)).pack(side="right")
        ttk.Button(adbrow, text="Browse...", command=self.browse_adb).pack(side="right", padx=4)

        self.btns = []
        self.link = None
        self.capturing = False
        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=14, pady=(8, 0))
        t1, t2, t3 = (ttk.Frame(nb, padding=(0, 8)) for _ in range(3))
        nb.add(t1, text="  Control  ")
        nb.add(t2, text="  Profiles  ")
        nb.add(t3, text="  Wireless & Tools  ")
        self.build_control(t1)
        self.build_profiles(t2)
        self.build_tools(t3)

        self.log = ScrolledText(self.root, height=7, state="disabled", font=("Consolas", 9), bg=PANEL, fg=MUTED,
                                relief="flat", borderwidth=0, highlightthickness=1, highlightbackground=BORDER,
                                highlightcolor=BORDER, insertbackground=FG)
        self.log.pack(fill="x", padx=14, pady=(8, 14))
        self.set_enabled(False)
        self.refresh_profiles()

    def build_control(self, tab):
        g1 = ttk.LabelFrame(tab, text=" App ", padding=10)
        g1.pack(fill="x", pady=(0, 6))
        self.add_btn(g1, "Install APK...", self.install_apk, 0, 0)
        self.add_btn(g1, "Grant overlay permission", self.grant, 0, 1)
        self.add_btn(g1, "Open app", lambda: self.shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"), 0, 2)

        g2 = ttk.LabelFrame(tab, text=" Overlay ", padding=10)
        g2.pack(fill="x", pady=6)
        self.add_btn(g2, "\u25b6 Start", lambda: self.svc(None), 0, 0, "Accent.TButton")
        self.add_btn(g2, "Reload", lambda: self.svc("RELOAD"), 0, 1)
        self.add_btn(g2, "Lock", lambda: self.svc("LOCK"), 0, 2)
        self.add_btn(g2, "Edit", lambda: self.svc("EDIT"), 0, 3)
        self.add_btn(g2, "Stop", lambda: self.svc("STOP"), 0, 4, "Danger.TButton")

        g3 = ttk.LabelFrame(tab, text=" Keymap file ", padding=10)
        g3.pack(fill="x", pady=6)
        self.add_btn(g3, "Push keymap.json...", self.push, 0, 0)
        self.add_btn(g3, "Pull keymap.json...", self.pull, 0, 1)

        self.quick = ttk.Button(tab, text="\u26a1  Quick start: grant + push last keymap + show overlay",
                                style="Accent.TButton", command=lambda: self.bg(self.quick_start))
        self.quick.pack(fill="x", pady=6)
        self.btns.append(self.quick)

        g4 = ttk.LabelFrame(tab, text=" Mouse (crosshair on phone) ", padding=10)
        g4.pack(fill="x", pady=6)
        row = ttk.Frame(g4)
        row.pack(fill="x")
        ttk.Label(row, text="Sensitivity").pack(side="left")
        self.sens = tk.DoubleVar(value=self.cfg.get("sens", 2.0))
        SliderField(row, self.sens, 0.3, 6.0, 0.1).pack(side="left", padx=10)
        self.land = tk.BooleanVar(value=True)
        ttk.Checkbutton(row, text="Landscape", variable=self.land).pack(side="right")
        row2 = ttk.Frame(g4)
        row2.pack(fill="x", pady=(8, 0))
        ttk.Label(row2, text="Fallback cam X, Y").pack(side="left")
        self.ax = tk.StringVar(value=self.cfg.get("ax", "0.75"))
        self.ay = tk.StringVar(value=self.cfg.get("ay", "0.5"))
        ttk.Entry(row2, textvariable=self.ax, width=6, justify="center").pack(side="left", padx=(10, 4))
        ttk.Entry(row2, textvariable=self.ay, width=6, justify="center").pack(side="left", padx=4)
        self.area = tk.Label(g4, text="CLICK HERE to capture the mouse\n(starts in cursor mode)", bg="#263238",
                             fg="white", height=5, cursor="hand2", relief="flat", font=("Segoe UI", 11, "bold"))
        self.area.pack(fill="x", pady=(10, 0))
        self.area.bind("<Button-1>", lambda e: self.start_capture())

    def build_profiles(self, tab):
        ttk.Label(tab, text="Save layouts per game. While the mouse is captured, Ctrl+Alt+1..9 switches profile "
                            "(in the order shown).", style="Muted.TLabel", wraplength=560).pack(anchor="w", pady=(0, 6))
        box = ttk.Frame(tab)
        box.pack(fill="both", expand=True)
        self.prof_names = []
        self.prof_list = tk.Listbox(box, height=8, bg=PANEL, fg=FG, selectbackground=ACCENT, selectforeground="#06232f",
                                    relief="flat", highlightthickness=1, highlightbackground=BORDER,
                                    highlightcolor=ACCENT, activestyle="none", font=("Segoe UI", 11), exportselection=False)
        self.prof_list.pack(side="left", fill="both", expand=True)
        col = ttk.Frame(box)
        col.pack(side="left", fill="y", padx=(10, 0))
        def pb(text, cmd, style="TButton"):
            ttk.Button(col, text=text, style=style, command=cmd).pack(fill="x", pady=2)
        pb("Activate on phone", self.prof_activate, "Accent.TButton")
        pb("Save phone layout as...", self.prof_save_phone)
        pb("Duplicate", self.prof_duplicate)
        pb("Rename", self.prof_rename)
        pb("Import...", self.prof_import)
        pb("Export...", self.prof_export)
        pb("Open folder", lambda: self.open_path(PROFILE_DIR))
        pb("Delete", self.prof_delete, "Danger.TButton")

    def build_tools(self, tab):
        g = ttk.LabelFrame(tab, text=" Wireless ADB ", padding=10)
        g.pack(fill="x", pady=(0, 6))
        r1 = ttk.Frame(g)
        r1.pack(fill="x")
        ttk.Label(r1, text="IP:port", width=9).pack(side="left")
        self.wip = tk.StringVar(value=self.cfg.get("wip", ""))
        ttk.Entry(r1, textvariable=self.wip, width=22).pack(side="left", padx=4)
        ttk.Button(r1, text="Connect", command=self.wifi_connect).pack(side="left", padx=2)
        ttk.Button(r1, text="Disconnect", command=self.wifi_disconnect).pack(side="left", padx=2)
        r2 = ttk.Frame(g)
        r2.pack(fill="x", pady=(6, 0))
        ttk.Label(r2, text="Pair", width=9).pack(side="left")
        self.wpair = tk.StringVar()
        self.wcode = tk.StringVar()
        ttk.Entry(r2, textvariable=self.wpair, width=22).pack(side="left", padx=4)
        ttk.Entry(r2, textvariable=self.wcode, width=8).pack(side="left", padx=2)
        ttk.Button(r2, text="Pair", command=self.wifi_pair).pack(side="left", padx=2)
        ttk.Label(g, text="Phone: Developer options > Wireless debugging. Pair uses the pairing IP:port and 6-digit code. "
                          "Or plug in USB once and press the button below.", style="Muted.TLabel",
                  wraplength=560).pack(anchor="w", pady=(6, 4))
        self.add_btn(g, "USB \u2192 Wi-Fi (switch this phone to wireless)", self.wifi_from_usb, None, None, pack=True)

        g2 = ttk.LabelFrame(tab, text=" Phone buttons & tools ", padding=10)
        g2.pack(fill="x", pady=6)
        for i, (name, code) in enumerate((("Back", 4), ("Home", 3), ("Recents", 187), ("Vol +", 24), ("Vol -", 25), ("Power", 26))):
            self.add_btn(g2, name, lambda c=code: self.keyevent(c), 0, i)
        self.add_btn(g2, "Screenshot", self.screenshot, 1, 0, "Accent.TButton")
        self.add_btn(g2, "Open screenshots", lambda: self.open_path(SHOT_DIR), 1, 1)
        self.stay = tk.BooleanVar(value=False)
        ttk.Checkbutton(g2, text="Keep screen on while charging", variable=self.stay,
                        command=lambda: self.bg(lambda v=self.stay.get(): self.set_stay(v))).grid(row=1, column=2, columnspan=3, sticky="w", padx=8)

        g3 = ttk.LabelFrame(tab, text=" Window & hotkeys ", padding=10)
        g3.pack(fill="x", pady=6)
        self.top_var = tk.BooleanVar(value=bool(self.cfg.get("top", False)))
        ttk.Checkbutton(g3, text="Always on top", variable=self.top_var, command=self.toggle_top).pack(anchor="w")
        ttk.Label(g3, text=HOTKEY_HELP, style="Muted.TLabel", justify="left").pack(anchor="w", pady=(6, 0))

    def add_btn(self, parent, text, cmd, r, c, style="TButton", pack=False):
        b = ttk.Button(parent, text=text, style=style, command=lambda: self.bg(cmd))
        if pack:
            b.pack(fill="x", pady=2)
        else:
            b.grid(row=r, column=c, padx=3, pady=2, sticky="ew")
            parent.columnconfigure(c, weight=1)
        self.btns.append(b)

    def adb_text(self):
        return f"ADB: {self.adb}" if self.adb else "ADB: not found - click Download ADB or Browse"

    def set_enabled(self, on):
        for b in self.btns:
            b.state(["!disabled"] if on else ["disabled"])

    def say(self, msg):
        self.q.put(("log", msg))

    def pump(self):
        try:
            while True:
                kind, val = self.q.get_nowait()
                if kind == "log":
                    self.log.config(state="normal")
                    self.log.insert("end", val + "\n")
                    self.log.see("end")
                    self.log.config(state="disabled")
                elif kind == "devices":
                    self.apply_devices(val)
                elif kind == "adb":
                    self.adb_label.config(text=self.adb_text())
                elif kind == "capon":
                    self.area.config(text=MODE_TEXT["cursor"].format(k=val), bg=MODE_BG["cursor"], cursor="none")
                elif kind == "capmode":
                    self.area.config(text=MODE_TEXT[val[0]].format(k=val[1]), bg=MODE_BG[val[0]])
                elif kind == "capend":
                    self.area.config(text="CLICK HERE to capture the mouse\n(starts in cursor mode)",
                                     bg="#263238", cursor="hand2")
                elif kind == "ping":
                    self.ping.config(text="" if val is None else f"ADB ping {val} ms")
                elif kind == "profiles":
                    self.refresh_profiles()
                elif kind == "msg":
                    messagebox.showinfo("Keymap Control", val)
        except queue.Empty:
            pass
        if self.running:
            self.root.after(100, self.pump)

    def apply_devices(self, devs):
        self.devices = devs
        ready = [d for d in devs if d[1] == "device"]
        if not self.adb:
            self.led_set(BAD, "ADB not found")
        elif ready:
            d = ready[0]
            self.led_set(GOOD, f"Connected: {d[2] or d[0]}")
        elif devs:
            st = devs[0][1]
            hint = {"unauthorized": "Accept the USB debugging prompt on the phone", "offline": "Replug the cable"}.get(st, st)
            self.led_set("#f9a825", f"{st}: {hint}")
        else:
            self.led_set(BAD, "No device connected")
        names = [f"{d[2] or d[0]} ({d[0]})" for d in ready]
        self.combo["values"] = names
        if names and self.combo.get() not in names:
            self.combo.current(0)
        if not names:
            self.combo.set("")
        self.set_enabled(bool(ready))

    def led_set(self, color, text):
        self.dot.itemconfig(self.led, fill=color)
        self.status.config(text=text)

    # ---------- ADB ----------
    def run_adb(self, args, timeout=30, serial=True):
        if not self.adb:
            return -1, "ADB not found"
        cmd = [self.adb]
        if serial:
            s = self.serial()
            if s:
                cmd += ["-s", s]
        try:
            p = subprocess.run(cmd + args, capture_output=True, text=True, timeout=timeout, creationflags=NOWIN)
            return p.returncode, (p.stdout + p.stderr).strip()
        except Exception as e:
            return -1, str(e)

    def serial(self):
        sel = self.combo.get()
        if "(" in sel:
            return sel.rsplit("(", 1)[1].rstrip(")")
        return None

    def poll_loop(self):
        started = False
        while self.running:
            if self.adb:
                if not started:
                    self.run_adb(["start-server"], serial=False)
                    started = True
                rc, out = self.run_adb(["devices", "-l"], timeout=8, serial=False)
                devs = parse_devices(out) if rc == 0 else []
                self.q.put(("devices", devs))
                ready = [d for d in devs if d[1] == "device"]
                ms = None
                if ready and not self.capturing:
                    try:
                        t0 = time.perf_counter()
                        subprocess.run([self.adb, "-s", ready[0][0], "shell", "echo", "1"], capture_output=True,
                                       timeout=5, creationflags=NOWIN)
                        ms = int((time.perf_counter() - t0) * 1000)
                    except Exception:
                        ms = None
                if not self.capturing:
                    self.q.put(("ping", ms))
            else:
                self.q.put(("devices", []))
                self.adb = find_adb(self.cfg)
                if self.adb:
                    self.q.put(("adb", None))
            time.sleep(2)

    def bg(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def adb_log(self, args, **kw):
        self.say("> adb " + " ".join(args))
        rc, out = self.run_adb(args, **kw)
        if out:
            self.say(out)
        return rc, out

    def shell(self, cmd):
        return self.adb_log(["shell"] + cmd.split())

    # ---------- actions ----------
    def install_apk(self):
        p = filedialog.askopenfilename(title="Select APK", filetypes=[("APK", "*.apk")],
                                       initialdir=self.cfg.get("apk_dir"))
        if p:
            self.cfg["apk_dir"] = os.path.dirname(p)
            save_cfg(self.cfg)
            self.adb_log(["install", "-r", p], timeout=120)

    def grant(self):
        self.shell(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")

    def svc(self, action):
        args = ["shell", "am", "start-foreground-service", "-n", SVC]
        if action:
            args += ["-a", action]
        self.adb_log(args)

    def push(self, path=None):
        path = path or filedialog.askopenfilename(title="Select keymap.json", filetypes=[("JSON", "*.json")],
                                                  initialdir=self.cfg.get("json_dir"))
        if not path:
            return False
        self.cfg["json_dir"] = os.path.dirname(path)
        self.cfg["last_keymap"] = path
        save_cfg(self.cfg)
        self.adb_log(["shell", "mkdir", "-p", REMOTE_DIR])
        rc, _ = self.adb_log(["push", path, REMOTE_JSON])
        if rc == 0:
            self.svc("RELOAD")
        return rc == 0

    def pull(self):
        p = filedialog.asksaveasfilename(title="Save keymap.json as", defaultextension=".json",
                                         initialfile="keymap.json", initialdir=self.cfg.get("json_dir"))
        if p:
            self.adb_log(["pull", REMOTE_JSON, p])

    def quick_start(self):
        self.grant()
        last = self.cfg.get("last_keymap")
        rc, _ = self.run_adb(["shell", "ls", REMOTE_JSON])
        if rc == 0:
            # the phone already has a keymap (saved from the overlay): never overwrite it with the old PC copy,
            # that silently reverted sensitivity / range / marker edits
            self.say("Keeping the keymap that is already on the phone")
        elif last and os.path.isfile(last):
            self.push(last)
        self.svc(None)

    def browse_adb(self):
        p = filedialog.askopenfilename(title="Select adb.exe", filetypes=[("adb", "adb.exe"), ("All", "*.*")])
        if p:
            self.cfg["adb"] = p
            save_cfg(self.cfg)
            self.adb = p
            self.q.put(("adb", None))

    def download_adb(self):
        try:
            self.say("Downloading platform-tools from Google...")
            zp = os.path.join(BASE, "platform-tools.zip")
            urllib.request.urlretrieve(PT_URL, zp)
            with zipfile.ZipFile(zp) as z:
                z.extractall(BASE)
            os.remove(zp)
            self.adb = find_adb(self.cfg)
            self.q.put(("adb", None))
            self.say("ADB ready: " + str(self.adb))
        except Exception as e:
            self.say(f"Download failed: {e}")

    # ---------- profiles ----------
    def refresh_profiles(self):
        self.prof_names = list_profiles()
        self.prof_list.delete(0, "end")
        for i, n in enumerate(self.prof_names):
            self.prof_list.insert("end", f"{i + 1}.  {n}" if i < 9 else f"      {n}")

    def prof_selected(self):
        sel = self.prof_list.curselection()
        if not sel or sel[0] >= len(self.prof_names):
            self.say("Select a profile first")
            return None
        return self.prof_names[sel[0]]

    def prof_save_phone(self):
        if not self.serial():
            self.say("Connect a phone first")
            return
        name = clean_name(simpledialog.askstring("Save profile", "Profile name (e.g. the game):", parent=self.root))
        if not name:
            return
        if os.path.exists(profile_path(name)) and not messagebox.askyesno("Overwrite", f"'{name}' exists. Replace it?"):
            return

        def work():
            os.makedirs(PROFILE_DIR, exist_ok=True)
            rc, _ = self.adb_log(["pull", REMOTE_JSON, profile_path(name)])
            self.say(f"Saved profile '{name}'" if rc == 0 else "Pull failed - save the layout in the overlay first")
            self.q.put(("profiles", None))
        self.bg(work)

    def prof_activate(self):
        n = self.prof_selected()
        if n:
            self.bg(lambda: self.push(profile_path(n)))

    def prof_duplicate(self):
        n = self.prof_selected()
        if n:
            shutil.copyfile(profile_path(n), profile_path(clean_name(n + " copy")))
            self.refresh_profiles()

    def prof_rename(self):
        n = self.prof_selected()
        if not n:
            return
        new = clean_name(simpledialog.askstring("Rename profile", "New name:", initialvalue=n, parent=self.root))
        if new and new != n:
            os.replace(profile_path(n), profile_path(new))
            self.refresh_profiles()

    def prof_delete(self):
        n = self.prof_selected()
        if n and messagebox.askyesno("Delete profile", f"Delete '{n}'?"):
            os.remove(profile_path(n))
            self.refresh_profiles()

    def prof_import(self):
        for path in filedialog.askopenfilenames(title="Import keymap profile(s)", filetypes=[("JSON", "*.json")]):
            os.makedirs(PROFILE_DIR, exist_ok=True)
            shutil.copyfile(path, profile_path(clean_name(os.path.splitext(os.path.basename(path))[0])))
        self.refresh_profiles()

    def prof_export(self):
        n = self.prof_selected()
        if n:
            dst = filedialog.asksaveasfilename(title="Export profile", defaultextension=".json", initialfile=n + ".json")
            if dst:
                shutil.copyfile(profile_path(n), dst)

    def push_profile_quick(self, serial, name):
        """Push a saved profile to the phone without logging noise. Returns the parsed keymap or None."""
        path = profile_path(name)
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            base = [self.adb, "-s", serial]
            subprocess.run(base + ["shell", "mkdir", "-p", REMOTE_DIR], capture_output=True, timeout=15, creationflags=NOWIN)
            r = subprocess.run(base + ["push", path, REMOTE_JSON], capture_output=True, text=True, timeout=30,
                               creationflags=NOWIN)
            if r.returncode != 0:
                self.say("Push failed: " + (r.stdout + r.stderr).strip())
                return None
            self.cfg["last_keymap"] = path
            save_cfg(self.cfg)
            return data
        except Exception as e:
            self.say(f"Could not load profile '{name}': {e}")
            return None

    def open_path(self, path):
        try:
            os.makedirs(path, exist_ok=True)
            if os.name == "nt":
                os.startfile(path)
            else:
                subprocess.Popen(["xdg-open", path])
        except Exception as e:
            self.say(f"Could not open {path}: {e}")

    # ---------- wireless adb + tools ----------
    def wifi_connect(self):
        ip = self.wip.get().strip()
        if not ip:
            self.say("Type the phone's IP:port first")
            return
        self.cfg["wip"] = ip
        save_cfg(self.cfg)
        self.bg(lambda: self.adb_log(["connect", ip if ":" in ip else ip + ":5555"], serial=False))

    def wifi_disconnect(self):
        ip = self.wip.get().strip()
        self.bg(lambda: self.adb_log(["disconnect"] + ([ip if ":" in ip else ip + ":5555"] if ip else []), serial=False))

    def wifi_pair(self):
        addr, code = self.wpair.get().strip(), self.wcode.get().strip()
        if not addr or not code:
            self.say("Enter the pairing IP:port and the 6-digit code")
            return
        self.bg(lambda: self.adb_log(["pair", addr, code], serial=False))

    def wifi_from_usb(self):
        rc, out = self.run_adb(["shell", "ip", "route"])
        m = re.search(r"src (\d+\.\d+\.\d+\.\d+)", out or "")
        if not m:
            rc, out = self.run_adb(["shell", "ip", "-f", "inet", "addr", "show", "wlan0"])
            m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out or "")
        if not m:
            self.say("Could not find the phone's Wi-Fi address - is Wi-Fi on?")
            return
        ip = m.group(1)
        self.adb_log(["tcpip", "5555"])
        time.sleep(2)
        self.adb_log(["connect", f"{ip}:5555"], serial=False)
        self.wip.set(f"{ip}:5555")

    def adb_s(self, serial, args, timeout=15):
        try:
            p = subprocess.run([self.adb, "-s", serial] + args, capture_output=True, text=True, timeout=timeout,
                               creationflags=NOWIN)
            return p.returncode, (p.stdout + p.stderr).strip()
        except Exception as e:
            return -1, str(e)

    def keyevent(self, code, serial=None):
        self.run_adb(["shell", "input", "keyevent", str(code)]) if not serial else \
            self.adb_s(serial, ["shell", "input", "keyevent", str(code)])

    def screenshot(self, serial=None):
        serial = serial or self.serial()
        if not (self.adb and serial):
            self.say("Connect a phone first")
            return
        try:
            p = subprocess.run([self.adb, "-s", serial, "exec-out", "screencap", "-p"], capture_output=True,
                               timeout=25, creationflags=NOWIN)
            if p.returncode != 0 or not p.stdout.startswith(b"\x89PNG"):
                self.say("Screenshot failed")
                return
            os.makedirs(SHOT_DIR, exist_ok=True)
            path = os.path.join(SHOT_DIR, time.strftime("shot_%Y%m%d_%H%M%S.png"))
            with open(path, "wb") as f:
                f.write(p.stdout)
            self.say("Screenshot saved: " + path)
        except Exception as e:
            self.say(f"Screenshot failed: {e}")

    def set_stay(self, on):
        self.run_adb(["shell", "svc", "power", "stayon", "true" if on else "false"])
        self.say("Screen stays on while charging" if on else "Screen timeout back to normal")

    def toggle_top(self):
        self.cfg["top"] = bool(self.top_var.get())
        save_cfg(self.cfg)
        self.root.attributes("-topmost", self.cfg["top"])

    def on_wheel(self, e):
        if self.capturing:
            self.wheel_q.append(1 if e.delta > 0 else -1)

    # ---------- mouse capture ----------
    def start_capture(self):
        if self.capturing:
            return
        if os.name != "nt":
            self.say("Mouse capture is Windows-only")
            return
        if not self.serial():
            self.say("Connect a phone first")
            return
        try:
            ax, ay = float(self.ax.get()), float(self.ay.get())
        except ValueError:
            self.say("Camera X,Y must be numbers between 0 and 1")
            return
        self.cfg.update(sens=self.sens.get(), ax=self.ax.get(), ay=self.ay.get())
        save_cfg(self.cfg)
        self.area.update_idletasks()
        rect = (self.area.winfo_rootx(), self.area.winfo_rooty(),
                self.area.winfo_rootx() + self.area.winfo_width(),
                self.area.winfo_rooty() + self.area.winfo_height())
        self.capturing = True
        self.area.config(text="Starting touch link...")
        self.area.focus_force()
        threading.Thread(target=self.capture_run, daemon=True,
                         args=(self.serial(), self.sens.get(), ax, ay, self.land.get(), rect)).start()

    def screen_size(self, landscape):
        rc, out = self.run_adb(["shell", "wm", "size"])
        sz = parse_wm_size(out) if rc == 0 else None
        if not sz:
            return None
        lo, hi = min(sz), max(sz)
        return (hi, lo) if landscape else (lo, hi)

    def load_keymap(self):
        """The keymap dict from the phone (fallback: the last pushed file), or None."""
        tmp = os.path.join(BASE, "_keymap_tmp.json")
        paths = []
        rc, _ = self.run_adb(["pull", REMOTE_JSON, tmp])
        if rc == 0:
            paths.append(tmp)
        if self.cfg.get("last_keymap"):
            paths.append(self.cfg["last_keymap"])
        for p in paths:
            try:
                with open(p, encoding="utf-8") as f:
                    data = json.load(f)
                if "keyMapNodes" in data:
                    return data
            except Exception:
                continue
        return None

    def capture_run(self, serial, sens, ax, ay, landscape, rect):
        user32 = ctypes.windll.user32
        try:
            ctypes.windll.winmm.timeBeginPeriod(1)   # 1 ms timer so the loop ticks evenly
        except Exception:
            pass
        ov = None
        link = None
        try:
            size = self.screen_size(landscape)
            if not size:
                self.say("Could not read the phone screen size (adb shell wm size)")
                return
            w, h = size
            if self.link is None or not self.link.alive:
                if self.link:
                    self.link.stop()
                self.link = TouchLink(self)
                if not self.link.start(serial):
                    return
            link = self.link
            ov = OverlayLink(self)
            if not ov.start(serial):
                self.say("Phone cursor overlay not reachable - install the newest APK and start the overlay. "
                         "Cursor mode will work but you won't see the cursor.")
            aim_vk = vk_for(AIM_KEY)     # ` (VK_OEM_3), fixed
            aim_name = key_label(AIM_KEY)
            pressed = lambda vk: vk is not None and bool(user32.GetAsyncKeyState(vk) & 0x8000)

            def make_rt(data):
                r = build_runtime(data, w, h, sens, ax, ay, aim_vk)
                for bd in r["binds"]:
                    bd.out = (lambda act, x, y, _p=bd.pid: link.send(act, _p, x, y, w, h, 0 if act == 1 else 0xFFFF))
                return r

            rt = make_rt(self.load_keymap())
            if not rt["aim"]:
                self.say("No aim marker in the keymap - using the Camera X,Y fields (add +Aim in the overlay)")

            # live reload: while in cursor mode, re-read keymap.json from the phone every ~1.2 s, so sensitivity,
            # range, markers and aim point edited in the overlay apply immediately (no need to restart the capture)
            live = {"data": None, "ver": 0, "raw": None}
            run_id = object()
            self.cap_run = run_id
            self.cap_mode = "cursor"

            def watch_keymap():
                tmp2 = os.path.join(BASE, "_keymap_live.json")
                while self.capturing and self.cap_run is run_id:
                    time.sleep(1.2)
                    if self.cap_mode != "cursor":
                        continue
                    try:
                        pr = subprocess.run([self.adb, "-s", serial, "pull", REMOTE_JSON, tmp2],
                                            capture_output=True, timeout=8, creationflags=NOWIN)
                        if pr.returncode != 0:
                            continue
                        with open(tmp2, "rb") as f:
                            raw = f.read()
                        if raw == live["raw"]:
                            continue
                        first = live["raw"] is None
                        live["raw"] = raw
                        if first:
                            continue        # first read = what the capture already loaded
                        live["data"] = json.loads(raw.decode("utf-8"))
                        live["ver"] += 1
                    except Exception:
                        pass

            threading.Thread(target=watch_keymap, daemon=True).start()
            seen_ver = 0

            def send_joy(events):
                for act, jx, jy in events:
                    link.send(act, 8, jx, jy, w, h, 0 if act == 1 else 0xFFFF)

            def release_game():
                for bd in rt["binds"]:
                    bd.release_all()
                if rt["joy"]:
                    up = rt["joy"].release()
                    if up:
                        send_joy([up])

            def enter_game():
                for bd in rt["binds"]:
                    bd.reset_key(pressed(bd.vk))

            def describe():
                kinds = {}
                for bd in rt["binds"]:
                    k = bd.kind if bd.kind != "click" else bd.mode
                    kinds[k] = kinds.get(k, 0) + 1
                return ", ".join(f"{n} {k}" for k, n in sorted(kinds.items())) or "none"

            self.say(f"Screen {w}x{h}. Press {aim_name} to switch cursor/aim. Markers: {describe()}. "
                     f"Direction pad: {'yes' if rt['has_wheel'] else 'no'}. Aim marker: {'yes' if rt['aim'] else 'no'}")

            l, t, r, b = rect
            cx, cy = (l + r) // 2, (t + b) // 2
            clip = RECT(l, t, r, b)
            user32.ClipCursor(ctypes.byref(clip))
            user32.SetCursorPos(cx, cy)
            self.q.put(("capon", aim_name))
            self.wheel_q.clear()

            pt = POINT()
            mode = "cursor"
            paused = False
            curx, cury = w / 2.0, h / 2.0           # simulated cursor position (phone px)
            cur_down = False                         # cursor-mode finger is down
            prev_lm = pressed(1)
            camx = camy = 0.0
            cam_pid = 1                              # camera finger id; alternates 1 <-> 2 on a handoff
            down = False                             # aim-mode camera finger is down
            last_sent = 0.0
            gap_until = down_time = 0.0              # aim finger: re-touch gap after a range reset / settle time
            dirty = True
            prev_hot = pressed(aim_vk)
            hk_prev = {}
            ov.send(f"P {curx / w:.4f} {cury / h:.4f}")

            def hotkey(name, vk, armed):
                on = armed and pressed(vk)
                fire = on and not hk_prev.get(name, False)
                hk_prev[name] = on
                return fire

            def phone_reload():
                if ov and ov.alive:
                    ov.send("R")
                else:
                    self.bg(lambda: self.adb_s(serial, ["shell", "am", "start-foreground-service", "-n", SVC, "-a", "RELOAD"]))

            while self.capturing and link.alive:
                if pressed(0x1B):
                    break
                now = time.time()
                armed = pressed(0x11) and pressed(0x12)          # Ctrl + Alt = hotkey layer

                # ---- global hotkeys ----
                if hotkey("pause", 0x50, armed):
                    paused = not paused
                    if paused:
                        if down:
                            link.send(1, cam_pid, camx, camy, w, h, 0)
                            down = False
                        if cur_down:
                            link.send(1, 4, curx, cury, w, h, 0)
                            cur_down = False
                        release_game()
                        user32.ClipCursor(None)
                        ov.send("H")
                        self.q.put(("capmode", ("pause", aim_name)))
                        self.say("Paused - mouse and keyboard are free")
                    else:
                        user32.ClipCursor(ctypes.byref(clip))
                        user32.SetCursorPos(cx, cy)
                        if mode == "game":
                            enter_game()
                        else:
                            prev_lm = pressed(1)
                            dirty = True
                        self.q.put(("capmode", (mode, aim_name)))
                        self.say("Resumed")
                if paused:
                    time.sleep(0.02)
                    continue
                if hotkey("hide", 0x48, armed):
                    ov.send("V")
                for i in range(9):
                    if hotkey(f"prof{i}", 0x31 + i, armed):
                        names = list_profiles()
                        if i >= len(names):
                            self.say(f"No profile #{i + 1} (open the Profiles tab)")
                            continue
                        if down:
                            link.send(1, cam_pid, camx, camy, w, h, 0)
                            down = False
                        if cur_down:
                            link.send(1, 4, curx, cury, w, h, 0)
                            cur_down = False
                        release_game()
                        nd = self.push_profile_quick(serial, names[i])
                        if nd is not None:
                            rt = make_rt(nd)
                            live["raw"] = None
                            phone_reload()
                            self.say(f"Profile: {names[i]}")
                        if mode == "game":
                            enter_game()
                for name, vk, code in (("back", 0x08, 4), ("home", 0x24, 3), ("recents", 0x23, 187),
                                       ("vup", 0x26, 24), ("vdown", 0x28, 25)):
                    if hotkey(name, vk, armed):
                        self.bg(lambda c=code: self.keyevent(c, serial))
                if hotkey("shot", 0x53, armed):
                    self.bg(lambda: self.screenshot(serial))

                if mode == "cursor" and live["ver"] != seen_ver and live["data"] is not None:
                    seen_ver = live["ver"]
                    rt = make_rt(live["data"])
                    self.say("Keymap updated from the phone")
                cam = rt["cam"]
                hot = pressed(aim_vk)
                if hot and not prev_hot:
                    if mode == "cursor":
                        if cur_down:
                            link.send(1, 4, curx, cury, w, h, 0)
                            cur_down = False
                        mode = "game"
                        self.cap_mode = mode
                        ov.send("H")
                        enter_game()
                        self.wheel_q.clear()
                    else:
                        if down:
                            link.send(1, cam_pid, camx, camy, w, h, 0)
                            down = False
                        release_game()
                        mode = "cursor"
                        self.cap_mode = mode
                        prev_lm = pressed(1)
                        dirty = True
                    self.q.put(("capmode", (mode, aim_name)))
                    self.say("AIM mode ON" if mode == "game" else "Cursor mode ON")
                prev_hot = hot

                user32.GetCursorPos(ctypes.byref(pt))
                dx, dy = pt.x - cx, pt.y - cy
                moved = bool(dx or dy)
                if moved:
                    user32.SetCursorPos(cx, cy)

                if mode == "cursor":
                    self.wheel_q.clear()
                    # plain mouse: a cursor on the phone, left click = tap under it (markers are NOT triggered)
                    if moved:
                        curx = max(0.0, min(w - 1.0, curx + dx * sens))
                        cury = max(0.0, min(h - 1.0, cury + dy * sens))
                        dirty = True
                        if cur_down:
                            link.send(2, 4, curx, cury, w, h)
                    lm = pressed(1) and aim_vk != 1
                    if lm and not prev_lm:
                        link.send(0, 4, curx, cury, w, h)
                        cur_down = True
                    elif not lm and prev_lm and cur_down:
                        link.send(1, 4, curx, cury, w, h, 0)
                        cur_down = False
                    prev_lm = lm
                    if dirty and now - last_sent >= 0.008:
                        ov.send(f"P {curx / w:.4f} {cury / h:.4f}")
                        dirty = False
                        last_sent = now
                else:
                    # aim mode: mouse = camera finger, keys/buttons trigger the markers
                    # the camera finger is HELD DOWN automatically at the aim point for as long as aim mode is on;
                    # moving the mouse just slides it (like keeping a finger pressed on the screen)
                    if not down and now >= gap_until:
                        camx, camy = cam.ax * w, cam.ay * h
                        link.send(0, cam_pid, camx, camy, w, h)
                        down = True
                        down_time = now
                    grabbed = any(bd.skill for bd in rt["key_binds"])      # a skill-shot is aiming with the mouse
                    if moved and down and not grabbed:
                        adx, ady = float(dx), float(dy)
                        if cam.invert:
                            ady = -ady
                        if cam.dz:
                            if abs(adx) <= cam.dz:
                                adx = 0.0
                            if abs(ady) <= cam.dz:
                                ady = 0.0
                        if adx or ady:
                            gain = 1.0
                            if cam.accel:
                                gain += cam.accel / 100.0 * min(math.hypot(adx, ady) / 12.0, 2.5)
                            if cam.ads_vk and pressed(cam.ads_vk):
                                gain *= cam.ads_mult
                            camx += adx * cam.sx * gain
                            camy += ady * cam.sy * gain
                            if not (cam.xmin * w <= camx <= cam.xmax * w and cam.ymin * h <= camy <= cam.ymax * h):
                                camx = min(max(camx, cam.xmin * w), cam.xmax * w)
                                camy = min(max(camy, cam.ymin * h), cam.ymax * h)
                                link.send(2, cam_pid, camx, camy, w, h)
                                if cam.reset:
                                    if AIM_HANDOFF:
                                        # endless smooth turning: a fresh finger lands at the aim point while the old
                                        # one is still down, then the old one lifts. The camera never stops being touched.
                                        new_pid = 2 if cam_pid == 1 else 1
                                        link.send(0, new_pid, cam.ax * w, cam.ay * h, w, h)
                                        time.sleep(AIM_OVERLAP)
                                        link.send(1, cam_pid, camx, camy, w, h, 0)
                                        cam_pid = new_pid
                                        camx, camy = cam.ax * w, cam.ay * h
                                        down_time = now
                                    else:
                                        # lift at the edge; pressed again at the aim point after a short gap
                                        link.send(1, cam_pid, camx, camy, w, h, 0)
                                        down = False
                                        gap_until = now + AIM_GAP
                            elif now - down_time >= AIM_SETTLE:
                                link.send(2, cam_pid, camx, camy, w, h)
                    # markers: hold / tap / toggle / double / long / turbo / swipe / skill-shot
                    for bd in rt["key_binds"]:
                        bd.tick(pressed(bd.vk), now, dx * sens, dy * sens)
                    while self.wheel_q:
                        d = self.wheel_q.popleft()
                        for bd in rt["wheel_binds"]:
                            if bd.wheel == d:
                                bd.trigger(now)
                    for bd in rt["wheel_binds"]:
                        bd.tick(False, now)
                    if rt["joy"]:
                        wvk = rt["wvk"]
                        vx = int(pressed(wvk[3])) - int(pressed(wvk[1]))
                        vy = int(pressed(wvk[2])) - int(pressed(wvk[0]))
                        send_joy(rt["joy"].update(vx, vy, now))
                time.sleep(0.002)

            # release everything that is still down
            if down:
                link.send(1, cam_pid, camx, camy, w, h, 0)
            if cur_down:
                link.send(1, 4, curx, cury, w, h, 0)
            release_game()
        except Exception as e:
            self.say(f"Capture error: {e}")
        finally:
            try:
                user32.ClipCursor(None)
                ctypes.windll.winmm.timeEndPeriod(1)
            except Exception:
                pass
            if ov:
                ov.send("H")
                ov.stop()
            self.capturing = False
            self.q.put(("capend", None))
            self.say("Mouse released")

    def close(self):
        self.running = False
        self.capturing = False
        if self.link:
            self.link.stop()
        self.root.destroy()


if __name__ == "__main__":
    r = tk.Tk()
    try:
        r.iconbitmap(os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "icon.ico"))
    except Exception:
        pass
    App(r)
    r.mainloop()
