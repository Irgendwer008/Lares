/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import de.lares_app.R
import kotlin.math.ceil

/**
 * Small translucent countdown pills, centered at the top, during the last minutes of a limit –
 * one per limit running out (e.g. the app in front and one in picture-in-picture), stacked
 * downward, each with the icon of its app. Can't be touched (taps go through). [windowType]:
 * `TYPE_ACCESSIBILITY_OVERLAY` from the accessibility service (no extra permission),
 * `TYPE_APPLICATION_OVERLAY` in Usage access mode (needs "display over other apps").
 */
class TimerOverlay(private val service: Context, private val windowType: Int) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var container: LinearLayout? = null
    private val pills = LinkedHashMap<String, TextView>()
    private val icons = HashMap<String, Drawable?>()
    private val labels = HashMap<String, String>()

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics)

    /** Shows [items] (in this order, top to bottom); an empty list removes the overlay. */
    fun show(items: List<TimerItem>) {
        if (items.isEmpty()) return hide()
        val c = container ?: create()?.also { container = it } ?: return
        if (items.map { it.packageName } != pills.keys.toList()) {
            c.removeAllViews()
            pills.clear()
            items.forEachIndexed { index, item ->
                val pill = pill(item.packageName)
                c.addView(
                    pill,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) topMargin = dp(6f).toInt()
                    },
                )
                pills[item.packageName] = pill
            }
        }
        items.forEach { item ->
            val text = DateUtils.formatElapsedTime(ceil(item.remainingMillis / 1000.0).toLong())
            pills[item.packageName]?.apply {
                this.text = text
                contentDescription = service.getString(R.string.timer_overlay_app_description, label(item.packageName), text)
            }
        }
    }

    fun hide() {
        container?.let { runCatching { windowManager.removeView(it) } }
        container = null
        pills.clear()
    }

    private fun pill(packageName: String) = TextView(service).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        // The app's own icon in front of the time, so it's clear which limit is running out.
        setCompoundDrawablesRelative(icon(packageName), null, null, null)
        compoundDrawablePadding = dp(6f).toInt()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setColor(Color.argb(0x87, 0x21, 0x21, 0x21)) // ~70 % opaque: readable, app stays visible
        }
    }

    /** App icon (cached), or the Lares "L" if it can't be loaded. */
    private fun icon(packageName: String): Drawable? = icons.getOrPut(packageName) {
        val size = dp(18f).toInt()
        val app = runCatching { service.packageManager.getApplicationIcon(packageName) }.getOrNull()?.mutate()
        app?.apply { setBounds(0, 0, size, size) }
            ?: AppCompatResources.getDrawable(service, R.drawable.ic_lares)?.mutate()?.apply {
                setTint(Color.WHITE)
                val h = dp(10.5f).toInt() // about the height of the digits at 14 sp
                setBounds(0, 0, h * intrinsicWidth / intrinsicHeight.coerceAtLeast(1), h)
            }
    }

    private fun label(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    private fun create(): LinearLayout? {
        val c = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
        return c.takeIf { runCatching { windowManager.addView(c, params) }.isSuccess }
    }
}
