package com.example.sgbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var status: TextView
    private lateinit var botSwitch: Switch

    private val refresh = object : Runnable {
        override fun run() {
            logView.text = BotService.recentLog()
            val on = getSharedPreferences(BotService.PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
            status.text = when {
                !on -> "الحالة: واقف (فعّله من هنا أو من الأيقونة جوه اللعبة)"
                BotService.gameActive -> "الحالة: شغّال (اللعبة مفتوحة)"
                else -> "الحالة: مستني تفتح اللعبة (${BotService.gamePkg})"
            }
            if (botSwitch.isChecked != on) botSwitch.isChecked = on   // لو اتغيّر من الأيقونة
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(BotService.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(40, 60, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Summoners Helper"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        })

        status = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1) فتح إعدادات إمكانية الوصول وتفعيل الخدمة"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        botSwitch = Switch(this).apply {
            text = "2) تشغيل البوت (بيشتغل بس لما تفتح اللعبة)"
            textSize = 18f
            isChecked = prefs.getBoolean("enabled", true)
            setPadding(0, 30, 0, 30)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("enabled", on).apply() }
        }
        root.addView(botSwitch)

        fun option(key: String, label: String) = CheckBox(this).apply {
            text = label
            isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        root.addView(option("buy", "الشراء من التاجر (بالذهب فقط)"))
        root.addView(option("monitor", "مشاهدة إعلان المونيتور"))
        root.addView(option("ach", "استلام جوائز الإنجازات"))
        root.addView(option("warp", "لما تخسر: Time Warp وإعادة"))
        root.addView(option("next", "لما تكسب الزعيم: الخريطة الجديدة"))

        root.addView(TextView(this).apply {
            text = "\nالسجل:"
            gravity = Gravity.START
            setTypeface(null, Typeface.BOLD)
        })
        logView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }
}
