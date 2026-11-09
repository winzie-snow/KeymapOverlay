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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val markers = mutableListOf<Marker>()
    private var panel: View? = null
    private var locked = false
    private val size by lazy { (56 * resources.displayMetrics.density).toInt() }

    // type: "click" or "wheel"; for wheel, keys = W,A,S,D
    inner class Marker(val type: String, var key: String, val view: TextView, val lp: LP,
                       var wheel: MutableList<String> = mutableListOf("Key_W", "Key_A", "Key_S", "Key_D"))

    // ---- simulated mouse cursor, driven by the PC over a local socket (adb forward) ----
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

    private val ui = Handler(Looper.getMainLooper())
    private var cursorView: CursorView? = null
    private var cursorLp: LP? = null
    private var cursorServer: LocalServerSocket? = null
    @Volatile private var curX = 0f
    @Volatile private var curY = 0f
    @Volatile private var curShow = false
    @Volatile private var curPending = false

    private fun makeCursor() {
        val sz = (30 * resources.displayMetrics.density).toInt()
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
            // re-add so the cursor sits above every marker
            runCatching { wm.removeView(v) }
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
            "RELOAD" -> { markers.forEach { runCatching { wm.removeView(it.view) } }; markers.clear(); loadExisting() }
            "LOCK" -> { locked = true; applyLock() }
            "EDIT" -> { locked = false; applyLock() }
            "STOP" -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        markers.forEach { runCatching { wm.removeView(it.view) } }
        panel?.let { runCatching { wm.removeView(it) } }
        cursorView?.let { runCatching { wm.removeView(it) } }
        runCatching { cursorServer?.close() }
        super.onDestroy()
    }

    private fun real(): DisplayMetrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    private fun baseFlags() = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS or LP.FLAG_LAYOUT_IN_SCREEN

    private fun showPanel() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xCC000000.toInt()) }
        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
            text = t; textSize = 11f; setOnClickListener { f(this) }
        }.also { row.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        btn("+Tap") { addMarker("click", "Key_R", 0.5f, 0.5f) }
        btn("+Wheel") { if (markers.none { it.type == "wheel" }) addMarker("wheel", "WASD", 0.17f, 0.72f) }
        btn("Save") { save() }
        btn("Lock") { b -> locked = !locked; b.text = if (locked) "Edit" else "Lock"; applyLock() }
        btn("X") { stopSelf() }
        val lp = LP(-2, -2, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 0; lp.y = 0
        wm.addView(row, lp); panel = row
    }

    private fun applyLock() {
        markers.forEach {
            it.lp.flags = if (locked) baseFlags() or LP.FLAG_NOT_TOUCHABLE else baseFlags()
            it.view.alpha = if (locked) 0.35f else 0.85f
            wm.updateViewLayout(it.view, it.lp)
        }
    }

    private fun addMarker(type: String, key: String, fx: Float, fy: Float): Marker {
        val m = real()
        val tv = TextView(this).apply {
            text = if (type == "wheel") "MOVE" else labelFor(key)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 12f; alpha = 0.85f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (type == "wheel") 0xAA2979FF.toInt() else 0xAAE53935.toInt())
                setStroke(3, Color.WHITE)
            }
        }
        val lp = LP(size, size, LP.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (fx * m.widthPixels - size / 2).toInt()
        lp.y = (fy * m.heightPixels - size / 2).toInt()
        val mk = Marker(type, key, tv, lp)
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx) > 10 || Math.abs(e.rawY - dy) > 10) moved = true
                    if (moved) { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) editMarker(mk)
            }
            true
        }
        wm.addView(tv, lp); markers.add(mk)
        return mk
    }

    // ---- key picker ----
    private val KEYS_MOUSE = listOf("LMB" to "Button_Left", "RMB" to "Button_Right", "MMB" to "Button_Middle")
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
        val cell = (58 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title; setPadding(8, 20, 8, 4); setTypeface(typeface, android.graphics.Typeface.BOLD)
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

    private fun editMarker(mk: Marker) {
        val del = { wm.removeView(mk.view); markers.remove(mk); Unit }
        if (mk.type == "wheel") { editWheel(mk, del); return }
        pickKey("Pick the button for this marker", del) { code ->
            mk.key = code; mk.view.text = labelFor(code)
        }
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
                setOnClickListener { mk.wheel = keys.toMutableList(); dlg?.dismiss(); editWheel(mk, del) }
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
                    pickKey("Key for " + names[i]) { code -> mk.wheel[i] = code; editWheel(mk, del) }
                }
            })
        }
        val b = AlertDialog.Builder(this, theme()).setTitle("Movement keys")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null).setNeutralButton("Delete") { _, _ -> del() }
        dlg = overlayDialog(b)
    }

    private fun fx(mk: Marker, w: Int) = ((mk.lp.x + size / 2f) / w)
    private fun fy(mk: Marker, h: Int) = ((mk.lp.y + size / 2f) / h)
    private fun pt(x: Float, y: Float) = JSONObject().put("x", "%.4f".format(x).toDouble()).put("y", "%.4f".format(y).toDouble())

    private fun file() = File(getExternalFilesDir(null), "keymap.json")

    private fun save() {
        val m = real()
        val nodes = JSONArray()
        for (mk in markers) {
            val x = fx(mk, m.widthPixels); val y = fy(mk, m.heightPixels)
            if (mk.type == "wheel") {
                nodes.put(JSONObject().put("type", "KMT_STEER_WHEEL").put("centerPos", pt(x, y))
                    .put("upKey", mk.wheel[0]).put("leftKey", mk.wheel[1])
                    .put("downKey", mk.wheel[2]).put("rightKey", mk.wheel[3])
                    .put("leftOffset", 0.1).put("rightOffset", 0.1).put("upOffset", 0.1).put("downOffset", 0.1))
            } else {
                nodes.put(JSONObject().put("type", "KMT_CLICK").put("key", mk.key)
                    .put("pos", pt(x, y)).put("switchMap", false))
            }
        }
        val root = JSONObject().put("switchKey", "Key_QuoteLeft")
            .put("mouseMoveMap", JSONObject().put("startPos", pt(0.5f, 0.5f))
                .put("speedRatio", JSONObject().put("x", 2.0).put("y", 2.0)))
            .put("keyMapNodes", nodes)
        file().writeText(root.toString(2))
        Toast.makeText(this, "Saved ${file().absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun loadExisting() {
        val f = file(); if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("keyMapNodes")
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                if (n.getString("type") == "KMT_STEER_WHEEL") {
                    val c = n.getJSONObject("centerPos")
                    addMarker("wheel", "WASD", c.getDouble("x").toFloat(), c.getDouble("y").toFloat()).wheel =
                        mutableListOf(n.getString("upKey"), n.getString("leftKey"), n.getString("downKey"), n.getString("rightKey"))
                } else if (n.getString("type") == "KMT_CLICK") {
                    val p = n.getJSONObject("pos")
                    addMarker("click", n.getString("key"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
                }
            }
        }
    }
}
