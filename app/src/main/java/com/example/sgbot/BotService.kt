package com.example.sgbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.util.concurrent.Executor

class BotService : AccessibilityService() {

    companion object {
        private const val TAG = "SGBot"
        const val PREFS = "sgbot"
        const val DEFAULT_GAME_PKG = "com.pixio.google.mtd"

        /** true لما البوت مفعّل واللعبة هي الشاشة الحالية. */
        @Volatile var gameActive = false
        @Volatile var gamePkg = DEFAULT_GAME_PKG
        private val logLines = ArrayDeque<String>()

        @Synchronized
        fun log(msg: String) {
            Log.i(TAG, msg)
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logLines.addFirst("$t  $msg")
            while (logLines.size > 60) logLines.removeLast()
        }

        @Synchronized
        fun recentLog(): String = logLines.joinToString("\n")
    }

    private enum class Mode { IDLE, AD }

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("sgbot-worker").also { it.start() }
    private val worker = Handler(workerThread.looper)
    private val executor = Executor { worker.post(it) }

    private lateinit var det: Detector
    private var ready = false
    private var busy = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var eventPkg: String? = null      // آخر حزمة وصلت من أحداث النوافذ
    private var overlay: TextView? = null     // الأيقونة العايمة (بتظهر جوه اللعبة بس)
    private var overlayLp: WindowManager.LayoutParams? = null

    // حالة الـ state machine
    private var mode = Mode.IDLE
    private var adStart = 0L
    private var lastTap = 0L
    private var cooldownUntil = 0L        // بعد ما نخلص تاجر/مونيتور نتجاهل الشخصية وهي ماشية
    private var lastSpriteTap = 0L
    private var lastTrophyTap = 0L
    private var popupKey: String? = null
    private var popupSince = 0L

    // تدفق الفوز/الخسارة: بنأكد على شاشة اختيار الوحوش بس لو إحنا اللي بدأنا التدفق
    private var winFlowUntil = 0L
    private var placeFlowUntil = 0L
    private var mapScrolls = 0

    // حالة نافذة الإنجازات
    private var achPhase = 0              // 0: اسحب لأعلى القائمة، 1: انزل لآخرها، 2: اقفل
    private var achSwipes = 0
    private var prevSig: Mat? = null

    private var sx = 1f
    private var sy = 1f

    private val loop = object : Runnable {
        override fun run() {
            try {
                val inGame = isGameForeground()
                if (inGame) showOverlay() else hideOverlay()

                if (inGame && enabled()) {
                    if (!gameActive) {
                        gameActive = true
                        log("اللعبة اتفتحت، البوت بدأ")
                    }
                    acquireWake()
                    capture()
                } else {
                    if (gameActive) {
                        gameActive = false
                        resetState()
                        log(if (inGame) "البوت اتوقف" else "اللعبة مش على الشاشة، البوت مستني")
                    }
                    releaseWake()
                }
            } catch (e: Throwable) {
                log("خطأ: ${e.message}")
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!OpenCVLoader.initLocal()) {
            log("OpenCV مش راضي يشتغل")
            return
        }
        det = Detector(this)
        gamePkg = resolveGamePackage()
        ready = true
        log("الخدمة اتوصلت. اللعبة: $gamePkg")
        main.post(loop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        // نتجاهل نافذة النظام ولوحة المفاتيح: الشاشة الحقيقية هي اللي تحتهم
        if (p == "com.android.systemui" || p.contains("inputmethod") || p == packageName) return
        eventPkg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hideOverlay()
        releaseWake()
        gameActive = false
        workerThread.quitSafely()
        super.onDestroy()
    }

    // ---------- اكتشاف اللعبة ----------
    private fun resolveGamePackage(): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        if (apps.any { it.activityInfo.packageName == DEFAULT_GAME_PKG }) return DEFAULT_GAME_PKG
        // لو الحزمة الافتراضية مش موجودة: دوّر بالاسم (ونستثني تطبيقنا هو)
        val byLabel = apps.firstOrNull {
            it.activityInfo.packageName != packageName &&
                it.loadLabel(pm).toString().contains("summoner", ignoreCase = true)
        }?.activityInfo?.packageName
        return byLabel ?: DEFAULT_GAME_PKG
    }

    private fun isGameForeground(): Boolean {
        val root = rootInActiveWindow
        val p = root?.packageName?.toString()
        @Suppress("DEPRECATION")
        root?.recycle()
        val current = p ?: eventPkg
        return current == gamePkg
    }

    // ---------- الأيقونة العايمة ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (overlay != null) { refreshOverlay(); return }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = dp(46)
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs().getInt("ovx", 0)
            y = prefs().getInt("ovy", (dm.heightPixels * 0.55).toInt())
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                        moved = true
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        try { wm.updateViewLayout(view, lp) } catch (_: Throwable) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs().edit().putInt("ovx", lp.x).putInt("ovy", lp.y).apply()
                    } else {
                        val now = !prefs().getBoolean("enabled", true)
                        prefs().edit().putBoolean("enabled", now).apply()
                        log(if (now) "البوت شغّال (من الأيقونة)" else "البوت اتوقف (من الأيقونة)")
                        refreshOverlay()
                    }
                }
            }
            true
        }
        try {
            wm.addView(view, lp)
            overlay = view
            overlayLp = lp
            refreshOverlay()
        } catch (e: Throwable) {
            log("مقدرتش أعرض الأيقونة: ${e.message}")
        }
    }

    private fun refreshOverlay() {
        val v = overlay ?: return
        val on = prefs().getBoolean("enabled", true)
        v.text = if (on) "ON" else "OFF"
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) Color.argb(200, 46, 160, 67) else Color.argb(200, 200, 50, 50))
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {}
        overlay = null
        overlayLp = null
    }

    // ---------- إعدادات ----------
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun enabled() = ready && prefs().getBoolean("enabled", true)
    private fun optBuy() = prefs().getBoolean("buy", true)
    private fun optMonitor() = prefs().getBoolean("monitor", true)
    private fun optAch() = prefs().getBoolean("ach", true)
    private fun optWarp() = prefs().getBoolean("warp", true)
    private fun optNext() = prefs().getBoolean("next", true)

    @Suppress("DEPRECATION")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "sgbot:screen"
        ).also { it.acquire() }
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun resetState() {
        mode = Mode.IDLE
        popupKey = null
        winFlowUntil = 0
        placeFlowUntil = 0
        mapScrolls = 0
        resetAch()
    }

    // ---------- التقاط الشاشة ----------
    private fun capture() {
        if (busy) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var frame: Mat? = null
                try {
                    val hb = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    if (hw == null) { hb.close(); return }
                    val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                    hw.recycle()
                    hb.close()
                    sx = bmp.width / Detector.W.toFloat()
                    sy = bmp.height / Detector.H.toFloat()
                    frame = det.prepare(bmp)
                    bmp.recycle()
                    step(frame)
                } catch (e: Throwable) {
                    log("خطأ في المعالجة: ${e.message}")
                } finally {
                    frame?.release()
                    busy = false
                }
            }

            override fun onFailure(errorCode: Int) {
                busy = false
                log("فشل التقاط الشاشة (كود $errorCode)")
            }
        })
    }

    // ---------- المنطق الرئيسي ----------
    private fun step(f: Mat) {
        val now = SystemClock.elapsedRealtime()

        val hdrBonus = det.find(f, "hdr_bonus", 0.80)
        val hdrSeller = det.find(f, "hdr_seller", 0.80)
        val okay = det.find(f, "okay", 0.80)
        val achHdr = det.find(f, "ach_header", 0.80)

        // 1) نافذة المونيتور (Bonus)
        if (hdrBonus != null) {
            trackPopup("bonus", now)
            if (mode == Mode.AD) return                 // الإعلان لسه بيتحمّل
            if (now - lastTap < 1200) return
            val watch = det.find(f, "watch_video", 0.80)
            if (optMonitor() && watch != null && now - popupSince < 8000) {
                tap(watch.x, watch.y)
                mode = Mode.AD
                adStart = now
                log("مونيتور: ضغطت Watch Video")
            } else {
                tap(hdrBonus.x - 86, hdrBonus.y + 259)
                log("مونيتور: ضغطت No Thanks")
            }
            return
        }

        // 2) نافذة التاجر
        if (hdrSeller != null) {
            trackPopup("seller", now)
            if (now - lastTap < 1200) return
            val coin = det.find(f, "coin", 0.80)
            // نشتري بس لو السعر بعملة الذهب (مش الجواهر البنفسجي) ولو الزرار مش عالق
            if (optBuy() && coin != null && now - popupSince < 8000) {
                tap(coin.x, coin.y)
                log("تاجر: اشتريت بالذهب")
            } else {
                tap(hdrSeller.x - 86, hdrSeller.y + 259)
                log("تاجر: ضغطت No Thanks")
            }
            return
        }
        popupKey = null

        // 3) نافذة "You received" -> OKAY
        if (okay != null) {
            if (now - lastTap < 1000) return
            tap(okay.x, okay.y)
            log("استلمت الجائزة (OKAY)")
            mode = Mode.IDLE
            cooldownUntil = now + 12_000
            return
        }

        // 4) نافذة الإنجازات
        if (achHdr != null) {
            handleAchievements(f, now)
            return
        } else {
            resetAch()
        }

        // 5) الخسارة: Time Warp ثم Return to Wave
        if (handleLoss(f, now)) return

        // 6) الفوز بالزعيم: Continue ثم اختيار الخريطة التالية
        if (handleWin(f, now)) return

        // 7) شاشة اختيار الوحوش بعد التدفقين: Confirm
        if (now < placeFlowUntil && now - lastTap >= 1500) {
            val confirm = det.find(f, "confirm_btn", 0.80)
            if (confirm != null) {
                tap(confirm.x, confirm.y)
                placeFlowUntil = 0
                log("أكدت مكان الوحوش (Confirm)")
                return
            }
        }

        // 8) فيديو إعلان شغال: نستنى لحد ما يقفل لوحده ويظهر OKAY
        if (mode == Mode.AD) {
            if (now - adStart > 150_000) {
                log("الإعلان خد وقت طويل، ضغطت Back")
                performGlobalAction(GLOBAL_ACTION_BACK)
                mode = Mode.IDLE
                cooldownUntil = now + 5_000
            }
            return
        }

        // 9) شاشة اللعب العادية
        val trophy = det.find(f, "trophy", 0.75) ?: return
        if (now < cooldownUntil || now - lastTap < 1200) return

        val sprite = det.find(f, "seller_idle", 0.72) ?: det.find(f, "monitor_idle", 0.72)
        if (sprite != null && now - lastSpriteTap > 4000) {
            tap(sprite.x, sprite.y)
            lastSpriteTap = now
            log("ضغطت على الشخصية الواقفة")
            return
        }

        if (optAch() && now - lastTrophyTap > 20_000 && det.hasAchievementBadge(f)) {
            tap(trophy.x, trophy.y)
            lastTrophyTap = now
            resetAch()
            log("فتحت الإنجازات")
        }
    }

    private fun trackPopup(key: String, now: Long) {
        if (popupKey != key) {
            popupKey = key
            popupSince = now
        }
    }

    // ---------- الخسارة ----------
    /** @return true لو الشاشة دي من تدفق الخسارة (سواء ضغطنا أو لأ). */
    private fun handleLoss(f: Mat, now: Long): Boolean {
        // نافذة Time Warp لازم تتفحص الأول، لأن شاشة الخسارة بتفضل ظاهرة باهتة وراها
        val returnTo = det.find(f, "return_to", 0.80)
        if (returnTo != null) {
            if (optWarp() && now - lastTap >= 1500) {
                tap(returnTo.x, returnTo.y)
                placeFlowUntil = now + 90_000
                log("خسارة: Return to Wave")
            }
            return true
        }
        val warp = det.find(f, "time_warp_btn", 0.80)
        if (warp != null) {
            if (optWarp() && now - lastTap >= 1500) {
                tap(warp.x, warp.y)
                log("خسارة: ضغطت Time Warp")
            }
            return true
        }
        return false
    }

    // ---------- الفوز ----------
    private fun handleWin(f: Mat, now: Long): Boolean {
        val mapHdr = det.find(f, "map_hdr", 0.80)
        if (mapHdr != null) {
            if (optNext() && now < winFlowUntil) handleMap(f, now)
            return true
        }
        mapScrolls = 0

        val cont = det.find(f, "continue_btn", 0.80)
        if (cont != null) {
            if (optNext() && now - lastTap >= 1500) {
                tap(cont.x, cont.y)
                winFlowUntil = now + 120_000
                log("فوز: ضغطت Continue")
            }
            return true
        }
        return false
    }

    private fun handleMap(f: Mat, now: Long) {
        if (now - lastTap < 1200) return
        val news = det.findAll(f, "new_lbl", 0.75)
        // الأولوية لزرار Normal (العمود الشمال)، بعدين Expert، بعدين Heroic
        val pick = news.filter { it.x < 130 }.minByOrNull { it.y }
            ?: news.filter { it.x in 130..260 }.minByOrNull { it.y }
            ?: news.minByOrNull { it.y }
        if (pick != null) {
            tap(pick.x, pick.y + 26)          // الزرار تحت علامة NEW! بـ 26 بكسل
            placeFlowUntil = now + 90_000
            log("فوز: اخترت الخريطة الجديدة")
            return
        }
        when {
            mapScrolls < 8 -> { swipe(194, 700, 194, 380); mapScrolls++ }        // انزل في القائمة
            mapScrolls < 16 -> { swipe(194, 380, 194, 700); mapScrolls++ }       // اطلع
            else -> {
                val close = det.find(f, "map_close", 0.80)
                if (close != null) {
                    tap(close.x, close.y)
                    log("مفيش خريطة جديدة، قفلت القائمة")
                    winFlowUntil = 0
                    mapScrolls = 0
                }
            }
        }
    }

    // ---------- الإنجازات ----------
    private fun resetAch() {
        achPhase = 0
        achSwipes = 0
        prevSig?.release()
        prevSig = null
    }

    private fun handleAchievements(f: Mat, now: Long) {
        if (now - lastTap < 900) return

        val claims = det.claimButtons(f)
        if (claims.isNotEmpty()) {
            tap(claims[0].x, claims[0].y)
            log("استلمت إنجاز")
            return
        }

        val cur = det.panelSignature(f)
        val unchanged = prevSig?.let { det.sameSignature(it, cur) } ?: false
        prevSig?.release()
        prevSig = cur

        if (achSwipes >= 14) achPhase = 2

        if (achPhase == 0) {
            if (unchanged) {
                achPhase = 1
                swipe(194, 650, 194, 300)       // ابدأ النزول
                achSwipes++
            } else {
                swipe(194, 300, 194, 650)       // اطلع لأعلى القائمة
                achSwipes++
            }
            return
        }
        if (achPhase == 1) {
            if (!unchanged) {
                swipe(194, 650, 194, 300)
                achSwipes++
                return
            }
            achPhase = 2
        }

        // phase 2: خلصنا، اقفل النافذة
        val close = det.find(f, "ach_close", 0.80)
        if (close != null) {
            tap(close.x, close.y)
            log("قفلت الإنجازات")
            resetAch()
        }
    }

    // ---------- لمس ----------
    private fun tap(x: Int, y: Int) {
        lastTap = SystemClock.elapsedRealtime()
        val p = Path().apply { moveTo(x * sx, y * sy) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 70))
    }

    private fun swipe(x0: Int, y0: Int, x1: Int, y1: Int) {
        lastTap = SystemClock.elapsedRealtime()
        val p = Path().apply {
            moveTo(x0 * sx, y0 * sy)
            lineTo(x1 * sx, y1 * sy)
        }
        dispatch(GestureDescription.StrokeDescription(p, 0, 350))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val g = GestureDescription.Builder().addStroke(stroke).build()
        main.post { dispatchGesture(g, null, null) }
    }
}
