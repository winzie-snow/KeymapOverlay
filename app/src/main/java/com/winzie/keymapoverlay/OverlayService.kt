package com.winzie.keymapoverlay

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.LocalServerSocket
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val markers = mutableListOf<Marker>()
    private var panel: View? = null
    private var handle: View? = null
    private var lockButton: Button? = null
    private var locked = false
    private var hideAll = false              // hide every button/pad while playing (locked); the center crosshair stays
    private var hideAllButton: Button? = null
    private var linesView: LinesView? = null    // dashed lines for swipe markers (edit mode)
    private var gridView: GridView? = null      // snap grid (edit mode)
    private var gridOn = false
    private var gridDp = 24
    private val history = ArrayList<String>()   // undo / redo snapshots of the whole layout
    private var histIdx = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- look & feel ----------
    private val C_BG = 0xF0151A21.toInt()        // toolbar background
    private val C_PANEL = 0xFF262F3B.toInt()     // buttons / fields
    private val C_ACCENT = 0xFF29B6F6.toInt()
    private val C_ON_ACCENT = 0xFF06232F.toInt()
    private val C_TEXT = 0xFFE8EDF3.toInt()
    private val C_MUTED = 0xFF8B98A8.toInt()
    private val C_GOOD = 0xFF43A047.toInt()
    private val C_BAD = 0xFFE53935.toInt()
    private val C_LINE = 0xFF2C3542.toInt()

    private fun rounded(color: Int, r: Int = 10, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun styleBtn(b: Button, bg: Int = C_PANEL, fg: Int = C_TEXT) {
        b.background = rounded(bg, 10)
        b.setTextColor(fg)
        b.stateListAnimator = null
        b.setAllCaps(false)
        b.minWidth = 0; b.minimumWidth = 0; b.minHeight = 0; b.minimumHeight = 0
    }

    private fun actionBtn(t: String, bg: Int = C_PANEL, fg: Int = C_TEXT, click: () -> Unit): Button =
        Button(this).apply { text = t; textSize = 13f; styleBtn(this, bg, fg); setOnClickListener { click() } }

    private fun rowLp() = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(C_MUTED); setPadding(0, dp(2), 0, dp(4)) }

    private fun dialogColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }

    private fun switchRow(t: String, on: Boolean, change: (Boolean) -> Unit) = android.widget.Switch(this).apply {
        text = t; isChecked = on; setTextColor(C_TEXT); setPadding(0, dp(10), 0, 0)
        setOnCheckedChangeListener { _, c -> change(c) }
    }

    // type: "click", "wheel" (direction pad) or "aim" (mouse look)
    inner class Marker(val type: String, var key: String, val view: View, val lp: LP,
                       var wheel: MutableList<String> = mutableListOf("Key_W", "Key_A", "Key_S", "Key_D")) {
        var sensX = 50
        var sensY = 50
        var rangeW = 0.4f
        var rangeH = 0.6f
        var reset = true
        var hideBox = false            // hide the range box while locked (playing)
        var centerCross = true         // fixed crosshair at the middle of the screen
        var centerView: View? = null
        var centerLp: LP? = null
        var alpha = 0.6f               // button opacity, 0.1 .. 1.0
        var rangeView: View? = null
        var rangeLp: LP? = null
        var grip: View? = null         // drag handle used to resize the direction pad / aim range
        var gripLp: LP? = null
        // click markers
        var mode = "hold"              // hold | tap | toggle | double | long | turbo
        var turboHz = 12
        var longMs = 600
        // swipe marker: the end point is its own draggable window
        var durMs = 200
        var endView: View? = null
        var endLp: LP? = null
        // skill-shot marker
        var skillSens = 100            // %
        // aim extras
        var invertY = false
        var deadZone = 0
        var accel = 0                  // %
        var adsKey = ""
        var adsMult = 60               // % of normal speed while the ADS key is held
        var chStyle = "cross"          // cross | dot | circle | crossdot
        var chSize = 44                // dp
        var chColor = 0xFFFFFFFF.toInt()
    }

    // ---------- custom views ----------
    private class WheelView(c: Context) : View(c) {
        var labels: List<String> = listOf("W", "A", "S", "D") // up, left, down, right
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00B0FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 8f }
        private val pad = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); style = Paint.Style.FILL }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88B0BEC5.toInt(); style = Paint.Style.FILL }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 6f
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, ring)
            val half = r * 0.30f
            val dist = r * 0.55f
            val dirs = arrayOf(floatArrayOf(0f, -1f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 0f))
            for (i in 0 until 4) {
                val px = cx + dirs[i][0] * dist
                val py = cy + dirs[i][1] * dist
                rect.set(px - half, py - half, px + half, py + half)
                canvas.drawRoundRect(rect, half * 0.4f, half * 0.4f, pad)
                val label = labels.getOrElse(i) { "" }
                txt.textSize = half * (if (label.length > 2) 0.7f else 1.1f)
                canvas.drawText(label, px, py - (txt.ascent() + txt.descent()) / 2f, txt)
            }
            canvas.drawCircle(cx, cy, r * 0.14f, dot)
        }
    }

    private class AimView(c: Context) : View(c) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553A5AFF; style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9FB4FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5C6BC0.toInt(); style = Paint.Style.FILL }
        private val xhOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        private val xhLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
        // locked (playing): draw only a crosshair, no blue circle. Edit mode keeps the full circle so it can be grabbed.
        var crosshairOnly = false
            set(v) { if (field != v) { field = v; invalidate() } }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 4f
            if (crosshairOnly) {
                val a = r * 0.7f
                canvas.drawLine(cx - a, cy, cx + a, cy, xhOutline)
                canvas.drawLine(cx, cy - a, cx, cy + a, xhOutline)
                canvas.drawLine(cx - a, cy, cx + a, cy, xhLine)
                canvas.drawLine(cx, cy - a, cx, cy + a, xhLine)
                return
            }
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawLine(cx - r, cy, cx + r, cy, ring)
            canvas.drawLine(cx, cy - r, cx, cy + r, ring)
            canvas.drawCircle(cx, cy, r * 0.28f, dot)
        }
    }

    // fixed crosshair for the middle of the screen (the aim pad itself stays where the camera finger touches)
    private class CenterCrossView(c: Context) : View(c) {
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt(); style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5252.toInt(); style = Paint.Style.FILL }
        var style = "cross"
            set(v) { field = v; invalidate() }
        var color = Color.WHITE
            set(v) { field = v; line.color = v; fill.color = v; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 4f
            when (style) {
                "dot" -> {
                    canvas.drawCircle(cx, cy, r * 0.2f + 3f, halo)
                    canvas.drawCircle(cx, cy, r * 0.2f, fill)
                }
                "circle" -> {
                    canvas.drawCircle(cx, cy, r * 0.7f, outline)
                    canvas.drawCircle(cx, cy, r * 0.7f, line)
                    canvas.drawCircle(cx, cy, 3.5f, dot)
                }
                "crossdot" -> {
                    for (p in arrayOf(outline, line)) {
                        canvas.drawLine(cx - r, cy, cx + r, cy, p)
                        canvas.drawLine(cx, cy - r, cx, cy + r, p)
                    }
                    canvas.drawCircle(cx, cy, 5f, dot)
                }
                else -> {
                    val g = r * 0.28f
                    for (p in arrayOf(outline, line)) {
                        canvas.drawLine(cx - r, cy, cx - g, cy, p)
                        canvas.drawLine(cx + g, cy, cx + r, cy, p)
                        canvas.drawLine(cx, cy - r, cx, cy - g, p)
                        canvas.drawLine(cx, cy + g, cx, cy + r, p)
                    }
                    canvas.drawCircle(cx, cy, 3.5f, dot)
                }
            }
        }
    }

    // skill-shot marker: a ring whose size is the drag range of the skill
    private class SkillView(c: Context) : View(c) {
        var label = "Q"
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF9800; style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB74D.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE65100.toInt(); style = Paint.Style.FILL }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 5f
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, r * 0.5f, dash)
            canvas.drawCircle(cx, cy, r * 0.3f, core)
            txt.textSize = r * 0.28f * (if (label.length > 2) 0.7f else 1f)
            canvas.drawText(label, cx, cy - (txt.ascent() + txt.descent()) / 2f, txt)
        }
    }

    // draggable end point of a swipe marker
    private class SwipeEndView(c: Context) : View(c) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE5E35B1.toInt(); style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 3f
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, ring)
            val a = r * 0.45f
            canvas.drawLine(cx - a, cy, cx + a, cy, ln)
            canvas.drawLine(cx + a, cy, cx + a * 0.3f, cy - a * 0.7f, ln)
            canvas.drawLine(cx + a, cy, cx + a * 0.3f, cy + a * 0.7f, ln)
        }
    }

    // full-screen, non-touchable: dashed line from every swipe marker to its end point
    private class LinesView(c: Context) : View(c) {
        var lines: List<FloatArray> = emptyList()
        private val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB388FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
        }
        override fun onDraw(canvas: Canvas) {
            for (l in lines) canvas.drawLine(l[0], l[1], l[2], l[3], ln)
        }
    }

    // full-screen snap grid, lines radiate from the screen centre
    private class GridView(c: Context) : View(c) {
        var step = 80f
            set(v) { field = v; invalidate() }
        private val thin = Paint().apply { color = 0x30FFFFFF; strokeWidth = 1f }
        private val mid = Paint().apply { color = 0x70FFFFFF; strokeWidth = 2f }
        override fun onDraw(canvas: Canvas) {
            val s = maxOf(step, 12f)
            val cx = width / 2f
            val cy = height / 2f
            var k = 0
            while (cx + k * s <= width || cx - k * s >= 0) {
                canvas.drawLine(cx + k * s, 0f, cx + k * s, height.toFloat(), thin)
                canvas.drawLine(cx - k * s, 0f, cx - k * s, height.toFloat(), thin)
                k++
            }
            k = 0
            while (cy + k * s <= height || cy - k * s >= 0) {
                canvas.drawLine(0f, cy + k * s, width.toFloat(), cy + k * s, thin)
                canvas.drawLine(0f, cy - k * s, width.toFloat(), cy - k * s, thin)
                k++
            }
            canvas.drawLine(cx, 0f, cx, height.toFloat(), mid)
            canvas.drawLine(0f, cy, width.toFloat(), cy, mid)
        }
    }

    // little round handle with a diagonal double arrow; drag it to resize
    private class GripView(c: Context) : View(c) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE263238.toInt(); style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 3f
            canvas.drawCircle(cx, cy, r, bg)
            canvas.drawCircle(cx, cy, r, ring)
            val a = r * 0.5f
            val h = r * 0.28f
            canvas.drawLine(cx - a, cy - a, cx + a, cy + a, ln)
            canvas.drawLine(cx + a, cy + a, cx + a - h, cy + a, ln)
            canvas.drawLine(cx + a, cy + a, cx + a, cy + a - h, ln)
            canvas.drawLine(cx - a, cy - a, cx - a + h, cy - a, ln)
            canvas.drawLine(cx - a, cy - a, cx - a, cy - a + h, ln)
        }
    }

    private class CursorView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            path.reset()
            path.moveTo(2f, 2f); path.lineTo(2f, s * 0.80f); path.lineTo(s * 0.25f, s * 0.62f)
            path.lineTo(s * 0.42f, s * 0.95f); path.lineTo(s * 0.55f, s * 0.89f); path.lineTo(s * 0.38f, s * 0.57f)
            path.lineTo(s * 0.65f, s * 0.57f); path.close()
            canvas.drawPath(path, fill); canvas.drawPath(path, line)
        }
    }

    // ---------- simulated mouse cursor, driven by the PC over a local socket (adb forward) ----------
    private val ui = Handler(Looper.getMainLooper())
    private var cursorView: CursorView? = null
    private var cursorLp: LP? = null
    private var cursorServer: LocalServerSocket? = null
    @Volatile private var curX = 0f
    @Volatile private var curY = 0f
    @Volatile private var curShow = false
    @Volatile private var curPending = false

    private fun makeCursor() {
        val sz = dp(30)
        val v = CursorView(this)
        v.visibility = View.GONE
        val lp = LP(sz, sz, LP.TYPE_APPLICATION_OVERLAY, baseFlags() or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        cursorView = v; cursorLp = lp
    }

    private fun applyCursor() {
        curPending = false
        val v = cursorView ?: return
        val lp = cursorLp ?: return
        if (!curShow) {
            if (v.visibility != View.GONE) v.visibility = View.GONE
            return
        }
        val m = real()
        lp.x = (curX * m.widthPixels).toInt()
        lp.y = (curY * m.heightPixels).toInt()
        if (v.visibility != View.VISIBLE) {
            runCatching { wm.removeView(v) } // re-add so the cursor sits above every marker
            v.visibility = View.VISIBLE
            wm.addView(v, lp)
        } else {
            wm.updateViewLayout(v, lp)
        }
    }

    private fun startCursorServer() {
        Thread {
            try {
                val srv = LocalServerSocket("keymap_overlay_cursor")
                cursorServer = srv
                while (true) {
                    val c = srv.accept()
                    try {
                        c.outputStream.write("OK\n".toByteArray())
                        val r = java.io.BufferedReader(java.io.InputStreamReader(c.inputStream))
                        while (true) {
                            val line = r.readLine() ?: break
                            val p = line.split(" ")
                            if (p[0] == "P" && p.size >= 3) {
                                curX = p[1].toFloat(); curY = p[2].toFloat(); curShow = true
                            } else if (p[0] == "H") {
                                curShow = false
                            } else if (p[0] == "R") {
                                ui.post { reloadFromFile() }
                                continue
                            } else if (p[0] == "V") {
                                ui.post { setHideAll(!hideAll) }
                                continue
                            } else continue
                            if (!curPending) { curPending = true; ui.post { applyCursor() } }
                        }
                    } catch (e: Exception) {
                    }
                    curShow = false
                    ui.post { applyCursor() }
                    try { c.close() } catch (e: Exception) { }
                }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ch = NotificationChannel("ov", "Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        startForeground(1, Notification.Builder(this, "ov").setContentTitle("Keymap overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_edit).build())
        makeBackdrop()
        showPanel()
        loadExisting()
        makeCursor()
        startCursorServer()
        commit()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "RELOAD" -> reloadFromFile()
            "TOGGLEALL" -> setHideAll(!hideAll)
            "LOCK" -> { setLocked(true); setPanelHidden(true) }
            "EDIT" -> { setLocked(false); setPanelHidden(false) }
            "STOP" -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ArrayList(markers).forEach { removeMarker(it) }
        panel?.let { runCatching { wm.removeView(it) } }
        handle?.let { runCatching { wm.removeView(it) } }
        cursorView?.let { runCatching { wm.removeView(it) } }
        linesView?.let { runCatching { wm.removeView(it) } }
        gridView?.let { runCatching { wm.removeView(it) } }
        runCatching { cursorServer?.close() }
        super.onDestroy()
    }

    private fun real(): DisplayMetrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    private fun baseFlags() = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS or LP.FLAG_LAYOUT_IN_SCREEN

    // ---------- toolbar with hide / show bar ----------
    private fun showPanel() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(C_BG, 16, C_LINE); setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        val lp = LP(-2, -2, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("px", dp(6)); lp.y = prefs.getInt("py", dp(4))
        // drag handle: grab the dots on the left end of the toolbar to move it anywhere (position is remembered)
        val grab = TextView(this).apply {
            text = "\u22EE\u22EE"; textSize = 16f; setTextColor(C_MUTED); gravity = Gravity.CENTER
            var x0 = 0f; var y0 = 0f; var lx = 0; var ly = 0
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; lx = lp.x; ly = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        val sc = real()
                        lp.x = (lx + (e.rawX - x0).toInt()).coerceIn(0, (sc.widthPixels - dp(60)).coerceAtLeast(0))
                        lp.y = (ly + (e.rawY - y0).toInt()).coerceIn(0, (sc.heightPixels - dp(50)).coerceAtLeast(0))
                        wm.updateViewLayout(row, lp)
                    }
                    MotionEvent.ACTION_UP -> prefs.edit().putInt("px", lp.x).putInt("py", lp.y).apply()
                }
                true
            }
        }
        row.addView(grab, LinearLayout.LayoutParams(dp(26), dp(36)))
        fun btn(t: String, bg: Int = C_PANEL, fg: Int = C_TEXT, f: (Button) -> Unit): Button = Button(this).apply {
            text = t; textSize = 11f; styleBtn(this, bg, fg)
            setPadding(dp(11), 0, dp(11), 0); setOnClickListener { f(this) }
        }.also { row.addView(it, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        btn("+ Add", C_ACCENT, C_ON_ACCENT) { addMenu() }
        btn("\u21B6") { undo() }
        btn("\u21B7") { redo() }
        btn("Save", C_GOOD, Color.WHITE) { save() }
        lockButton = btn("Lock") { setLocked(!locked) }
        hideAllButton = btn(if (hideAll) "Show btns" else "Hide btns") { setHideAll(!hideAll) }
        btn("More") { moreMenu() }
        btn("Hide") { setPanelHidden(true) }
        btn("X", C_BAD, Color.WHITE) { stopSelf() }
        wm.addView(row, lp); panel = row

        // small bar shown while the toolbar is hidden: tap it to bring the menu back
        val bar = View(this).apply {
            background = rounded(0xE629B6F6.toInt(), 8, 0x99000000.toInt())
            setOnClickListener { setPanelHidden(false) }
        }
        val blp = LP(dp(72), dp(16), LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        blp.gravity = Gravity.TOP or Gravity.START; blp.x = dp(8); blp.y = dp(2)
        bar.visibility = View.GONE
        wm.addView(bar, blp); handle = bar
    }

    private fun setPanelHidden(hidden: Boolean) {
        panel?.visibility = if (hidden) View.GONE else View.VISIBLE
        handle?.visibility = if (hidden) View.VISIBLE else View.GONE
    }

    private fun setLocked(on: Boolean) {
        // locking from edit mode saves first, so the PC always uses exactly what is on screen
        // (resized range box, moved markers) even if Save was never tapped
        if (on && !locked && markers.isNotEmpty()) save()
        locked = on
        lockButton?.let {
            it.text = if (on) "Edit" else "Lock"
            styleBtn(it, if (on) C_GOOD else C_PANEL, if (on) Color.WHITE else C_TEXT)
        }
        applyLock()
    }

    private fun applyLock() {
        markers.forEach { applyMarkerState(it) }
        applyChrome()
    }

    // grid and swipe lines are editing aids: visible in Edit mode only
    private fun applyChrome() {
        gridView?.visibility = if (gridOn && !locked) View.VISIBLE else View.GONE
        linesView?.visibility = if (locked) View.GONE else View.VISIBLE
    }

    private fun makeBackdrop() {
        fun full(v: View) {
            val lp = LP(-1, -1, LP.TYPE_APPLICATION_OVERLAY, baseFlags() or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.alpha = 0.75f       // <= 0.8 so Android 12+ lets touches pass through to the game
            v.visibility = View.GONE
            wm.addView(v, lp)
        }
        val g = GridView(this); full(g); gridView = g
        val l = LinesView(this); full(l); linesView = l
    }

    private fun refreshLines() {
        val lv = linesView ?: return
        lv.lines = markers.filter { it.type == "swipe" }.mapNotNull { mk ->
            val el = mk.endLp ?: return@mapNotNull null
            floatArrayOf(cxOf(mk).toFloat(), cyOf(mk).toFloat(), el.x + el.width / 2f, el.y + el.height / 2f)
        }
        lv.invalidate()
    }

    // "Hide btns": while locked, no button, direction pad, aim pad or range box is drawn (Edit mode always shows them)
    private fun setHideAll(on: Boolean) {
        hideAll = on
        hideAllButton?.text = if (on) "Show btns" else "Hide btns"
        applyLock()
        save(true)
        if (on && !locked) Toast.makeText(this, "Buttons will be hidden once you tap Lock", Toast.LENGTH_SHORT).show()
    }

    // lock state + opacity for one marker (also used right after a marker is created, so a RELOAD while
    // locked never leaves touchable markers that would swallow the injected touches)
    private fun applyMarkerState(mk: Marker) {
        val flags = if (locked) baseFlags() or LP.FLAG_NOT_TOUCHABLE else baseFlags()
        mk.lp.flags = flags
        val hidden = locked && hideAll
        mk.view.alpha = mk.alpha
        mk.view.visibility = if (hidden) View.GONE else View.VISIBLE
        wm.updateViewLayout(mk.view, mk.lp)
        mk.rangeView?.alpha = if (locked) mk.alpha * 0.4f else mk.alpha
        mk.rangeView?.visibility = if (hidden || (locked && mk.hideBox)) View.GONE else View.VISIBLE
        mk.centerView?.visibility = if (mk.centerCross) View.VISIBLE else View.GONE
        (mk.view as? AimView)?.crosshairOnly = locked
        if (mk.type == "aim") {
            // while playing, keep every aim window at <= 0.75 window opacity: Android 12+ blocks touches that
            // would pass through a more opaque overlay window, which can swallow the camera finger
            val wa = if (locked) 0.75f else 1f
            mk.lp.alpha = wa
            wm.updateViewLayout(mk.view, mk.lp)
            val rv = mk.rangeView; val rl = mk.rangeLp
            if (rv != null && rl != null) { rl.alpha = wa; wm.updateViewLayout(rv, rl) }
        }
        mk.grip?.let { g ->
            val gl = mk.gripLp ?: return@let
            gl.flags = flags
            if (mk.type == "aim") gl.alpha = if (locked) 0.75f else 1f
            g.visibility = if (locked) View.GONE else View.VISIBLE
            wm.updateViewLayout(g, gl)
        }
        mk.endView?.let { ev ->
            val el = mk.endLp ?: return@let
            el.flags = flags
            ev.visibility = if (locked) View.GONE else View.VISIBLE
            wm.updateViewLayout(ev, el)
        }
    }

    private fun cxOf(mk: Marker) = mk.lp.x + mk.lp.width / 2
    private fun cyOf(mk: Marker) = mk.lp.y + mk.lp.height / 2

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // snap a screen coordinate to the editor grid (grid lines radiate from the screen centre)
    private fun snap(v: Int, center: Int): Int {
        if (!gridOn) return v
        val g = dp(gridDp).coerceAtLeast(8)
        return center + Math.round((v - center) / g.toFloat()) * g
    }

    private fun modeShort(mk: Marker) = when {
        mk.type == "swipe" -> "SWIPE"
        mk.mode == "tap" -> "TAP"
        mk.mode == "toggle" -> "TOGGLE"
        mk.mode == "double" -> "x2"
        mk.mode == "long" -> "LONG"
        mk.mode == "turbo" -> "TURBO"
        else -> ""
    }

    private fun refreshLabel(mk: Marker) {
        when (val v = mk.view) {
            is SkillView -> { v.label = labelFor(mk.key); v.invalidate() }
            is TextView -> {
                val main = labelFor(mk.key)
                val sub = modeShort(mk)
                if (sub.isEmpty()) {
                    v.text = main
                } else {
                    val sb = android.text.SpannableStringBuilder(main + "\n" + sub)
                    sb.setSpan(android.text.style.RelativeSizeSpan(0.55f), main.length + 1, sb.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    v.text = sb
                }
            }
            else -> {}
        }
    }

    private fun addMarker(type: String, key: String, fx: Float, fy: Float, sizePx: Int = dp(56)): Marker {
        val m = real()
        val view: View = when (type) {
            "wheel" -> WheelView(this)
            "aim" -> AimView(this)
            "skill" -> SkillView(this)
            else -> TextView(this).apply {
                setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    // opaque: the opacity slider does the fading
                    setColor(if (type == "swipe") 0xFF5E35B1.toInt() else 0xFFE53935.toInt())
                    setStroke(dp(2), 0xF2FFFFFF.toInt())
                }
            }
        }
        val lp = LP(sizePx, sizePx, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (fx * m.widthPixels - sizePx / 2).toInt()
        lp.y = (fy * m.heightPixels - sizePx / 2).toInt()
        val mk = Marker(type, key, view, lp)
        if (type == "aim") {
            // the range rectangle is its own window, added first so the aim marker sits on top of it
            val rv = View(this).apply {
                background = GradientDrawable().apply { setColor(0x22FFFFFF); setStroke(dp(2), 0xAAFFFFFF.toInt()) }
            }
            val rlp = LP(dp(100), dp(100), LP.TYPE_APPLICATION_OVERLAY, baseFlags() or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            rlp.gravity = Gravity.TOP or Gravity.START
            wm.addView(rv, rlp)
            mk.rangeView = rv; mk.rangeLp = rlp
            // fixed crosshair in the middle of the screen (non-touchable, window opacity kept <= 0.75)
            val csz = dp(44)
            val cv = CenterCrossView(this)
            val clp = LP(csz, csz, LP.TYPE_APPLICATION_OVERLAY, baseFlags() or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            clp.gravity = Gravity.TOP or Gravity.START
            clp.x = (m.widthPixels - csz) / 2; clp.y = (m.heightPixels - csz) / 2
            clp.alpha = 0.75f
            wm.addView(cv, clp)
            mk.centerView = cv; mk.centerLp = clp
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        var lx0 = 0; var ly0 = 0; var ex0 = 0; var ey0 = 0
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = cxOf(mk); sy = cyOf(mk); moved = false
                    lx0 = lp.x; ly0 = lp.y
                    mk.endLp?.let { ex0 = it.x; ey0 = it.y }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx) > 10 || Math.abs(e.rawY - dy) > 10) moved = true
                    if (moved) {
                        val sc = real()
                        lp.x = snap(sx + (e.rawX - dx).toInt(), sc.widthPixels / 2) - lp.width / 2
                        lp.y = snap(sy + (e.rawY - dy).toInt(), sc.heightPixels / 2) - lp.height / 2
                        wm.updateViewLayout(view, lp)
                        when (mk.type) {
                            "aim" -> syncRange(mk)
                            "swipe" -> {
                                // the end point travels with the start point
                                mk.endLp?.let { el ->
                                    el.x = ex0 + (lp.x - lx0); el.y = ey0 + (lp.y - ly0)
                                    mk.endView?.let { wm.updateViewLayout(it, el) }
                                }
                                refreshLines()
                            }
                            else -> syncGrip(mk)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) commit() else editMarker(mk)
            }
            true
        }
        wm.addView(view, lp)
        markers.add(mk)
        if (type == "wheel") refreshWheel(mk)
        if (type == "wheel" || type == "aim" || type == "skill") addGrip(mk)
        if (type == "swipe") addSwipeEnd(mk)
        if (type == "aim") { syncRange(mk); applyCrosshair(mk) }
        refreshLabel(mk)
        applyMarkerState(mk)
        return mk
    }

    // draggable end point of a swipe marker
    private fun addSwipeEnd(mk: Marker) {
        val sz = dp(34)
        val v = SwipeEndView(this)
        val el = LP(sz, sz, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        el.gravity = Gravity.TOP or Gravity.START
        el.x = cxOf(mk) + dp(120) - sz / 2
        el.y = cyOf(mk) - sz / 2
        mk.endView = v; mk.endLp = el
        var x0 = 0f; var y0 = 0f; var cx0 = 0; var cy0 = 0
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; cx0 = el.x + el.width / 2; cy0 = el.y + el.height / 2 }
                MotionEvent.ACTION_MOVE -> {
                    val sc = real()
                    el.x = snap(cx0 + (e.rawX - x0).toInt(), sc.widthPixels / 2) - el.width / 2
                    el.y = snap(cy0 + (e.rawY - y0).toInt(), sc.heightPixels / 2) - el.height / 2
                    wm.updateViewLayout(v, el)
                    refreshLines()
                }
                MotionEvent.ACTION_UP -> commit()
            }
            true
        }
        wm.addView(v, el)
        refreshLines()
    }

    private fun applyCrosshair(mk: Marker) {
        val cv = mk.centerView as? CenterCrossView ?: return
        val clp = mk.centerLp ?: return
        cv.style = mk.chStyle
        cv.color = mk.chColor
        val sz = dp(mk.chSize)
        val m = real()
        clp.width = sz; clp.height = sz
        clp.x = (m.widthPixels - sz) / 2; clp.y = (m.heightPixels - sz) / 2
        wm.updateViewLayout(cv, clp)
    }

    private fun duplicate(mk: Marker) {
        if (mk.type == "aim" || mk.type == "wheel") return
        val m = real()
        val off = dp(40)
        val n = addMarker(mk.type, mk.key, (cxOf(mk) + off).toFloat() / m.widthPixels,
            (cyOf(mk) + off).toFloat() / m.heightPixels, mk.lp.width)
        n.mode = mk.mode; n.turboHz = mk.turboHz; n.longMs = mk.longMs
        n.durMs = mk.durMs; n.skillSens = mk.skillSens; n.alpha = mk.alpha
        val src = mk.endLp
        val dst = n.endLp
        if (src != null && dst != null) {
            dst.x = src.x + off; dst.y = src.y + off
            n.endView?.let { wm.updateViewLayout(it, dst) }
        }
        refreshLabel(n); applyMarkerState(n); refreshLines()
        commit()
    }

    // ---------- undo / redo (snapshots of the whole layout) ----------
    private fun commit() {
        val snap = runCatching { buildJson().toString() }.getOrNull() ?: return
        if (histIdx >= 0 && history[histIdx] == snap) return
        while (history.size > histIdx + 1) history.removeAt(history.size - 1)
        history.add(snap)
        histIdx = history.size - 1
        if (history.size > 60) { history.removeAt(0); histIdx-- }
    }

    private fun undo() {
        if (histIdx > 0) { histIdx--; restore(history[histIdx]) } else toast("Nothing to undo")
    }

    private fun redo() {
        if (histIdx < history.size - 1) { histIdx++; restore(history[histIdx]) } else toast("Nothing to redo")
    }

    private fun restore(snap: String) {
        ArrayList(markers).forEach { removeMarker(it) }
        runCatching { loadFromRoot(JSONObject(snap)) }
        applyLock()
        refreshLines()
    }

    private fun reloadFromFile() {
        ArrayList(markers).forEach { removeMarker(it) }
        loadExisting()
        commit()
    }

    // ---------- add menu / more menu / grid / templates ----------
    private fun addMenu() {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        fun item(title: String, desc: String, f: () -> Unit) {
            col.addView(actionBtn(title, C_PANEL, C_TEXT) { dlg?.dismiss(); f(); commit() }, rowLp())
            col.addView(hint(desc))
        }
        item("Tap / key button", "Taps the screen. Modes: hold, tap, toggle, double, long press, turbo.") {
            addMarker("click", "Key_R", 0.5f, 0.5f)
        }
        item("Direction pad", "WASD or arrow keys move your character.") {
            if (markers.none { it.type == "wheel" }) addMarker("wheel", "", 0.17f, 0.72f, dp(140)) else toast("There is already a direction pad")
        }
        item("Aim (mouse look)", "The mouse moves the camera. Sensitivity, ADS, crosshair and more.") {
            if (markers.none { it.type == "aim" }) addMarker("aim", "", 0.75f, 0.5f, dp(56)) else toast("There is already an aim marker")
        }
        item("Swipe", "One key press swipes from the marker to the purple end point.") {
            addMarker("swipe", "Key_Q", 0.3f, 0.5f)
        }
        item("Skill-shot (MOBA)", "Hold the key, aim with the mouse inside the ring, release to cast.") {
            addMarker("skill", "Key_Q", 0.8f, 0.7f, dp(120))
        }
        dlg = overlayDialog(AlertDialog.Builder(this, theme()).setTitle("Add to layout")
            .setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Close", null))
    }

    private fun moreMenu() {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        col.addView(actionBtn("Grid & snap") { dlg?.dismiss(); gridDialog() }, rowLp())
        col.addView(actionBtn("Templates (FPS, MOBA, racing)") { dlg?.dismiss(); templateDialog() }, rowLp())
        col.addView(actionBtn("Reload layout from file") { dlg?.dismiss(); reloadFromFile(); toast("Reloaded") }, rowLp())
        col.addView(hint("Profiles (several layouts per game, import / export) are managed in the PC app: Profiles tab."))
        dlg = overlayDialog(AlertDialog.Builder(this, theme()).setTitle("More")
            .setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Close", null))
    }

    private fun gridDialog() {
        val col = dialogColumn()
        col.addView(switchRow("Snap to grid", gridOn) { gridOn = it; applyChrome(); save(true) })
        sliderRow(col, "Grid size", " dp", { gridDp }, { gridDp = it; gridView?.step = dp(it).toFloat(); save(true) }, 10, 100, 2)
        col.addView(hint("Markers snap to the grid while you drag them. The grid is only drawn in Edit mode."))
        overlayDialog(AlertDialog.Builder(this, theme()).setTitle("Grid & snap")
            .setView(ScrollView(this).apply { addView(col) }).setPositiveButton("Done", null))
    }

    private fun templateDialog() {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        col.addView(hint("A template replaces the current layout. Tap \u21B6 to get your old layout back."))
        val list = listOf(
            Triple<String, String, () -> JSONObject>("FPS / shooter",
                "Direction pad, aim, fire, ADS, jump, crouch, reload, lean, weapon swap on the wheel", { tplFps() }),
            Triple<String, String, () -> JSONObject>("MOBA",
                "Direction pad, 4 skill-shots, turbo attack, summoner spells, recall, items", { tplMoba() }),
            Triple<String, String, () -> JSONObject>("Racing",
                "Steer, brake, gas, nitro, handbrake, camera", { tplRacing() }))
        for ((name, desc, build) in list) {
            col.addView(actionBtn(name, C_ACCENT, C_ON_ACCENT) { dlg?.dismiss(); applyTemplate(build()) }, rowLp())
            col.addView(hint(desc))
        }
        dlg = overlayDialog(AlertDialog.Builder(this, theme()).setTitle("Templates")
            .setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Close", null))
    }

    private fun applyTemplate(root: JSONObject) {
        ArrayList(markers).forEach { removeMarker(it) }
        loadFromRoot(root)
        applyLock()
        commit()
    }

    private fun jp(x: Double, y: Double) = JSONObject().put("x", x).put("y", y)
    private fun nClick(key: String, x: Double, y: Double, mode: String = "hold", size: Int = 52) = JSONObject()
        .put("type", "KMT_CLICK").put("key", key).put("pos", jp(x, y)).put("switchMap", false).put("alpha", 0.6)
        .put("mode", mode).put("turboHz", 12).put("longMs", 600).put("size", size)
    private fun nSkill(key: String, x: Double, y: Double) = JSONObject()
        .put("type", "KMT_SKILL").put("key", key).put("pos", jp(x, y)).put("radius", 0.06).put("skillSens", 100).put("alpha", 0.6)
    private fun nWheel(keys: List<String>, x: Double, y: Double) = JSONObject()
        .put("type", "KMT_STEER_WHEEL").put("centerPos", jp(x, y))
        .put("upKey", keys[0]).put("leftKey", keys[1]).put("downKey", keys[2]).put("rightKey", keys[3])
        .put("leftOffset", 0.065).put("rightOffset", 0.065).put("upOffset", 0.14).put("downOffset", 0.14).put("alpha", 0.6)

    private fun tplRoot(nodes: List<JSONObject>, aim: Boolean): JSONObject {
        val arr = JSONArray()
        nodes.forEach { arr.put(it) }
        val mm = JSONObject().put("startPos", jp(0.75, 0.5)).put("speedRatio", JSONObject().put("x", 2.0).put("y", 2.0))
        if (aim) {
            mm.put("range", JSONObject().put("w", 0.4).put("h", 0.6)).put("reset", true).put("hideBox", false)
                .put("centerCrosshair", true).put("alpha", 0.6).put("adsKey", "Button_Right").put("adsMult", 60)
        }
        return JSONObject().put("switchKey", "Key_QuoteLeft").put("mouseMoveMap", mm).put("keyMapNodes", arr)
    }

    private val WASD = listOf("Key_W", "Key_A", "Key_S", "Key_D")

    private fun tplFps() = tplRoot(listOf(
        nWheel(WASD, 0.16, 0.70),
        nClick("Button_Left", 0.88, 0.66, "hold", 64), nClick("Button_Right", 0.93, 0.45, "hold"),
        nClick("Key_Space", 0.93, 0.82, "tap"), nClick("Key_C", 0.80, 0.84, "toggle"), nClick("Key_Z", 0.86, 0.93, "tap"),
        nClick("Key_R", 0.72, 0.80, "tap"), nClick("Key_Shift", 0.27, 0.54, "toggle"),
        nClick("Key_Q", 0.36, 0.10, "hold", 44), nClick("Key_E", 0.46, 0.10, "hold", 44),
        nClick("Key_F", 0.62, 0.62, "tap"), nClick("Wheel_Up", 0.55, 0.90, "tap", 44),
        nClick("Wheel_Down", 0.62, 0.90, "tap", 44), nClick("Key_Tab", 0.50, 0.05, "tap", 44)), true)

    private fun tplMoba() = tplRoot(listOf(
        nWheel(WASD, 0.14, 0.70),
        nSkill("Key_Q", 0.74, 0.66), nSkill("Key_W", 0.80, 0.50), nSkill("Key_E", 0.90, 0.44), nSkill("Key_R", 0.94, 0.66),
        nClick("Key_Space", 0.85, 0.86, "turbo", 64), nClick("Key_D", 0.64, 0.88, "tap"), nClick("Key_F", 0.70, 0.78, "tap"),
        nClick("Key_B", 0.50, 0.90, "long")) +
        (1..6).map { nClick("Key_$it", 0.34 + it * 0.04, 0.95, "tap", 40) }, false)

    private fun tplRacing() = tplRoot(listOf(
        nClick("Key_A", 0.10, 0.80, "hold", 72), nClick("Key_D", 0.24, 0.80, "hold", 72),
        nClick("Key_S", 0.74, 0.86, "hold", 64), nClick("Key_W", 0.90, 0.80, "hold", 72),
        nClick("Key_Space", 0.88, 0.52, "tap"), nClick("Key_Shift", 0.78, 0.62, "hold"),
        nClick("Key_C", 0.50, 0.10, "tap", 44)), false)

    // ---------- resize handle (direction pad: whole pad, aim: the range rectangle) ----------
    private fun addGrip(mk: Marker) {
        val sz = dp(34)
        val g = GripView(this)
        val gl = LP(sz, sz, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        gl.gravity = Gravity.TOP or Gravity.START
        mk.grip = g; mk.gripLp = gl
        var x0 = 0f; var y0 = 0f; var w0 = 0; var h0 = 0
        g.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY
                    if (mk.type == "aim") {
                        val m = real(); w0 = (mk.rangeW * m.widthPixels).toInt(); h0 = (mk.rangeH * m.heightPixels).toInt()
                    } else { w0 = mk.lp.width; h0 = w0 }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (mk.type == "aim") {
                        // the range is centred on the aim marker, so the corner moves half as far as the size
                        val m = real()
                        mk.rangeW = ((w0 + 2 * dx).toInt().coerceIn(dp(120), m.widthPixels)) / m.widthPixels.toFloat()
                        mk.rangeH = ((h0 + 2 * dy).toInt().coerceIn(dp(120), m.heightPixels)) / m.heightPixels.toFloat()
                        syncRange(mk)
                    } else {
                        val lo = if (mk.type == "skill") dp(60) else dp(80)
                        val hi = if (mk.type == "skill") dp(260) else dp(320)
                        setWheelSize(mk, (w0 + dx + dy).toInt().coerceIn(lo, hi))
                    }
                }
                MotionEvent.ACTION_UP -> commit()
            }
            true
        }
        wm.addView(g, gl)
        syncGrip(mk)
    }

    private fun syncGrip(mk: Marker) {
        val g = mk.grip ?: return
        val gl = mk.gripLp ?: return
        if (mk.type == "aim") {
            val rl = mk.rangeLp ?: return
            gl.x = rl.x + rl.width - gl.width / 2          // bottom-right corner of the range rectangle
            gl.y = rl.y + rl.height - gl.height / 2
        } else {
            val cx = mk.lp.x + mk.lp.width / 2
            val cy = mk.lp.y + mk.lp.height / 2
            val off = (mk.lp.width / 2 * 0.7071f).toInt()  // sits on the ring, bottom-right
            gl.x = cx + off - gl.width / 2
            gl.y = cy + off - gl.height / 2
        }
        wm.updateViewLayout(g, gl)
    }

    private fun removeMarker(mk: Marker) {
        runCatching { wm.removeView(mk.view) }
        mk.rangeView?.let { runCatching { wm.removeView(it) } }
        mk.centerView?.let { runCatching { wm.removeView(it) } }
        mk.grip?.let { runCatching { wm.removeView(it) } }
        mk.endView?.let { runCatching { wm.removeView(it) } }
        markers.remove(mk)
        refreshLines()
    }

    private fun syncRange(mk: Marker) {
        val rv = mk.rangeView ?: return
        val rl = mk.rangeLp ?: return
        val m = real()
        val cx = mk.lp.x + mk.lp.width / 2
        val cy = mk.lp.y + mk.lp.height / 2
        rl.width = (mk.rangeW * m.widthPixels).toInt().coerceAtLeast(dp(120))
        rl.height = (mk.rangeH * m.heightPixels).toInt().coerceAtLeast(dp(120))
        rl.x = cx - rl.width / 2
        rl.y = cy - rl.height / 2
        wm.updateViewLayout(rv, rl)
        syncGrip(mk)
    }

    private fun refreshWheel(mk: Marker) {
        val v = mk.view as? WheelView ?: return
        v.labels = mk.wheel.map { labelFor(it) }
        v.invalidate()
    }

    private fun setWheelSize(mk: Marker, px: Int) {
        val cx = mk.lp.x + mk.lp.width / 2
        val cy = mk.lp.y + mk.lp.height / 2
        mk.lp.width = px; mk.lp.height = px
        mk.lp.x = cx - px / 2; mk.lp.y = cy - px / 2
        wm.updateViewLayout(mk.view, mk.lp)
        syncGrip(mk)
    }

    // ---------- key picker ----------
    private val KEYS_MOUSE = listOf("LMB" to "Button_Left", "RMB" to "Button_Right", "MMB" to "Button_Middle",
        "M4" to "Button_Back", "M5" to "Button_Forward")
    private val KEYS_WHEEL = listOf("Roll \u2191" to "Wheel_Up", "Roll \u2193" to "Wheel_Down")
    private val KEYS_SPECIAL = listOf("Space" to "Key_Space", "Shift" to "Key_Shift", "Ctrl" to "Key_Control",
        "Alt" to "Key_Alt", "Tab" to "Key_Tab", "Esc" to "Key_Escape", "Enter" to "Key_Return",
        "\u2191" to "Key_Up", "\u2193" to "Key_Down", "\u2190" to "Key_Left", "\u2192" to "Key_Right")
    private val KEYS_LETTERS = ('A'..'Z').map { it.toString() to "Key_$it" }
    private val KEYS_NUMS = ('0'..'9').map { it.toString() to "Key_$it" }
    private val KEYS_F = (1..12).map { "F$it" to "Key_F$it" }
    private val ALL_KEYS = KEYS_MOUSE + KEYS_WHEEL + KEYS_SPECIAL + KEYS_LETTERS + KEYS_NUMS + KEYS_F

    private fun labelFor(code: String): String =
        ALL_KEYS.firstOrNull { it.second == code }?.first ?: code.removePrefix("Key_").removePrefix("Button_")

    private fun overlayDialog(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.window?.setType(LP.TYPE_APPLICATION_OVERLAY)
        d.window?.setBackgroundDrawable(rounded(0xFF1B212A.toInt(), 18, C_LINE))
        d.setOnDismissListener { commit() }
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(C_ACCENT)
        d.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFFFF8A80.toInt())
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(C_MUTED)
        return d
    }

    private fun theme() = android.R.style.Theme_Material_Dialog_Alert

    private fun keySection(title: String, items: List<Pair<String, String>>, onPick: (Pair<String, String>) -> Unit): View {
        val cell = dp(52)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title.uppercase(); textSize = 11f; setTextColor(C_ACCENT)
            setPadding(dp(4), dp(14), dp(4), dp(4)); setTypeface(typeface, Typeface.BOLD)
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        for (kv in items) {
            val b = Button(this).apply {
                text = kv.first; textSize = 12f; styleBtn(this)
                setPadding(2, 2, 2, 2); setOnClickListener { onPick(kv) }
            }
            val lp = android.widget.GridLayout.LayoutParams().apply { width = cell; height = cell; setMargins(dp(2), dp(2), dp(2), dp(2)) }
            grid.addView(b, lp)
        }
        box.addView(grid)
        return box
    }

    private fun pickKey(title: String, onDelete: (() -> Unit)? = null, deleteLabel: String = "Delete", onPick: (String) -> Unit) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        fun sec(t: String, l: List<Pair<String, String>>) =
            col.addView(keySection(t, l) { onPick(it.second); dlg?.dismiss() })
        sec("Mouse", KEYS_MOUSE); sec("Mouse wheel", KEYS_WHEEL); sec("Special", KEYS_SPECIAL); sec("Letters", KEYS_LETTERS)
        sec("Numbers", KEYS_NUMS); sec("Function", KEYS_F)
        val b = AlertDialog.Builder(this, theme()).setTitle(title)
            .setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Cancel", null)
        if (onDelete != null) b.setNeutralButton(deleteLabel) { _, _ -> onDelete() }
        dlg = overlayDialog(b)
    }

    // label on top, then   [-] ======o====== [+] [ value ]   - drag, tap -/+ for single steps, value is always visible
    private fun sliderRow(parent: LinearLayout, label: String, unit: String, getV: () -> Int, setV: (Int) -> Unit,
                          min: Int, max: Int, step: Int = 1) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        box.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(C_MUTED) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val value = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 14f; setTextColor(C_ACCENT); setTypeface(typeface, Typeface.BOLD)
            background = rounded(0xFF0F1318.toInt(), 8, C_LINE); minWidth = dp(78); setPadding(dp(6), dp(7), dp(6), dp(7))
        }
        val bar = SeekBar(this)
        val steps = ((max - min) / step).coerceAtLeast(1)
        val start = getV().coerceIn(min, max)
        bar.max = steps
        bar.progress = (start - min) / step
        value.text = "$start$unit"
        bar.progressTintList = ColorStateList.valueOf(C_ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(C_ACCENT)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = (min + p * step).coerceIn(min, max)
                value.text = "$v$unit"
                setV(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        fun nudge(d: Int) { bar.progress = (bar.progress + d).coerceIn(0, bar.max) }
        row.addView(actionBtn("\u2212") { nudge(-1) }, LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(actionBtn("+") { nudge(1) }, LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        box.addView(row)
        parent.addView(box)
    }

    private fun editMarker(mk: Marker) {
        val del = { removeMarker(mk) }
        when (mk.type) {
            "wheel" -> editWheel(mk, del)
            "aim" -> editAim(mk, del)
            "swipe" -> editSwipe(mk, del)
            "skill" -> editSkill(mk, del)
            else -> editTap(mk, del)
        }
    }

    // opacity slider (10..100 %), applied live to the marker behind the dialog
    private fun opacityRow(parent: LinearLayout, mk: Marker) =
        sliderRow(parent, "Opacity", " %", { Math.round(mk.alpha * 100) },
            { mk.alpha = it / 100f; applyMarkerState(mk) }, 10, 100, 1)

    private val MODES = listOf("hold" to "Hold", "tap" to "Tap", "toggle" to "Toggle",
        "double" to "Double", "long" to "Long", "turbo" to "Turbo")
    private val MODE_HINT = mapOf(
        "hold" to "Finger stays down while you hold the key.",
        "tap" to "One quick tap every time you press the key.",
        "toggle" to "Press once = finger down, press again = finger up (sprint, crouch).",
        "double" to "Two quick taps per key press.",
        "long" to "Finger stays down for the hold time below, then lifts.",
        "turbo" to "Taps over and over while the key is held (auto-fire).")

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(C_ACCENT); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun editTap(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        col.addView(actionBtn("Key:  " + labelFor(mk.key), C_ACCENT, C_ON_ACCENT) {
            dlg?.dismiss()
            pickKey("Pick the button for this marker") { code ->
                mk.key = code; refreshLabel(mk); editTap(mk, del)
            }
        }, rowLp())
        col.addView(sectionLabel("MODE"))
        for (r in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, 0) }
            for (c in 0 until 3) {
                val code = MODES[r * 3 + c].first
                val name = MODES[r * 3 + c].second
                val sel = mk.mode == code
                line.addView(actionBtn(name, if (sel) C_ACCENT else C_PANEL, if (sel) C_ON_ACCENT else C_TEXT) {
                    mk.mode = code; refreshLabel(mk); dlg?.dismiss(); editTap(mk, del)
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            col.addView(line)
        }
        col.addView(hint(MODE_HINT[mk.mode] ?: ""))
        if (mk.mode == "turbo") sliderRow(col, "Taps per second", " /s", { mk.turboHz }, { mk.turboHz = it }, 3, 30, 1)
        if (mk.mode == "long") sliderRow(col, "Hold time", " ms", { mk.longMs }, { mk.longMs = it }, 200, 2000, 50)
        sliderRow(col, "Button size", " dp", { (mk.lp.width / resources.displayMetrics.density).toInt() },
            { setWheelSize(mk, dp(it)) }, 30, 120, 2)
        opacityRow(col, mk)
        col.addView(actionBtn("Duplicate") { dlg?.dismiss(); duplicate(mk) }, rowLp())
        val b = AlertDialog.Builder(this, theme()).setTitle("Tap button")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun editSwipe(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        col.addView(actionBtn("Key:  " + labelFor(mk.key), C_ACCENT, C_ON_ACCENT) {
            dlg?.dismiss()
            pickKey("Pick the key that triggers the swipe") { code ->
                mk.key = code; refreshLabel(mk); editSwipe(mk, del)
            }
        }, rowLp())
        col.addView(hint("Drag the purple arrow dot to set where the swipe ends. Dragging the start moves both."))
        sliderRow(col, "Swipe duration", " ms", { mk.durMs }, { mk.durMs = it }, 80, 800, 10)
        opacityRow(col, mk)
        col.addView(actionBtn("Duplicate") { dlg?.dismiss(); duplicate(mk) }, rowLp())
        val b = AlertDialog.Builder(this, theme()).setTitle("Swipe")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun editSkill(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        col.addView(actionBtn("Key:  " + labelFor(mk.key), C_ACCENT, C_ON_ACCENT) {
            dlg?.dismiss()
            pickKey("Pick the skill key") { code ->
                mk.key = code; refreshLabel(mk); editSkill(mk, del)
            }
        }, rowLp())
        col.addView(hint("Hold the key, move the mouse to aim inside the ring, release to cast. " +
            "Drag the round handle to resize the ring."))
        sliderRow(col, "Ring size (aim range)", " dp", { (mk.lp.width / resources.displayMetrics.density).toInt() },
            { setWheelSize(mk, dp(it)) }, 60, 260, 5)
        sliderRow(col, "Aim sensitivity", " %", { mk.skillSens }, { mk.skillSens = it }, 20, 300, 5)
        opacityRow(col, mk)
        col.addView(actionBtn("Duplicate") { dlg?.dismiss(); duplicate(mk) }, rowLp())
        val b = AlertDialog.Builder(this, theme()).setTitle("Skill-shot")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun editWheel(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val col = dialogColumn()
        col.addView(TextView(this).apply { text = "PRESETS"; textSize = 11f; setTextColor(C_ACCENT); setTypeface(typeface, Typeface.BOLD) })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        val map = linkedMapOf(
            "WASD" to listOf("Key_W", "Key_A", "Key_S", "Key_D"),
            "Arrows" to listOf("Key_Up", "Key_Left", "Key_Down", "Key_Right"),
            "ESDF" to listOf("Key_E", "Key_S", "Key_D", "Key_F"),
            "IJKL" to listOf("Key_I", "Key_J", "Key_K", "Key_L"))
        for ((name, keys) in map) {
            presets.addView(actionBtn(name) {
                mk.wheel = keys.toMutableList(); refreshWheel(mk); dlg?.dismiss(); editWheel(mk, del)
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        col.addView(presets)
        col.addView(TextView(this).apply {
            text = "OR PICK EACH DIRECTION"; textSize = 11f; setTextColor(C_ACCENT)
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, 0)
        })
        val names = listOf("Up", "Left", "Down", "Right")
        for (r in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            for (c in 0 until 2) {
                val i = r * 2 + c
                line.addView(actionBtn(names[i] + ":  " + labelFor(mk.wheel[i])) {
                    dlg?.dismiss()
                    pickKey("Key for " + names[i]) { code -> mk.wheel[i] = code; refreshWheel(mk); editWheel(mk, del) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            col.addView(line)
        }
        sliderRow(col, "Size", " dp", { (mk.lp.width / resources.displayMetrics.density).toInt() },
            { setWheelSize(mk, dp(it)) }, 80, 320, 5)
        col.addView(hint("Tip: you can also drag the round handle on the pad to resize it."))
        opacityRow(col, mk)
        val b = AlertDialog.Builder(this, theme()).setTitle("Direction keys")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun editAim(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val m = real()
        val col = dialogColumn()
        col.addView(hint("On the PC, press ` to switch between cursor and aim mode. " +
            "Drag the round handle on the range box to resize it."))
        // every change is written to keymap.json right away (the PC picks it up live), no Save/Lock needed
        col.addView(sectionLabel("SPEED"))
        sliderRow(col, "X-axis sensitivity", "", { mk.sensX }, { mk.sensX = it; save(true) }, 5, 200, 1)
        sliderRow(col, "Y-axis sensitivity", "", { mk.sensY }, { mk.sensY = it; save(true) }, 5, 200, 1)
        sliderRow(col, "Acceleration (faster flicks turn further)", " %", { mk.accel }, { mk.accel = it; save(true) }, 0, 100, 5)
        sliderRow(col, "Dead zone (ignore tiny jitter)", "", { mk.deadZone }, { mk.deadZone = it; save(true) }, 0, 4, 1)
        col.addView(switchRow("Invert Y axis", mk.invertY) { mk.invertY = it; save(true) })

        col.addView(sectionLabel("ADS / SCOPE"))
        col.addView(actionBtn("ADS key:  " + (if (mk.adsKey.isEmpty()) "none" else labelFor(mk.adsKey))) {
            dlg?.dismiss()
            pickKey("Hold this key to slow the camera down", { mk.adsKey = ""; save(true); editAim(mk, del) }, "Clear") { code ->
                mk.adsKey = code; save(true); editAim(mk, del)
            }
        }, rowLp())
        sliderRow(col, "Sensitivity while ADS key is held", " %", { mk.adsMult }, { mk.adsMult = it; save(true) }, 10, 200, 5)

        col.addView(sectionLabel("RANGE"))
        sliderRow(col, "X-axis range", " px", { (mk.rangeW * m.widthPixels).toInt() },
            { mk.rangeW = it / m.widthPixels.toFloat(); syncRange(mk); save(true) }, dp(120), m.widthPixels, 10)
        sliderRow(col, "Y-axis range", " px", { (mk.rangeH * m.heightPixels).toInt() },
            { mk.rangeH = it / m.heightPixels.toFloat(); syncRange(mk); save(true) }, dp(120), m.heightPixels, 10)
        col.addView(switchRow("Reset", mk.reset) { mk.reset = it; save(true) })
        col.addView(hint("If enabled, turning never stops: when the mouse goes out of range the PC hands the camera over to a " +
            "fresh finger at the center (see AIM_HANDOFF in KeymapControl.py)."))
        col.addView(switchRow("Hide range box while playing", mk.hideBox) { mk.hideBox = it; applyMarkerState(mk); save(true) })
        col.addView(hint("The box stays visible in Edit mode so you can still resize it."))

        col.addView(sectionLabel("CROSSHAIR"))
        col.addView(switchRow("Crosshair at screen center", mk.centerCross) { mk.centerCross = it; applyMarkerState(mk); save(true) })
        val styles = listOf("cross" to "Cross", "dot" to "Dot", "circle" to "Ring", "crossdot" to "Cross+dot")
        val srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        for ((code, name) in styles) {
            val sel = mk.chStyle == code
            srow.addView(actionBtn(name, if (sel) C_ACCENT else C_PANEL, if (sel) C_ON_ACCENT else C_TEXT) {
                mk.chStyle = code; applyCrosshair(mk); save(true); dlg?.dismiss(); editAim(mk, del)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        col.addView(srow)
        val crow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val palette = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFF5252.toInt(), 0xFF69F0AE.toInt(),
            0xFF40C4FF.toInt(), 0xFFFFEB3B.toInt(), 0xFFE040FB.toInt())
        for (cc in palette) {
            val sel = mk.chColor == cc
            val sw = Button(this).apply {
                stateListAnimator = null
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(cc)
                    setStroke(dp(if (sel) 3 else 1), if (sel) C_ACCENT else C_LINE)
                }
                setOnClickListener { mk.chColor = cc; applyCrosshair(mk); save(true); dlg?.dismiss(); editAim(mk, del) }
            }
            crow.addView(sw, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
        col.addView(crow)
        sliderRow(col, "Crosshair size", " dp", { mk.chSize }, { mk.chSize = it; applyCrosshair(mk); save(true) }, 16, 120, 2)
        col.addView(hint("A fixed crosshair in the middle of the screen. The aim pad stays where it is, so you can still see " +
            "where the camera finger touches and moves."))
        opacityRow(col, mk)
        val b = AlertDialog.Builder(this, theme()).setTitle("Aim settings")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    // ---------- save / load ----------
    private fun fx(mk: Marker, w: Int) = (mk.lp.x + mk.lp.width / 2f) / w
    private fun fy(mk: Marker, h: Int) = (mk.lp.y + mk.lp.height / 2f) / h
    private fun r4(v: Number) = Math.round(v.toDouble() * 10000) / 10000.0
    private fun pt(x: Float, y: Float) = JSONObject().put("x", r4(x)).put("y", r4(y))

    private fun file() = File(getExternalFilesDir(null), "keymap.json")

    private fun buildJson(): JSONObject {
        val m = real()
        val dens = resources.displayMetrics.density
        val nodes = JSONArray()
        var aim: Marker? = null
        for (mk in markers) {
            val x = fx(mk, m.widthPixels)
            val y = fy(mk, m.heightPixels)
            when (mk.type) {
                "wheel" -> {
                    val rad = mk.lp.width / 2f
                    val ox = r4(rad / m.widthPixels)
                    val oy = r4(rad / m.heightPixels)
                    nodes.put(JSONObject().put("type", "KMT_STEER_WHEEL").put("centerPos", pt(x, y))
                        .put("upKey", mk.wheel[0]).put("leftKey", mk.wheel[1])
                        .put("downKey", mk.wheel[2]).put("rightKey", mk.wheel[3])
                        .put("leftOffset", ox).put("rightOffset", ox).put("upOffset", oy).put("downOffset", oy)
                        .put("alpha", r4(mk.alpha)))
                }
                "aim" -> aim = mk
                "swipe" -> {
                    val el = mk.endLp
                    val ex = if (el != null) (el.x + el.width / 2f) / m.widthPixels else x
                    val ey = if (el != null) (el.y + el.height / 2f) / m.heightPixels else y
                    nodes.put(JSONObject().put("type", "KMT_SWIPE").put("key", mk.key)
                        .put("pos", pt(x, y)).put("endPos", pt(ex, ey)).put("durationMs", mk.durMs)
                        .put("alpha", r4(mk.alpha)))
                }
                "skill" -> nodes.put(JSONObject().put("type", "KMT_SKILL").put("key", mk.key)
                    .put("pos", pt(x, y)).put("radius", r4(mk.lp.width / 2f / m.widthPixels))
                    .put("skillSens", mk.skillSens).put("alpha", r4(mk.alpha)))
                else -> nodes.put(JSONObject().put("type", "KMT_CLICK").put("key", mk.key)
                    .put("pos", pt(x, y)).put("switchMap", false).put("alpha", r4(mk.alpha))
                    .put("mode", mk.mode).put("turboHz", mk.turboHz).put("longMs", mk.longMs)
                    .put("size", Math.round(mk.lp.width / dens)))
            }
        }
        val mm = JSONObject()
        if (aim != null) {
            mm.put("startPos", pt(fx(aim, m.widthPixels), fy(aim, m.heightPixels)))
                .put("speedRatio", JSONObject().put("x", r4(aim.sensX / 25.0)).put("y", r4(aim.sensY / 25.0)))
                .put("range", JSONObject().put("w", r4(aim.rangeW)).put("h", r4(aim.rangeH)))
                .put("reset", aim.reset).put("hideBox", aim.hideBox).put("centerCrosshair", aim.centerCross)
                .put("alpha", r4(aim.alpha))
                .put("invertY", aim.invertY).put("deadZone", aim.deadZone).put("accel", aim.accel)
                .put("adsKey", aim.adsKey).put("adsMult", aim.adsMult)
                .put("crosshair", JSONObject().put("style", aim.chStyle).put("size", aim.chSize).put("color", aim.chColor))
        } else {
            mm.put("startPos", pt(0.5f, 0.5f))
                .put("speedRatio", JSONObject().put("x", 2.0).put("y", 2.0))
        }
        return JSONObject().put("version", 2).put("switchKey", "Key_QuoteLeft").put("mouseMoveMap", mm)
            .put("keyMapNodes", nodes).put("hideButtons", hideAll)
            .put("grid", JSONObject().put("on", gridOn).put("size", gridDp))
    }

    private fun save(quiet: Boolean = false) {
        file().writeText(buildJson().toString(2))
        if (!quiet) Toast.makeText(this, "Saved ${file().absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun loadExisting() {
        val f = file(); if (!f.exists()) return
        runCatching { loadFromRoot(JSONObject(f.readText())) }
    }

    // builds all markers from a keymap JSON (file on the phone, undo snapshot or template)
    private fun loadFromRoot(root: JSONObject) {
        if (root.has("hideButtons")) {
            hideAll = root.optBoolean("hideButtons", false)
            hideAllButton?.text = if (hideAll) "Show btns" else "Hide btns"
        }
        root.optJSONObject("grid")?.let { g ->
            gridOn = g.optBoolean("on", false)
            gridDp = g.optInt("size", 24).coerceIn(8, 120)
        }
        gridView?.step = dp(gridDp).toFloat()
        val m = real()
        val arr = root.optJSONArray("keyMapNodes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i)
            val alpha = n.optDouble("alpha", 0.6).toFloat().coerceIn(0.1f, 1f)
            when (n.getString("type")) {
                "KMT_STEER_WHEEL" -> {
                    val c = n.getJSONObject("centerPos")
                    val rad = n.optDouble("rightOffset", 0.07) * m.widthPixels
                    val sz = (rad * 2).toInt().coerceIn(dp(80), dp(320))
                    val mk = addMarker("wheel", "", c.getDouble("x").toFloat(), c.getDouble("y").toFloat(), sz)
                    mk.wheel = mutableListOf(n.getString("upKey"), n.getString("leftKey"),
                        n.getString("downKey"), n.getString("rightKey"))
                    mk.alpha = alpha
                    refreshWheel(mk); applyMarkerState(mk)
                }
                "KMT_CLICK" -> {
                    val p = n.getJSONObject("pos")
                    val sz = dp(n.optInt("size", 56).coerceIn(30, 120))
                    val mk = addMarker("click", n.getString("key"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), sz)
                    mk.mode = n.optString("mode", "hold")
                    mk.turboHz = n.optInt("turboHz", 12)
                    mk.longMs = n.optInt("longMs", 600)
                    mk.alpha = alpha
                    refreshLabel(mk); applyMarkerState(mk)
                }
                "KMT_SWIPE" -> {
                    val p = n.getJSONObject("pos")
                    val mk = addMarker("swipe", n.getString("key"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
                    mk.durMs = n.optInt("durationMs", 200)
                    mk.alpha = alpha
                    n.optJSONObject("endPos")?.let { e ->
                        val el = mk.endLp
                        if (el != null) {
                            el.x = (e.getDouble("x") * m.widthPixels - el.width / 2f).toInt()
                            el.y = (e.getDouble("y") * m.heightPixels - el.height / 2f).toInt()
                            mk.endView?.let { wm.updateViewLayout(it, el) }
                        }
                    }
                    refreshLabel(mk); applyMarkerState(mk); refreshLines()
                }
                "KMT_SKILL" -> {
                    val p = n.getJSONObject("pos")
                    val sz = (n.optDouble("radius", 0.06) * m.widthPixels * 2).toInt().coerceIn(dp(60), dp(260))
                    val mk = addMarker("skill", n.getString("key"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), sz)
                    mk.skillSens = n.optInt("skillSens", 100)
                    mk.alpha = alpha
                    refreshLabel(mk); applyMarkerState(mk)
                }
            }
        }
        val mm = root.optJSONObject("mouseMoveMap")
        if (mm != null && mm.has("range")) {
            val sp = mm.getJSONObject("startPos")
            val rg = mm.getJSONObject("range")
            val sr = mm.optJSONObject("speedRatio")
            val mk = addMarker("aim", "", sp.getDouble("x").toFloat(), sp.getDouble("y").toFloat(), dp(56))
            mk.rangeW = rg.getDouble("w").toFloat()
            mk.rangeH = rg.getDouble("h").toFloat()
            mk.sensX = ((sr?.optDouble("x", 2.0) ?: 2.0) * 25).toInt()
            mk.sensY = ((sr?.optDouble("y", 2.0) ?: 2.0) * 25).toInt()
            mk.reset = mm.optBoolean("reset", true)
            mk.hideBox = mm.optBoolean("hideBox", false)
            mk.centerCross = mm.optBoolean("centerCrosshair", true)
            mk.alpha = mm.optDouble("alpha", 0.6).toFloat().coerceIn(0.1f, 1f)
            mk.invertY = mm.optBoolean("invertY", false)
            mk.deadZone = mm.optInt("deadZone", 0)
            mk.accel = mm.optInt("accel", 0)
            mk.adsKey = mm.optString("adsKey", "")
            mk.adsMult = mm.optInt("adsMult", 60)
            mm.optJSONObject("crosshair")?.let { ch ->
                mk.chStyle = ch.optString("style", "cross")
                mk.chSize = ch.optInt("size", 44)
                mk.chColor = ch.optInt("color", 0xFFFFFFFF.toInt())
            }
            syncRange(mk); applyCrosshair(mk); applyMarkerState(mk)
        }
        applyChrome()
        refreshLines()
    }
}
