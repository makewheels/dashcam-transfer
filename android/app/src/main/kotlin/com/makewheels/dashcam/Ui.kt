package com.makewheels.dashcam

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Small native UI palette with consistent touch targets, spacing and hierarchy. */
internal class Ui(val context: Context) {
  fun dp(value: Int): Int =
      Math.round(value * context.resources.displayMetrics.density)

  fun shape(color: Int, radius: Int): GradientDrawable =
      GradientDrawable().apply {
        setColor(color)
        setCornerRadius(dp(radius).toFloat())
      }

  fun text(value: String, size: Int, color: Int): TextView =
      TextView(context).apply {
        this.text = value
        setTextSize(size.toFloat())
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
      }

  fun bold(value: String, size: Int, color: Int): TextView =
      text(value, size, color).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
      }

  fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

  fun row(): LinearLayout = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

  fun card(): LinearLayout = column().apply {
    setPadding(dp(20), dp(20), dp(20), dp(20))
    background = shape(Color.WHITE, 20)
  }

  fun add(parent: LinearLayout, child: View, top: Int) {
    val p = LinearLayout.LayoutParams(-1, -2)
    p.topMargin = dp(top)
    parent.addView(child, p)
  }

  fun line(parent: LinearLayout, top: Int) {
    val v = View(context)
    v.setBackgroundColor(LINE)
    val p = LinearLayout.LayoutParams(-1, dp(1))
    p.topMargin = dp(top)
    p.bottomMargin = dp(top)
    parent.addView(v, p)
  }

  fun button(label: String, primary: Boolean, action: Runnable): TextView =
      bold(label, 15, if (primary) Color.WHITE else BLUE).apply {
        gravity = Gravity.CENTER
        minHeight = dp(50)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = RippleDrawable(
            ColorStateList.valueOf(0x202563eb.toInt()), shape(if (primary) BLUE else TINT, 14), null)
        setOnClickListener { action.run() }
        isFocusable = true
      }

  fun bar(): ProgressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 1000
    progressTintList = ColorStateList.valueOf(BLUE)
    progressBackgroundTintList = ColorStateList.valueOf(LINE)
    val lp = LinearLayout.LayoutParams(-1, dp(8))
    lp.topMargin = dp(10)
    layoutParams = lp
  }

  fun chip(label: String, color: Int, bg: Int): TextView =
      bold(label, 11, color).apply {
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = shape(bg, 8)
      }

  fun icon(name: String, color: Int, size: Int): ImageView = ImageView(context).apply {
    setImageDrawable(Icon(name, color))
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  class Icon(private val name: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
      paint.color = color
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = 1.7f
      paint.strokeCap = Paint.Cap.ROUND
      paint.strokeJoin = Paint.Join.ROUND
    }

    override fun draw(c: Canvas) {
      c.save()
      c.translate(bounds.left.toFloat(), bounds.top.toFloat())
      c.scale(bounds.width().toFloat() / 24f, bounds.height().toFloat() / 24f)
      val p = Path()
      when (name) {
        "history" -> {
          c.drawCircle(12f, 12f, 9f, paint)
          p.moveTo(12f, 6f); p.lineTo(12f, 12f); p.lineTo(16f, 14f)
        }
        "start" -> {
          p.moveTo(3f, 10f); p.lineTo(12f, 3f); p.lineTo(21f, 10f)
          p.moveTo(5f, 9f); p.lineTo(5f, 21f); p.lineTo(19f, 21f); p.lineTo(19f, 9f)
          p.moveTo(10f, 21f); p.lineTo(10f, 14f); p.lineTo(14f, 14f); p.lineTo(14f, 21f)
        }
        "upload" -> {
          p.moveTo(4f, 17f); p.lineTo(4f, 21f); p.lineTo(20f, 21f); p.lineTo(20f, 17f)
          p.moveTo(12f, 17f); p.lineTo(12f, 3f)
          p.moveTo(7f, 8f); p.lineTo(12f, 3f); p.lineTo(17f, 8f)
        }
        "cloud" -> {
          p.moveTo(7f, 19f)
          p.cubicTo(0f, 19f, 1f, 9f, 7f, 10f)
          p.cubicTo(7f, 1f, 21f, 3f, 19f, 11f)
          p.cubicTo(25f, 12f, 23f, 19f, 18f, 19f)
          p.close()
        }
        "settings" -> {
          for (x in intArrayOf(5, 12, 19)) {
            p.moveTo(x.toFloat(), 3f); p.lineTo(x.toFloat(), 21f)
          }
          c.drawPath(p, paint)
          p.reset()
          c.drawCircle(5f, 8f, 2.5f, paint)
          c.drawCircle(12f, 16f, 2.5f, paint)
          c.drawCircle(19f, 10f, 2.5f, paint)
        }
        "folder" -> {
          p.moveTo(3f, 6f); p.lineTo(10f, 6f); p.lineTo(12f, 9f)
          p.lineTo(21f, 9f); p.lineTo(21f, 20f); p.lineTo(3f, 20f); p.close()
        }
        "check" -> {
          p.moveTo(5f, 12f); p.lineTo(10f, 17f); p.lineTo(19f, 7f)
        }
        "wifi" -> {
          p.moveTo(3f, 8f); p.quadTo(12f, 1f, 21f, 8f)
          p.moveTo(6f, 12f); p.quadTo(12f, 7f, 18f, 12f)
          p.moveTo(9f, 16f); p.quadTo(12f, 13f, 15f, 16f)
          c.drawCircle(12f, 20f, .8f, paint)
        }
        "cell" -> {
          p.moveTo(5f, 20f); p.lineTo(5f, 15f)
          p.moveTo(10f, 20f); p.lineTo(10f, 11f)
          p.moveTo(15f, 20f); p.lineTo(15f, 7f)
          p.moveTo(20f, 20f); p.lineTo(20f, 3f)
        }
        "video" -> {
          c.drawRoundRect(3f, 3f, 21f, 21f, 4f, 4f, paint)
          p.moveTo(10f, 8f); p.lineTo(16f, 12f); p.lineTo(10f, 16f); p.close()
        }
        "trash" -> {
          p.moveTo(4f, 6f); p.lineTo(20f, 6f)
          p.moveTo(9f, 3f); p.lineTo(15f, 3f)
          p.moveTo(6f, 6f); p.lineTo(7f, 21f); p.lineTo(17f, 21f); p.lineTo(18f, 6f)
          p.moveTo(10f, 10f); p.lineTo(10f, 17f)
          p.moveTo(14f, 10f); p.lineTo(14f, 17f)
        }
        else -> c.drawCircle(12f, 12f, 9f, paint)
      }
      c.drawPath(p, paint)
      c.restore()
    }

    override fun setAlpha(alpha: Int) {
      paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
      paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
  }

  companion object {
    const val INK = 0xff16253c.toInt()
    const val MUTED = 0xff6b788c.toInt()
    const val BLUE = 0xff2563eb.toInt()
    const val BG = 0xfff5f7fb.toInt()
    const val LINE = 0xffe5eaf2.toInt()
    const val TINT = 0xffedf3ff.toInt()
    const val GREEN = 0xff148765.toInt()
    const val AMBER = 0xffad6612.toInt()
  }
}
