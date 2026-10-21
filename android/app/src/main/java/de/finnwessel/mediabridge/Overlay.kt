package de.finnwessel.mediabridge

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shows a small card in the top-right corner above whatever is playing.
 * Needs the "display over other apps" app-op (granted via ADB); falls back
 * to a plain toast without it.
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private val hide = Runnable { removeCurrent() }

    fun show(context: Context, title: String?, message: String, durationMs: Long) {
        main.post { showOnMain(context.applicationContext, title, message, durationMs.coerceIn(2_000L, 120_000L)) }
    }

    private fun showOnMain(context: Context, title: String?, message: String, durationMs: Long) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing, using toast")
            val text = if (title.isNullOrBlank()) message else "$title\n$message"
            Toast.makeText(context, text, if (durationMs > 4000) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        removeCurrent()
        val wm = context.getSystemService(WindowManager::class.java)
        val view = buildCard(context, title, message)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(context, 48)
            y = dp(context, 40)
            windowAnimations = android.R.style.Animation_Toast
        }
        try {
            wm.addView(view, params)
            current = view
            main.removeCallbacks(hide)
            main.postDelayed(hide, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed: ${e.message}")
        }
    }

    private fun removeCurrent() {
        val view = current ?: return
        current = null
        runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
    }

    private fun buildCard(context: Context, title: String?, message: String): View {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 16), pad, dp(context, 16))
            background = GradientDrawable().apply {
                setColor(0xEE1F2124.toInt())
                cornerRadius = dp(context, 16).toFloat()
            }
            if (!title.isNullOrBlank()) {
                addView(text(context, title, 20f, Typeface.BOLD, 0xFFFFFFFF.toInt(), screenWidth))
            }
            addView(text(context, message, 17f, Typeface.NORMAL, 0xFFD7D9DC.toInt(), screenWidth))
        }
    }

    private fun text(context: Context, value: String, sp: Float, style: Int, color: Int, screenWidth: Int) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTypeface(typeface, style)
            setTextColor(color)
            maxWidth = (screenWidth * 0.32).toInt()
        }

    private fun dp(context: Context, value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
