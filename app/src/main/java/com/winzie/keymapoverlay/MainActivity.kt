package com.winzie.keymapoverlay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun button(t: String, bg: Int, fg: Int, click: () -> Unit) = Button(this).apply {
        text = t; textSize = 15f; setAllCaps(false); stateListAnimator = null
        background = rounded(bg, 14); setTextColor(fg)
        setOnClickListener { click() }
    }

    private fun step(n: String, t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = n; textSize = 13f; setTextColor(0xFF06232F.toInt()); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); background = rounded(0xFF29B6F6.toInt(), 12)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply { text = t; textSize = 14f; setTextColor(0xFFE8EDF3.toInt()) })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        window.statusBarColor = 0xFF12161C.toInt()
        window.navigationBarColor = 0xFF12161C.toInt()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(48), dp(22), dp(28)) }
        col.addView(TextView(this).apply {
            text = "Keymap Overlay"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Place game key markers, then control them from your PC"; textSize = 13f
            setTextColor(0xFF8B98A8.toInt()); setPadding(0, dp(2), 0, dp(18))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(0xFF1B212A.toInt(), 16, 0xFF2C3542.toInt())
        }
        card.addView(step("1", "Grant overlay permission"))
        card.addView(step("2", "Start overlay, open your game (landscape)"))
        card.addView(step("3", "Drag markers, tap one to set its key, press Save"))
        col.addView(card)

        col.addView(TextView(this).apply {
            text = "PC:  adb pull /sdcard/Android/data/$packageName/files/keymap.json"
            textSize = 11f; setTextColor(0xFF8B98A8.toInt()); setPadding(dp(4), dp(10), dp(4), dp(18))
        })

        fun add(v: Button) = col.addView(v, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })
        add(button("Grant overlay permission", 0xFF262F3B.toInt(), 0xFFE8EDF3.toInt()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        add(button("Start overlay", 0xFF29B6F6.toInt(), 0xFF06232F.toInt()) {
            if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, OverlayService::class.java))
        })
        add(button("Stop overlay", 0xFF3A1C1F.toInt(), 0xFFFF8A80.toInt()) {
            stopService(Intent(this, OverlayService::class.java))
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF12161C.toInt()); addView(col) })
    }
}
