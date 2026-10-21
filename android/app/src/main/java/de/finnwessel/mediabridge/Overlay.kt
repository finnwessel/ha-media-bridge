package de.finnwessel.mediabridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A button on a notification card: [id] goes back to Home Assistant, [title] is shown. */
data class OverlayAction(val id: String, val title: String)

/**
 * Shows a card in the top-right corner above whatever is playing, optionally
 * with an image and buttons. Without buttons the card never takes focus, so
 * the remote keeps controlling the app underneath; with buttons it takes
 * focus until a button is pressed, BACK is pressed or it times out.
 *
 * Needs the "display over other apps" app-op (granted via ADB); falls back to
 * a plain toast without it.
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var currentDone: ((String) -> Unit)? = null
    private val hide = Runnable { dismiss("timeout") }

    fun show(
        context: Context,
        title: String?,
        message: String,
        durationMs: Long,
        image: Bitmap? = null,
        actions: List<OverlayAction> = emptyList(),
        onDone: ((String) -> Unit)? = null,
    ) {
        val duration = durationMs.coerceIn(2_000L, 300_000L)
        main.post { showOnMain(context.applicationContext, title, message, duration, image, actions, onDone) }
    }

    private fun showOnMain(
        context: Context,
        title: String?,
        message: String,
        durationMs: Long,
        image: Bitmap?,
        actions: List<OverlayAction>,
        onDone: ((String) -> Unit)?,
    ) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing, using toast")
            val text = if (title.isNullOrBlank()) message else "$title\n$message"
            Toast.makeText(context, text, if (durationMs > 4000) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        dismiss("replaced")
        val wm = context.getSystemService(WindowManager::class.java)
        val interactive = actions.isNotEmpty()
        val view = buildCard(context, title, message, image, actions)
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!interactive) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
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
            currentDone = onDone
            main.removeCallbacks(hide)
            main.postDelayed(hide, durationMs)
            if (interactive) view.findViewWithTag<View>(FIRST_BUTTON)?.requestFocus()
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed: ${e.message}")
        }
    }

    /** Removes the card and reports how it ended (action id, "dismissed", "timeout" or "replaced"). */
    private fun dismiss(result: String) {
        val view = current ?: return
        val done = currentDone
        current = null
        currentDone = null
        main.removeCallbacks(hide)
        runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
        done?.invoke(result)
    }

    private fun buildCard(
        context: Context,
        title: String?,
        message: String,
        image: Bitmap?,
        actions: List<OverlayAction>,
    ): View {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val maxWidth = (screenWidth * 0.32).toInt()
        val card = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) dismiss("dismissed")
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return card.apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 20)
            setPadding(pad, dp(context, 16), pad, dp(context, 16))
            background = GradientDrawable().apply {
                setColor(0xEE1F2124.toInt())
                cornerRadius = dp(context, 16).toFloat()
            }
            if (image != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(image)
                    adjustViewBounds = true
                    this.maxWidth = maxWidth
                    maxHeight = (context.resources.displayMetrics.heightPixels * 0.4).toInt()
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dp(context, 10).toFloat() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(context, 12)
                })
            }
            if (!title.isNullOrBlank()) {
                addView(text(context, title, 20f, Typeface.BOLD, 0xFFFFFFFF.toInt(), maxWidth))
            }
            addView(text(context, message, 17f, Typeface.NORMAL, 0xFFD7D9DC.toInt(), maxWidth))
            if (actions.isNotEmpty()) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(context, 14), 0, 0)
                }
                actions.forEachIndexed { index, action ->
                    row.addView(button(context, action) { dismiss(action.id) }.apply {
                        if (index == 0) tag = FIRST_BUTTON
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) marginStart = dp(context, 10) })
                }
                addView(row)
            }
        }
    }

    private fun button(context: Context, action: OverlayAction, onClick: () -> Unit) = TextView(context).apply {
        text = action.title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, Typeface.BOLD)
        val padH = dp(context, 18)
        val padV = dp(context, 9)
        setPadding(padH, padV, padH, padV)
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(context, 0xFF3D7BFD.toInt()))
            addState(intArrayOf(), pill(context, 0xFF3A3D42.toInt()))
        }
        setOnClickListener { onClick() }
    }

    private fun pill(context: Context, color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, 20).toFloat()
    }

    private fun text(context: Context, value: String, sp: Float, style: Int, color: Int, maxWidth: Int) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTypeface(typeface, style)
            setTextColor(color)
            this.maxWidth = maxWidth
        }

    private fun dp(context: Context, value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private const val FIRST_BUTTON = "first_button"
}
