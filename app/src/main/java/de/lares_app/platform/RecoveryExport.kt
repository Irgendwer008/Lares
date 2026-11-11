/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File

/** Copying / sharing the recovery code during setup. Nothing is stored permanently on the phone. */
object RecoveryExport {
    const val FILE_NAME = "Recovery-Codes-Lares.txt"
    private const val SHARE_DIR = "share"

    /**
     * Opens the Android share sheet. The code goes along as text (messengers, mail) and as the
     * file [FILE_NAME] (so it can also be saved via "Files" / Drive). The file lives in the
     * app's cache only until [deleteSharedFile] runs.
     */
    fun share(context: Context, fileContent: String, subject: String, chooserTitle: String) {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, FILE_NAME).apply { writeText(fileContent) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, fileContent)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(FILE_NAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Removes the temporary share file (after setup, and on every app start as a safety net). */
    fun deleteSharedFile(context: Context) {
        File(context.cacheDir, SHARE_DIR).deleteRecursively()
    }

    /** Copies to the clipboard, marked as sensitive so Android 13+ hides it in previews. */
    fun copyToClipboard(context: Context, label: String, code: String) {
        val clip = ClipData.newPlainText(label, code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
