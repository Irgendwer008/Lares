/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import de.lares_app.R
import kotlin.math.ceil

/**
 * Small translucent countdown pill, centered at the top, during the last minutes of a limit.
 * Can't be touched (taps go through). [windowType]: `TYPE_ACCESSIBILITY_OVERLAY` from the
 * accessibility service (no extra permission), `TYPE_APPLICATION_OVERLAY` in Usage access mode
 * (needs "display over other apps").
 */
class TimerOverlay(private val service: Context, private val windowType: Int) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show(remainingMillis: Long) {
        val text = DateUtils.formatElapsedTime(ceil(remainingMillis / 1000.0).toLong())
        val v = view ?: create()?.also { view = it } ?: return
        v.text = text
        v.contentDescription = service.getString(R.string.timer_overlay_description, text)
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }

    private fun create(): TextView? {
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics)
        val v = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // Small Lares "L" in front of the time, so it's clear where the countdown comes from.
            val mark = AppCompatResources.getDrawable(service, R.drawable.ic_lares)?.mutate()?.apply {
                setTint(Color.WHITE)
                val h = dp(10.5f).toInt() // about the height of the digits at 14 sp
                setBounds(0, 0, h * intrinsicWidth / intrinsicHeight.coerceAtLeast(1), h)
            }
            setCompoundDrawablesRelative(mark, null, null, null)
            compoundDrawablePadding = dp(6f).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(Color.argb(0x87, 0x21, 0x21, 0x21)) // ~70 % opaque: readable, app stays visible
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Android 12+ drops touches under opaque overlays of other apps; at most 0.8 they pass.
            if (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) alpha = 0.8f
            y = dp(56f).toInt() // below the status bar
        }
        // Fails if the overlay permission was taken away – then there's no countdown.
        return v.takeIf { runCatching { windowManager.addView(v, params) }.isSuccess }
    }
}
