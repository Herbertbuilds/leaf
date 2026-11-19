package io.herbert.leaf

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView

/** Palette and the handful of view factories the two activities share. */
object Ui {

    val DARK_BG = 0xFF0F1214.toInt()
    val DARK_SURFACE = 0xFF161A1D.toInt()
    val DARK_TEXT = 0xFFE8EAED.toInt()
    val DARK_DIM = 0xFF8B9297.toInt()
    val DARK_ACCENT = 0xFF66D9A8.toInt()

    val LIGHT_BG = 0xFFFBFAF7.toInt()
    val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
    val LIGHT_TEXT = 0xFF1B1B1B.toInt()
    val LIGHT_DIM = 0xFF5F6368.toInt()
    val LIGHT_ACCENT = 0xFF0F7B5F.toInt()

    fun dp(ctx: Context, v: Int): Int = Math.round(v * ctx.resources.displayMetrics.density)

    /**
     * Dialog and popup colours come from the window theme, not from the colours this app
     * paints by hand. The activity theme is Material (dark), so a light-theme reader would
     * otherwise still get dark dialogs; these wrappers pick the matching platform theme.
     */
    fun dialogTheme(dark: Boolean): Int =
        if (dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    fun dialogContext(ctx: Context, dark: Boolean): Context =
        ContextThemeWrapper(ctx, dialogTheme(dark))

    /** For widgets the platform styles itself, such as a PopupMenu's item list. */
    fun chromeContext(ctx: Context, dark: Boolean): Context =
        ContextThemeWrapper(ctx, if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)

    /** Background behind the current text-search hit. Kept muted so the text stays readable. */
    fun hitBg(dark: Boolean) = if (dark) 0xFF3C5A4C.toInt() else 0xFFFFE9A3.toInt()

    fun bg(dark: Boolean) = if (dark) DARK_BG else LIGHT_BG
    fun surface(dark: Boolean) = if (dark) DARK_SURFACE else LIGHT_SURFACE
    fun text(dark: Boolean) = if (dark) DARK_TEXT else LIGHT_TEXT
    fun dim(dark: Boolean) = if (dark) DARK_DIM else LIGHT_DIM
    fun accent(dark: Boolean) = if (dark) DARK_ACCENT else LIGHT_ACCENT

    fun round(color: Int, radiusDp: Float, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        }

    /** Keeps the status bar in step with the reader theme, with the right icon tint. */
    @Suppress("DEPRECATION")
    fun systemBars(activity: Activity, dark: Boolean) {
        val w = activity.window
        w.statusBarColor = surface(dark)
        w.navigationBarColor = bg(dark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.isStatusBarContrastEnforced = false
            w.isNavigationBarContrastEnforced = false
        }
        var flags = w.decorView.systemUiVisibility
        flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        w.decorView.systemUiVisibility = flags
    }

    fun label(ctx: Context, sizeSp: Float, color: Int, text: String = ""): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            includeFontPadding = false
        }

    fun bold(ctx: Context, sizeSp: Float, color: Int, text: String = ""): TextView =
        label(ctx, sizeSp, color, text).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    /** Transparent, used for list backgrounds. */
    val COLOR_NONE = Color.TRANSPARENT
}
