package com.winzie.keymapoverlay

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // type: "click", "wheel" (direction pad) or "aim" (mouse look)
    inner class Marker(val type: String, var key: String, val view: View, val lp: LP,
                       var wheel: MutableList<String> = mutableListOf("Key_W", "Key_A", "Key_S", "Key_D")) {
        var sensX = 50
        var sensY = 50
        var rangeW = 0.4f
        var rangeH = 0.6f
        var reset = true
        var alpha = 0.6f               // button opacity, 0.1 .. 1.0
        var rangeView: View? = null
        var rangeLp: LP? = null
        var grip: View? = null         // drag handle used to resize the direction pad / aim range
        var gripLp: LP? = null
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
        showPanel()
        loadExisting()
        makeCursor()
        startCursorServer()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            "RELOAD" -> { ArrayList(markers).forEach { removeMarker(it) }; loadExisting() }
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
        runCatching { cursorServer?.close() }
        super.onDestroy()
    }

    private fun real(): DisplayMetrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    private fun baseFlags() = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS or LP.FLAG_LAYOUT_IN_SCREEN

    // ---------- toolbar with hide / show bar ----------
    private fun showPanel() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC000000.toInt()) }
        fun btn(t: String, f: (Button) -> Unit): Button = Button(this).apply {
            text = t; textSize = 11f; setAllCaps(false); minWidth = 0; minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0); setOnClickListener { f(this) }
        }.also { row.addView(it, LinearLayout.LayoutParams(-2, dp(40))) }
        btn("+Tap") { addMarker("click", "Key_R", 0.5f, 0.5f) }
        btn("+Dir") { if (markers.none { it.type == "wheel" }) addMarker("wheel", "", 0.17f, 0.72f, dp(140)) }
        btn("+Aim") { if (markers.none { it.type == "aim" }) addMarker("aim", "", 0.75f, 0.5f, dp(56)) }
        btn("Save") { save() }
        lockButton = btn("Lock") { setLocked(!locked) }
        btn("Hide") { setPanelHidden(true) }
        btn("X") { stopSelf() }
        val lp = LP(-2, -2, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 0; lp.y = 0
        wm.addView(row, lp); panel = row

        // small bar shown while the toolbar is hidden: tap it to bring the menu back
        val bar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(0xDDFFFFFF.toInt()); setStroke(dp(1), 0x99000000.toInt())
            }
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
        lockButton?.text = if (on) "Edit" else "Lock"
        applyLock()
    }

    private fun applyLock() = markers.forEach { applyMarkerState(it) }

    // lock state + opacity for one marker (also used right after a marker is created, so a RELOAD while
    // locked never leaves touchable markers that would swallow the injected touches)
    private fun applyMarkerState(mk: Marker) {
        val flags = if (locked) baseFlags() or LP.FLAG_NOT_TOUCHABLE else baseFlags()
        mk.lp.flags = flags
        mk.view.alpha = mk.alpha
        wm.updateViewLayout(mk.view, mk.lp)
        mk.rangeView?.alpha = if (locked) mk.alpha * 0.4f else mk.alpha
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
    }

    // ---------- markers ----------
    private fun addMarker(type: String, key: String, fx: Float, fy: Float, sizePx: Int = dp(56)): Marker {
        val m = real()
        val view: View = when (type) {
            "wheel" -> WheelView(this)
            "aim" -> AimView(this)
            else -> TextView(this).apply {
                text = labelFor(key)
                setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 12f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE53935.toInt()); setStroke(3, Color.WHITE) // opaque: the opacity slider does the fading
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
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx) > 10 || Math.abs(e.rawY - dy) > 10) moved = true
                    if (moved) {
                        lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt()
                        wm.updateViewLayout(view, lp)
                        if (mk.type == "aim") syncRange(mk) else syncGrip(mk)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) editMarker(mk)
            }
            true
        }
        wm.addView(view, lp)
        markers.add(mk)
        if (type == "wheel") refreshWheel(mk)
        if (type == "wheel" || type == "aim") addGrip(mk)
        if (type == "aim") syncRange(mk)
        applyMarkerState(mk)
        return mk
    }

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
                        setWheelSize(mk, (w0 + dx + dy).toInt().coerceIn(dp(80), dp(320)))
                    }
                }
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
        mk.grip?.let { runCatching { wm.removeView(it) } }
        markers.remove(mk)
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
    private val KEYS_SPECIAL = listOf("Space" to "Key_Space", "Shift" to "Key_Shift", "Ctrl" to "Key_Control",
        "Alt" to "Key_Alt", "Tab" to "Key_Tab", "Esc" to "Key_Escape", "Enter" to "Key_Return",
        "\u2191" to "Key_Up", "\u2193" to "Key_Down", "\u2190" to "Key_Left", "\u2192" to "Key_Right")
    private val KEYS_LETTERS = ('A'..'Z').map { it.toString() to "Key_$it" }
    private val KEYS_NUMS = ('0'..'9').map { it.toString() to "Key_$it" }
    private val KEYS_F = (1..12).map { "F$it" to "Key_F$it" }
    private val ALL_KEYS = KEYS_MOUSE + KEYS_SPECIAL + KEYS_LETTERS + KEYS_NUMS + KEYS_F

    private fun labelFor(code: String): String =
        ALL_KEYS.firstOrNull { it.second == code }?.first ?: code.removePrefix("Key_").removePrefix("Button_")

    private fun overlayDialog(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.window?.setType(LP.TYPE_APPLICATION_OVERLAY)
        d.show()
        return d
    }

    private fun theme() = android.R.style.Theme_Material_Light_Dialog_Alert

    private fun keySection(title: String, items: List<Pair<String, String>>, onPick: (Pair<String, String>) -> Unit): View {
        val cell = dp(58)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title; setPadding(8, 20, 8, 4); setTypeface(typeface, Typeface.BOLD)
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        for (kv in items) {
            val b = Button(this).apply {
                text = kv.first; textSize = 12f; setAllCaps(false); minimumWidth = 0; minWidth = 0
                setPadding(2, 2, 2, 2); setOnClickListener { onPick(kv) }
            }
            val lp = android.widget.GridLayout.LayoutParams().apply { width = cell; height = cell }
            grid.addView(b, lp)
        }
        box.addView(grid)
        return box
    }

    private fun pickKey(title: String, onDelete: (() -> Unit)? = null, onPick: (String) -> Unit) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        fun sec(t: String, l: List<Pair<String, String>>) =
            col.addView(keySection(t, l) { onPick(it.second); dlg?.dismiss() })
        sec("Mouse", KEYS_MOUSE); sec("Special", KEYS_SPECIAL); sec("Letters", KEYS_LETTERS)
        sec("Numbers", KEYS_NUMS); sec("Function", KEYS_F)
        val b = AlertDialog.Builder(this, theme()).setTitle(title)
            .setView(ScrollView(this).apply { addView(col) }).setNegativeButton("Cancel", null)
        if (onDelete != null) b.setNeutralButton("Delete") { _, _ -> onDelete() }
        dlg = overlayDialog(b)
    }

    private fun stepper(parent: LinearLayout, label: String, getV: () -> Int, setV: (Int) -> Unit,
                        step: Int, min: Int, max: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(this).apply { text = getV().toString(); gravity = Gravity.CENTER; minWidth = dp(60) }
        fun bump(d: Int) { setV((getV() + d * step).coerceIn(min, max)); value.text = getV().toString() }
        row.addView(Button(this).apply {
            text = "-"; minWidth = 0; minimumWidth = 0; setOnClickListener { bump(-1) }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        row.addView(value)
        row.addView(Button(this).apply {
            text = "+"; minWidth = 0; minimumWidth = 0; setOnClickListener { bump(1) }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        parent.addView(row)
    }

    private fun editMarker(mk: Marker) {
        val del = { removeMarker(mk) }
        when (mk.type) {
            "wheel" -> editWheel(mk, del)
            "aim" -> editAim(mk, del)
            else -> editTap(mk, del)
        }
    }

    // opacity slider (10..100 %), applied live to the marker behind the dialog
    private fun opacityRow(parent: LinearLayout, mk: Marker) {
        val label = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        fun show() { label.text = "Opacity: " + Math.round(mk.alpha * 100) + "%" }
        show()
        parent.addView(label)
        parent.addView(SeekBar(this).apply {
            max = 90
            progress = Math.round(mk.alpha * 100) - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    mk.alpha = (p + 10) / 100f
                    show()
                    applyMarkerState(mk)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun editTap(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        col.addView(Button(this).apply {
            text = "Key: " + labelFor(mk.key); setAllCaps(false)
            setOnClickListener {
                dlg?.dismiss()
                pickKey("Pick the button for this marker") { code ->
                    mk.key = code; (mk.view as TextView).text = labelFor(code); editTap(mk, del)
                }
            }
        })
        opacityRow(col, mk)
        val b = AlertDialog.Builder(this, theme()).setTitle("Tap button")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun editWheel(mk: Marker, del: () -> Unit) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        col.addView(TextView(this).apply { text = "Presets" })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val map = linkedMapOf(
            "WASD" to listOf("Key_W", "Key_A", "Key_S", "Key_D"),
            "Arrows" to listOf("Key_Up", "Key_Left", "Key_Down", "Key_Right"),
            "ESDF" to listOf("Key_E", "Key_S", "Key_D", "Key_F"),
            "IJKL" to listOf("Key_I", "Key_J", "Key_K", "Key_L"))
        for ((name, keys) in map) {
            presets.addView(Button(this).apply {
                text = name; textSize = 12f; setAllCaps(false)
                setOnClickListener { mk.wheel = keys.toMutableList(); refreshWheel(mk); dlg?.dismiss(); editWheel(mk, del) }
            })
        }
        col.addView(presets)
        col.addView(TextView(this).apply { text = "Or pick each direction"; setPadding(0, 16, 0, 0) })
        val names = listOf("Up", "Left", "Down", "Right")
        for (i in 0 until 4) {
            col.addView(Button(this).apply {
                text = names[i] + ": " + labelFor(mk.wheel[i]); setAllCaps(false)
                setOnClickListener {
                    dlg?.dismiss()
                    pickKey("Key for " + names[i]) { code -> mk.wheel[i] = code; refreshWheel(mk); editWheel(mk, del) }
                }
            })
        }
        stepper(col, "Size (dp)", { (mk.lp.width / resources.displayMetrics.density).toInt() },
            { setWheelSize(mk, dp(it)) }, 10, 80, 320)
        col.addView(TextView(this).apply {
            text = "Tip: you can also drag the round handle on the pad to resize it."
            textSize = 11f
        })
        opacityRow(col, mk)
        val b = AlertDialog.Builder(this, theme()).setTitle("Direction keys")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun editAim(mk: Marker, del: () -> Unit) {
        val m = real()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        col.addView(TextView(this).apply {
            text = "On the PC, press ` to switch between cursor and aim mode. " +
                "Drag the round handle on the range box to resize it."
            textSize = 11f; setPadding(0, 0, 0, dp(8))
        })
        stepper(col, "X-axis sensitivity", { mk.sensX }, { mk.sensX = it }, 5, 5, 200)
        stepper(col, "Y-axis sensitivity", { mk.sensY }, { mk.sensY = it }, 5, 5, 200)
        stepper(col, "X-axis range (px)", { (mk.rangeW * m.widthPixels).toInt() },
            { mk.rangeW = it / m.widthPixels.toFloat(); syncRange(mk) }, 20, dp(120), m.widthPixels)
        stepper(col, "Y-axis range (px)", { (mk.rangeH * m.heightPixels).toInt() },
            { mk.rangeH = it / m.heightPixels.toFloat(); syncRange(mk) }, 20, dp(120), m.heightPixels)
        col.addView(android.widget.Switch(this).apply {
            text = "Reset"; isChecked = mk.reset; setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, c -> mk.reset = c }
        })
        col.addView(TextView(this).apply {
            text = "If enabled, the camera finger lifts and restarts at the center when the mouse moves out of range."
            textSize = 11f
        })
        opacityRow(col, mk)
        val b = AlertDialog.Builder(this, theme()).setTitle("Aim settings")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        overlayDialog(b)
    }

    // ---------- save / load ----------
    private fun fx(mk: Marker, w: Int) = (mk.lp.x + mk.lp.width / 2f) / w
    private fun fy(mk: Marker, h: Int) = (mk.lp.y + mk.lp.height / 2f) / h
    private fun r4(v: Number) = Math.round(v.toDouble() * 10000) / 10000.0
    private fun pt(x: Float, y: Float) = JSONObject().put("x", r4(x)).put("y", r4(y))

    private fun file() = File(getExternalFilesDir(null), "keymap.json")

    private fun save() {
        val m = real()
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
                else -> nodes.put(JSONObject().put("type", "KMT_CLICK").put("key", mk.key)
                    .put("pos", pt(x, y)).put("switchMap", false).put("alpha", r4(mk.alpha)))
            }
        }
        val mm = JSONObject()
        if (aim != null) {
            mm.put("startPos", pt(fx(aim, m.widthPixels), fy(aim, m.heightPixels)))
                .put("speedRatio", JSONObject().put("x", r4(aim.sensX / 25.0)).put("y", r4(aim.sensY / 25.0)))
                .put("range", JSONObject().put("w", r4(aim.rangeW)).put("h", r4(aim.rangeH)))
                .put("reset", aim.reset).put("alpha", r4(aim.alpha))
        } else {
            mm.put("startPos", pt(0.5f, 0.5f))
                .put("speedRatio", JSONObject().put("x", 2.0).put("y", 2.0))
        }
        val root = JSONObject().put("switchKey", "Key_QuoteLeft").put("mouseMoveMap", mm).put("keyMapNodes", nodes)
        file().writeText(root.toString(2))
        Toast.makeText(this, "Saved ${file().absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun loadExisting() {
        val f = file(); if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            val m = real()
            val arr = root.getJSONArray("keyMapNodes")
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                if (n.getString("type") == "KMT_STEER_WHEEL") {
                    val c = n.getJSONObject("centerPos")
                    val rad = n.optDouble("rightOffset", 0.07) * m.widthPixels
                    val sz = (rad * 2).toInt().coerceIn(dp(80), dp(320))
                    val mk = addMarker("wheel", "", c.getDouble("x").toFloat(), c.getDouble("y").toFloat(), sz)
                    mk.wheel = mutableListOf(n.getString("upKey"), n.getString("leftKey"),
                        n.getString("downKey"), n.getString("rightKey"))
                    mk.alpha = n.optDouble("alpha", 0.6).toFloat().coerceIn(0.1f, 1f)
                    refreshWheel(mk); applyMarkerState(mk)
                } else if (n.getString("type") == "KMT_CLICK") {
                    val p = n.getJSONObject("pos")
                    val mk = addMarker("click", n.getString("key"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
                    mk.alpha = n.optDouble("alpha", 0.6).toFloat().coerceIn(0.1f, 1f)
                    applyMarkerState(mk)
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
                mk.alpha = mm.optDouble("alpha", 0.6).toFloat().coerceIn(0.1f, 1f)
                syncRange(mk); applyMarkerState(mk)
            }
        }
    }
}
