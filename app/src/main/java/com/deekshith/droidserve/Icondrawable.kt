package com.deekshith.droidserve

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * DroidServe icon — 100% programmatic, zero drawable resources.
 *
 * Design: broadcast signal tower
 *   · Three upward-facing arc rings  (signal waves)
 *   · Vertical mast + base bar + diagonal feet  (tower structure)
 *   · Glowing pulse dot at tip  (live indicator)
 *
 * Use:
 *   DroidServeIconDrawable()            → Drawable for ImageView / Compose
 *   DroidServeIconDrawable.toBitmap(n)  → Bitmap at any pixel size
 *   DroidServeIconDrawable.toIcon()     → android.graphics.drawable.Icon
 *                                          (for notifications on API 23+)
 */
class DroidServeIconDrawable : Drawable() {

    // ── Colours ────────────────────────────────────────────────────────────
    private val cBg      = Color.parseColor("#0F172A")
    private val cGlow    = Color.parseColor("#1E3A5F")
    private val cAccent  = Color.parseColor("#38BDF8")
    private val cMast    = Color.parseColor("#E2E8F0")
    private val cFeet    = Color.parseColor("#94A3B8")
    private val cWhite   = Color.WHITE

    // ── Paints — allocated once ────────────────────────────────────────────
    private val pBg    = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cBg;    it.style = Paint.Style.FILL }
    private val pGlow  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cGlow;  it.style = Paint.Style.FILL; it.alpha = 160 }
    private val pGlow2 = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cGlow;  it.style = Paint.Style.FILL; it.alpha = 90  }
    private val pArc1  = arcPaint(255)
    private val pArc2  = arcPaint(191)   // 75%
    private val pArc3  = arcPaint(127)   // 50%
    private val pMast  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cMast; it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND }
    private val pFeet  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cFeet; it.style = Paint.Style.STROKE; it.strokeCap = Paint.Cap.ROUND }
    private val pDot   = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cAccent; it.style = Paint.Style.FILL }
    private val pHigh  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = cWhite;  it.style = Paint.Style.FILL; it.alpha = 130 }

    private fun arcPaint(a: Int) = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = cAccent; it.style = Paint.Style.STROKE
        it.strokeCap = Paint.Cap.ROUND; it.alpha = a
    }

    // ── Draw ──────────────────────────────────────────────────────────────
    override fun draw(canvas: Canvas) {
        val b  = bounds
        val w  = b.width().toFloat()
        val h  = b.height().toFloat()
        val cx = w / 2f
        // Design coordinates on a 108×108 grid
        val u  = w / 108f
        val sw = 4.5f * u   // thick stroke
        val tw = 3.0f * u   // thin stroke

        pArc1.strokeWidth = sw;  pArc2.strokeWidth = sw;  pArc3.strokeWidth = sw
        pMast.strokeWidth = sw;  pFeet.strokeWidth = tw

        // Background circle
        canvas.drawCircle(cx, h / 2f, cx, pBg)
        canvas.drawCircle(cx, h / 2f, cx * 0.88f, pGlow)
        canvas.drawCircle(cx, h / 2f, cx * 0.55f, pGlow2)

        // Signal arcs — open upward semicircles at decreasing radii
        //   Centre of arc system is at y = 62u (broadcast origin)
        val ay = 62f * u
        canvas.drawArc(oval(cx, ay, 30f*u), 180f, -180f, false, pArc1)
        canvas.drawArc(oval(cx, ay, 22f*u), 180f, -180f, false, pArc2)
        canvas.drawArc(oval(cx, ay, 14f*u), 180f, -180f, false, pArc3)

        // Tower mast (vertical)
        canvas.drawLine(cx, 74f*u, cx, 88f*u, pMast)
        // Base bar (horizontal)
        canvas.drawLine(45f*u, 88f*u, 63f*u, 88f*u, pMast)
        // Diagonal feet
        canvas.drawLine(45f*u, 88f*u, 48f*u, 82f*u, pFeet)
        canvas.drawLine(63f*u, 88f*u, 60f*u, 82f*u, pFeet)

        // Pulse dot at broadcast tip
        val dotY = 56f * u
        canvas.drawCircle(cx, dotY, 5.5f*u, pDot)
        canvas.drawCircle(cx, dotY, 2.5f*u, pHigh)
    }

    private fun oval(cx: Float, cy: Float, r: Float) =
        RectF(cx - r, cy - r, cx + r, cy + r)

    override fun setAlpha(a: Int)              { pBg.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { pBg.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // ── Static factory helpers ────────────────────────────────────────────
    companion object {

        /** Render to a Bitmap at any square pixel size. */
        fun toBitmap(size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            DroidServeIconDrawable().apply {
                setBounds(0, 0, size, size)
                draw(Canvas(bmp))
            }
            return bmp
        }


        /**
         * Circular-clipped bitmap (clean for notification large icon slot).
         */
        fun toCircularBitmap(size: Int): Bitmap {
            val src = toBitmap(size)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cvs = Canvas(out)
            val p   = Paint(Paint.ANTI_ALIAS_FLAG)
            val r   = size / 2f
            cvs.drawCircle(r, r, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            cvs.drawBitmap(src, 0f, 0f, p)
            src.recycle()
            return out
        }
    }
}