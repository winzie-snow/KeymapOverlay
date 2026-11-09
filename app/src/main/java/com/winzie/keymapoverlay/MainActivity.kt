package com.winzie.keymapoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply {
            text = "1) Grant overlay permission\n2) Start overlay, open your game (landscape)\n3) Drag markers, tap one to set its key, press Save\n\nPC: adb pull /sdcard/Android/data/$packageName/files/keymap.json"
        })
        col.addView(Button(this).apply {
            text = "Grant overlay permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        col.addView(Button(this).apply {
            text = "Start overlay"
            setOnClickListener {
                if (Settings.canDrawOverlays(context)) startForegroundService(Intent(context, OverlayService::class.java))
            }
        })
        col.addView(Button(this).apply {
            text = "Stop overlay"
            setOnClickListener { stopService(Intent(context, OverlayService::class.java)) }
        })
        setContentView(col)
    }
}
