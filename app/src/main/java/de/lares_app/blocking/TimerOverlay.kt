/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import de.lares_app.R
import kotlin.math.ceil

/**
 * Small translucent countdown pill, centered at the top, during the last minutes of a limit.
 * An accessibility overlay: needs no extra permission, can't be touched (taps go through).
 */
class TimerOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show(remainingMillis: Long) {
        val text = DateUtils.formatElapsedTime(ceil(remainingMillis / 1000.0).toLong())
        val v = view ?: create().also { view = it }
        v.text = text
        v.contentDescription = service.getString(R.string.timer_overlay_description, text)
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun create(): TextView {
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics)
        val v = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(Color.argb(0x87, 0x21, 0x21, 0x21)) // ~70 % opaque: readable, app stays visible
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56f).toInt() // below the status bar
        }
        windowManager.addView(v, params)
        return v
    }
}
