/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.lares_app.R

/** "Limit almost reached" notifications. Only used if the user switched them on. */
class LimitNotifier(private val context: Context) {

    /** Android 13+ needs a runtime permission; before that, notifications are always allowed. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasPermission(): Boolean = !needsPermission ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Gives the permission back once no warning is switched on (applied when the app process ends). */
    fun returnPermission() {
        if (needsPermission && hasPermission()) context.revokeSelfPermissionOnKill(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * @param subject what is counted (one notification per limit, so several limits running out
     *   at once – e.g. an app in picture-in-picture – each get their own; the 1-minute warning
     *   replaces the 5-minute one of the same limit)
     * @param label app name; @param minutes minutes left (5 or 1).
     */
    fun warn(subject: String, label: String, minutes: Int) {
        if (!hasPermission()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_limits), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.limit_warning_title, minutes, minutes))
            .setContentText(context.getString(R.string.limit_warning_text, label))
            .setAutoCancel(true)
            .setTimeoutAfter(minutes * 60_000L)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, subject.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked in the meantime.
        }
    }

    /** Removes the warning of [subject] (more time left again, or a new period). */
    fun cancel(subject: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, subject.hashCode())
    }

    private companion object {
        const val CHANNEL = "limit_warnings"
        /** Tag keeps these ids apart from the blocking-service notification. */
        const val NOTIFICATION_TAG = "limit_warning"
    }
}
