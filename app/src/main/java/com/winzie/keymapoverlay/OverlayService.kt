package com.winzie.keymapoverlay

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
            text = if (type == "wheel") "WASD" else key.removePrefix("Key_")
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

    private fun editMarker(mk: Marker) {
        val et = EditText(this).apply {
            setText(if (mk.type == "wheel") mk.wheel.joinToString(",") else mk.key)
            hint = if (mk.type == "wheel") "Up,Left,Down,Right e.g. Key_W,Key_A,Key_S,Key_D" else "e.g. Key_R, Key_Space, Button_Left"
        }
        val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(if (mk.type == "wheel") "Wheel keys (up,left,down,right)" else "QtScrcpy key name")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (mk.type == "wheel") {
                    val p = t.split(",").map { it.trim() }
                    if (p.size == 4) mk.wheel = p.toMutableList()
                } else { mk.key = t; mk.view.text = t.removePrefix("Key_").removePrefix("Button_") }
            }
            .setNeutralButton("Delete") { _, _ -> wm.removeView(mk.view); markers.remove(mk) }
            .setNegativeButton("Cancel", null).create()
        d.window?.setType(LP.TYPE_APPLICATION_OVERLAY)
        d.show()
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
