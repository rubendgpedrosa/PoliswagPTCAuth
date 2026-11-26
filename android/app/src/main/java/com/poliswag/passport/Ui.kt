package com.poliswag.passport

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** The app's dark look: colors and the few view styles every screen shares. */
object Ui {
    const val BACKGROUND = 0xFF01040A.toInt()
    const val CARD = 0xFF0C131D.toInt()
    const val FIELD = 0xFF161E2A.toInt()
    const val BORDER = 0xFF262E3B.toInt()
    const val TEXT = 0xFFF1F5FC.toInt()
    const val TEXT_MUTED = 0xFFA7B0BE.toInt()
    const val TEXT_SUBTLE = 0xFF778190.toInt()
    const val PRIMARY = 0xFF2B6CB0.toInt()
    const val PRIMARY_LIGHT = 0xFF5AA0D6.toInt()
    const val GOLD = 0xFFF4CC64.toInt()
    const val GREEN = 0xFF52CD86.toInt()
    const val RED = 0xFFE6443A.toInt()

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, context: Context, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(context, 1), strokeColor)
        }

    /** A bordered panel that groups related rows. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 16, context, BORDER)
        val p = dp(context, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 12) }
    }

    /** Small spaced-out caption above a field or a card's content. */
    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(TEXT_SUBTLE)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, 0, 0, dp(context, 6))
    }

    fun styleField(field: EditText) {
        val c = field.context
        field.background = rounded(FIELD, 12, c, BORDER)
        field.setTextColor(TEXT)
        field.setHintTextColor(TEXT_SUBTLE)
        field.textSize = 14f
        val h = dp(c, 12)
        field.setPadding(h, h, h, h)
        field.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(c, 14) }
    }

    enum class ButtonKind { PRIMARY, SECONDARY, DANGER }

    fun styleButton(button: Button, kind: ButtonKind) {
        val c = button.context
        val (fill, stroke, text) = when (kind) {
            ButtonKind.PRIMARY -> Triple(PRIMARY, null, TEXT)
            ButtonKind.SECONDARY -> Triple(FIELD, BORDER, TEXT)
            ButtonKind.DANGER -> Triple(CARD, RED, RED)
        }
        button.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            rounded(fill, 12, c, stroke),
            rounded(0xFFFFFFFF.toInt(), 12, c)
        )
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(TEXT_SUBTLE, text)
            )
        )
        button.isAllCaps = false
        button.textSize = 14f
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        button.stateListAnimator = null
        button.minHeight = dp(c, 48)
        button.minimumHeight = dp(c, 48)
        button.gravity = Gravity.CENTER
        button.maxLines = 2
        button.ellipsize = TextUtils.TruncateAt.END
        val h = dp(c, 12)
        button.setPadding(h, 0, h, 0)
    }

    /** A small filled circle, used as the status light. */
    fun dot(context: Context, color: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val size = dp(context, 10)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(context, 8) }
    }

    fun setDotColor(dot: View, color: Int) {
        (dot.background as? GradientDrawable)?.setColor(color)
    }
}
