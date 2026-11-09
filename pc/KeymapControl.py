"""Keymap Control - Windows control panel for the Keymap Overlay APK, using ADB."""
import ctypes, json, os, queue, re, shutil, socket, struct, subprocess, sys, threading, time, urllib.request, zipfile
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
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
    "cursor": "CURSOR MODE - cursor visible on the phone\nPress ` to lock into the game  |  Esc = release",
    "game": "GAME MODE - mouse turns the camera\nPress ` for the cursor  |  Esc = release",
}
MODE_BG = {"cursor": "#0d47a1", "game": "#1b5e20"}
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


def parse_wm_size(text):
    """Return (w, h) in natural orientation from `wm size`, preferring the override size."""
    found = dict((k.lower(), (int(w), int(h))) for k, w, h in re.findall(r"(\w+) size:\s*(\d+)x(\d+)", text))
    return found.get("override") or found.get("physical")


class App:
    def __init__(self, root):
        self.root = root
        self.cfg = load_cfg()
        self.adb = find_adb(self.cfg)
        self.q = queue.Queue()
        self.devices = []
        self.running = True
        root.title("Keymap Control")
        root.geometry("560x560")
        self.build_ui()
        threading.Thread(target=self.poll_loop, daemon=True).start()
        self.root.after(100, self.pump)
        root.protocol("WM_DELETE_WINDOW", self.close)

    # ---------- UI ----------
    def build_ui(self):
        top = ttk.Frame(self.root, padding=10)
        top.pack(fill="x")
        self.dot = tk.Canvas(top, width=22, height=22, highlightthickness=0)
        self.dot.pack(side="left")
        self.led = self.dot.create_oval(3, 3, 19, 19, fill="#c62828", outline="")
        self.status = ttk.Label(top, text="Starting...", font=("Segoe UI", 11, "bold"))
        self.status.pack(side="left", padx=8)
        self.combo = ttk.Combobox(top, state="readonly", width=24)
        self.combo.pack(side="right")

        adbrow = ttk.Frame(self.root, padding=(10, 0))
        adbrow.pack(fill="x")
        self.adb_label = ttk.Label(adbrow, text=self.adb_text())
        self.adb_label.pack(side="left")
        ttk.Button(adbrow, text="Download ADB", command=lambda: self.bg(self.download_adb)).pack(side="right")
        ttk.Button(adbrow, text="Browse...", command=self.browse_adb).pack(side="right", padx=4)

        self.btns = []
        g1 = ttk.LabelFrame(self.root, text="App", padding=8)
        g1.pack(fill="x", padx=10, pady=6)
        self.add_btn(g1, "Install APK...", self.install_apk, 0, 0)
        self.add_btn(g1, "Grant overlay permission", self.grant, 0, 1)
        self.add_btn(g1, "Open app", lambda: self.shell(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"), 0, 2)

        g2 = ttk.LabelFrame(self.root, text="Overlay", padding=8)
        g2.pack(fill="x", padx=10, pady=6)
        self.add_btn(g2, "Start", lambda: self.svc(None), 0, 0)
        self.add_btn(g2, "Reload", lambda: self.svc("RELOAD"), 0, 1)
        self.add_btn(g2, "Lock", lambda: self.svc("LOCK"), 0, 2)
        self.add_btn(g2, "Edit", lambda: self.svc("EDIT"), 0, 3)
        self.add_btn(g2, "Stop", lambda: self.svc("STOP"), 0, 4)

        g3 = ttk.LabelFrame(self.root, text="Keymap file", padding=8)
        g3.pack(fill="x", padx=10, pady=6)
        self.add_btn(g3, "Push keymap.json...", self.push, 0, 0)
        self.add_btn(g3, "Pull keymap.json...", self.pull, 0, 1)

        self.quick = ttk.Button(self.root, text="Quick start: grant + push last keymap + show overlay",
                                command=lambda: self.bg(self.quick_start))
        self.quick.pack(fill="x", padx=10, pady=4)
        self.btns.append(self.quick)

        self.link = None
        self.capturing = False
        g4 = ttk.LabelFrame(self.root, text="Mouse (crosshair on phone)", padding=8)
        g4.pack(fill="x", padx=10, pady=6)
        row = ttk.Frame(g4)
        row.pack(fill="x")
        ttk.Label(row, text="Sensitivity").pack(side="left")
        self.sens = tk.DoubleVar(value=self.cfg.get("sens", 2.0))
        ttk.Scale(row, from_=0.3, to=6, variable=self.sens, length=130).pack(side="left", padx=6)
        self.land = tk.BooleanVar(value=True)
        ttk.Checkbutton(row, text="Landscape", variable=self.land).pack(side="left", padx=6)
        ttk.Label(row, text="Camera X,Y").pack(side="left")
        self.ax = tk.StringVar(value=self.cfg.get("ax", "0.75"))
        self.ay = tk.StringVar(value=self.cfg.get("ay", "0.5"))
        ttk.Entry(row, textvariable=self.ax, width=5).pack(side="left", padx=2)
        ttk.Entry(row, textvariable=self.ay, width=5).pack(side="left", padx=2)
        self.area = tk.Label(g4, text="CLICK HERE to capture the mouse\n(starts in cursor mode)", bg="#263238",
                             fg="white", height=5, cursor="hand2", relief="ridge")
        self.area.pack(fill="x", pady=6)
        self.area.bind("<Button-1>", lambda e: self.start_capture())

        self.log = ScrolledText(self.root, height=12, state="disabled", font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, padx=10, pady=8)
        self.set_enabled(False)

    def add_btn(self, parent, text, cmd, r, c):
        b = ttk.Button(parent, text=text, command=lambda: self.bg(cmd))
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
                    self.area.config(text=MODE_TEXT["cursor"], bg=MODE_BG["cursor"], cursor="none")
                elif kind == "capmode":
                    self.area.config(text=MODE_TEXT[val], bg=MODE_BG[val])
                elif kind == "capend":
                    self.area.config(text="CLICK HERE to capture the mouse\n(starts in cursor mode)",
                                     bg="#263238", cursor="hand2")
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
            self.led_set("#c62828", "ADB not found")
        elif ready:
            d = ready[0]
            self.led_set("#2e7d32", f"Connected: {d[2] or d[0]}")
        elif devs:
            st = devs[0][1]
            hint = {"unauthorized": "Accept the USB debugging prompt on the phone", "offline": "Replug the cable"}.get(st, st)
            self.led_set("#f9a825", f"{st}: {hint}")
        else:
            self.led_set("#c62828", "No device connected")
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
                self.q.put(("devices", parse_devices(out) if rc == 0 else []))
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
        if last and os.path.isfile(last):
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
        threading.Thread(target=self.capture_run, daemon=True,
                         args=(self.serial(), self.sens.get(), ax, ay, self.land.get(), rect)).start()

    def screen_size(self, landscape):
        rc, out = self.run_adb(["shell", "wm", "size"])
        sz = parse_wm_size(out) if rc == 0 else None
        if not sz:
            return None
        lo, hi = min(sz), max(sz)
        return (hi, lo) if landscape else (lo, hi)

    def load_clicks(self):
        """Positions of Button_Left / Button_Right from the keymap on the phone (fallback: last pushed file)."""
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
                    nodes = json.load(f)["keyMapNodes"]
                return {n["key"]: (n["pos"]["x"], n["pos"]["y"]) for n in nodes
                        if n.get("type") == "KMT_CLICK" and n.get("key") in ("Button_Left", "Button_Right")}
            except Exception:
                continue
        return {}

    def capture_run(self, serial, sens, ax, ay, landscape, rect):
        user32 = ctypes.windll.user32
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
            clicks = self.load_clicks()
            self.say(f"Screen {w}x{h}. Game-mode mouse buttons: {', '.join(clicks) or 'none (set LMB/RMB markers in the overlay)'}")
            l, t, r, b = rect
            cx, cy = (l + r) // 2, (t + b) // 2
            clip = RECT(l, t, r, b)
            user32.ClipCursor(ctypes.byref(clip))
            user32.SetCursorPos(cx, cy)
            self.q.put(("capon", None))

            pressed = lambda vk: bool(user32.GetAsyncKeyState(vk) & 0x8000)
            pt = POINT()
            mode = "cursor"
            curx, cury = w / 2.0, h / 2.0           # simulated cursor position (phone px)
            cur_down = False                         # cursor-mode finger is down
            prev_lm = pressed(1)
            camx = camy = 0.0
            down = False                             # game-mode camera finger is down
            last_move = last_sent = 0.0
            dirty = True
            held = {"Button_Left": pressed(1), "Button_Right": pressed(2)}
            prev_hot = pressed(0xC0)                 # ` key toggles the mode
            ov.send(f"P {curx / w:.4f} {cury / h:.4f}")

            while self.capturing and link.alive:
                if pressed(0x1B):
                    break
                now = time.time()
                hot = pressed(0xC0)
                if hot and not prev_hot:
                    if mode == "cursor":
                        if cur_down:
                            link.send(1, 4, curx, cury, w, h, 0)
                            cur_down = False
                        mode = "game"
                        ov.send("H")
                        held = {"Button_Left": pressed(1), "Button_Right": pressed(2)}
                    else:
                        if down:
                            link.send(1, 1, camx, camy, w, h, 0)
                            down = False
                        for key, pid in (("Button_Left", 2), ("Button_Right", 3)):
                            if key in clicks and held.get(key):
                                link.send(1, pid, clicks[key][0] * w, clicks[key][1] * h, w, h, 0)
                        mode = "cursor"
                        prev_lm = pressed(1)
                        dirty = True
                    self.q.put(("capmode", mode))
                prev_hot = hot

                user32.GetCursorPos(ctypes.byref(pt))
                dx, dy = pt.x - cx, pt.y - cy
                moved = bool(dx or dy)
                if moved:
                    user32.SetCursorPos(cx, cy)

                if mode == "cursor":
                    if moved:
                        curx = max(0.0, min(w - 1.0, curx + dx * sens))
                        cury = max(0.0, min(h - 1.0, cury + dy * sens))
                        dirty = True
                        if cur_down:
                            link.send(2, 4, curx, cury, w, h)
                    lm = pressed(1)
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
                    if moved:
                        if not down:
                            camx, camy = ax * w, ay * h
                            link.send(0, 1, camx, camy, w, h)
                            down = True
                        camx += dx * sens
                        camy += dy * sens
                        if not (0.05 * w < camx < 0.95 * w and 0.05 * h < camy < 0.95 * h):
                            link.send(1, 1, camx, camy, w, h, 0)
                            camx, camy = ax * w, ay * h
                            link.send(0, 1, camx, camy, w, h)
                        link.send(2, 1, camx, camy, w, h)
                        last_move = now
                    elif down and now - last_move > 0.25:
                        link.send(1, 1, camx, camy, w, h, 0)
                        down = False
                    for vk, pid, key in ((1, 2, "Button_Left"), (2, 3, "Button_Right")):
                        if key not in clicks:
                            continue
                        state = pressed(vk)
                        if state != held[key]:
                            held[key] = state
                            link.send(0 if state else 1, pid, clicks[key][0] * w, clicks[key][1] * h, w, h,
                                      0xFFFF if state else 0)
                time.sleep(0.002)

            # release everything that is still down
            if down:
                link.send(1, 1, camx, camy, w, h, 0)
            if cur_down:
                link.send(1, 4, curx, cury, w, h, 0)
            for key, pid in (("Button_Left", 2), ("Button_Right", 3)):
                if key in clicks and held.get(key) and mode == "game":
                    link.send(1, pid, clicks[key][0] * w, clicks[key][1] * h, w, h, 0)
        except Exception as e:
            self.say(f"Capture error: {e}")
        finally:
            try:
                user32.ClipCursor(None)
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
    App(r)
    r.mainloop()
