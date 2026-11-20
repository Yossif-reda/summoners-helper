package com.example.sgbot

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** مكان اكتشاف (المركز) في فراغ الإحداثيات المرجعي 388x848. */
data class Hit(val x: Int, val y: Int, val score: Double)

/**
 * كل الـ templates اتقصّت من فيديوهات اللعبة بمقاس 388x848،
 * فنصغّر أي screenshot لنفس المقاس قبل المطابقة، وبعدين نرجّع الإحداثيات للمقاس الحقيقي.
 */
class Detector(ctx: Context) {

    companion object {
        const val W = 388
        const val H = 848
    }

    private val tpl = HashMap<String, Mat>()

    // منطقة البحث لكل template: x0, y0, x1, y1
    private val roi = mapOf(
        "hdr_bonus" to intArrayOf(60, 180, 330, 260),
        "hdr_seller" to intArrayOf(60, 180, 330, 260),
        "watch_video" to intArrayOf(0, 420, 388, 540),
        "coin" to intArrayOf(180, 420, 388, 540),
        "okay" to intArrayOf(40, 430, 350, 530),
        "seller_idle" to intArrayOf(230, 600, 388, 780),
        "monitor_idle" to intArrayOf(230, 600, 388, 780),
        "trophy" to intArrayOf(280, 50, 388, 130),
        "ach_header" to intArrayOf(60, 40, 330, 120),
        "ach_close" to intArrayOf(300, 40, 388, 120),
        // الفوز / الخسارة
        "continue_btn" to intArrayOf(40, 560, 350, 700),
        "map_hdr" to intArrayOf(100, 30, 300, 90),
        "map_close" to intArrayOf(320, 30, 388, 100),
        "new_lbl" to intArrayOf(20, 300, 388, 820),
        "time_warp_btn" to intArrayOf(10, 560, 200, 700),
        "return_to" to intArrayOf(200, 500, 388, 600),
        "confirm_btn" to intArrayOf(100, 770, 290, 848),
    )

    init {
        for (name in roi.keys) {
            val bytes = ctx.assets.open("templates/$name.png").use { it.readBytes() }
            val m = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
            require(!m.empty()) { "template $name لم يتحمّل" }
            tpl[name] = m
        }
    }

    /** يحوّل الـ Bitmap لـ Mat بصيغة BGR ومقاس 388x848. */
    fun prepare(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val small = Mat()
        Imgproc.resize(rgba, small, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bgr = Mat()
        Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        small.release()
        return bgr
    }

    fun find(frame: Mat, name: String, threshold: Double): Hit? {
        val t = tpl[name] ?: return null
        val r = roi[name] ?: return null
        val x0 = r[0].coerceIn(0, W)
        val y0 = r[1].coerceIn(0, H)
        val x1 = r[2].coerceIn(0, W)
        val y1 = r[3].coerceIn(0, H)
        if (x1 - x0 < t.cols() || y1 - y0 < t.rows()) return null
        val sub = frame.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val res = Mat()
        Imgproc.matchTemplate(sub, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        res.release()
        sub.release()
        if (mm.maxVal < threshold) return null
        return Hit(
            x0 + mm.maxLoc.x.toInt() + t.cols() / 2,
            y0 + mm.maxLoc.y.toInt() + t.rows() / 2,
            mm.maxVal
        )
    }

    /** كل التطابقات فوق الحد (مثلاً علامات NEW! في قائمة الخرائط)، من فوق لتحت. */
    fun findAll(frame: Mat, name: String, threshold: Double, max: Int = 6): List<Hit> {
        val t = tpl[name] ?: return emptyList()
        val r = roi[name] ?: return emptyList()
        val x0 = r[0].coerceIn(0, W)
        val y0 = r[1].coerceIn(0, H)
        val x1 = r[2].coerceIn(0, W)
        val y1 = r[3].coerceIn(0, H)
        if (x1 - x0 < t.cols() || y1 - y0 < t.rows()) return emptyList()
        val sub = frame.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val res = Mat()
        Imgproc.matchTemplate(sub, t, res, Imgproc.TM_CCOEFF_NORMED)
        val out = ArrayList<Hit>()
        for (i in 0 until max) {
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal < threshold) break
            val px = mm.maxLoc.x.toInt()
            val py = mm.maxLoc.y.toInt()
            out.add(Hit(x0 + px + t.cols() / 2, y0 + py + t.rows() / 2, mm.maxVal))
            // امسح الجيران عشان ما نعدّش نفس العلامة مرتين
            Imgproc.rectangle(
                res, org.opencv.core.Point((px - 20).toDouble(), (py - 10).toDouble()),
                org.opencv.core.Point((px + 20).toDouble(), (py + 10).toDouble()),
                Scalar(-1.0), -1
            )
        }
        res.release()
        sub.release()
        return out.sortedBy { it.y }
    }

    /** دايرة الـ "!" الزرقاء على أيقونة الكأس. تُستخدم فقط لما الكأس نفسها ظاهرة (يعني الشاشة هي اللعبة). */
    fun hasAchievementBadge(frame: Mat): Boolean {
        val sub = frame.submat(Rect(330, 88, 28, 24))
        val hsv = Mat()
        Imgproc.cvtColor(sub, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(95.0, 120.0, 150.0), Scalar(115.0, 255.0, 255.0), mask)
        val n = Core.countNonZero(mask)
        sub.release(); hsv.release(); mask.release()
        return n > 20
    }

    /** أزرار الاستلام الخضرا داخل نافذة الإنجازات. بترجّع المراكز من فوق لتحت. */
    fun claimButtons(frame: Mat): List<Hit> {
        val ox = 220
        val oy = 100
        val sub = frame.submat(Rect(ox, oy, 150, 680))
        val hsv = Mat()
        Imgproc.cvtColor(sub, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(35.0, 120.0, 150.0), Scalar(85.0, 255.0, 255.0), mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val out = ArrayList<Hit>()
        for (c in contours) {
            val b = Imgproc.boundingRect(c)
            if (b.width in 60..110 && b.height in 22..45) {
                out.add(Hit(ox + b.x + b.width / 2, oy + b.y + b.height / 2, 1.0))
            }
            c.release()
        }
        sub.release(); hsv.release(); mask.release(); kernel.release()
        return out.sortedBy { it.y }
    }

    /** بصمة صغيرة لمنطقة القائمة، لمعرفة هل السحب حرّك القائمة ولا وصلنا للآخر. */
    fun panelSignature(frame: Mat): Mat {
        val sub = frame.submat(Rect(100, 110, 250, 640))
        val gray = Mat()
        Imgproc.cvtColor(sub, gray, Imgproc.COLOR_BGR2GRAY)
        val small = Mat()
        Imgproc.resize(gray, small, Size(50.0, 128.0), 0.0, 0.0, Imgproc.INTER_AREA)
        sub.release(); gray.release()
        return small
    }

    fun sameSignature(a: Mat, b: Mat): Boolean {
        val d = Mat()
        Core.absdiff(a, b, d)
        val mean = Core.mean(d).`val`[0]
        d.release()
        return mean < 2.0
    }
}
