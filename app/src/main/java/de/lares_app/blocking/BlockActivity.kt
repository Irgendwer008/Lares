/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.blocking

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.lares_app.MainActivity
import de.lares_app.R
import de.lares_app.core.policy.BlockReason
import de.lares_app.ui.theme.LaresTheme
import java.util.Date

/** Full-screen "this is blocked" page, shown by the accessibility service over a blocked app. */
class BlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val reason = intent.getStringExtra(EXTRA_REASON)?.let { runCatching { BlockReason.valueOf(it) }.getOrNull() } ?: BlockReason.ALWAYS_ON
        val group = intent.getStringExtra(EXTRA_GROUP)
        val resetAt = intent.getLongExtra(EXTRA_RESET_AT, 0L)
        val resetText = if (resetAt > 0) DateFormat.getTimeFormat(this).format(Date(resetAt)) else ""
        setContent {
            LaresTheme {
                BlockScreen(
                    label = label,
                    reason = reason,
                    group = group,
                    resetText = resetText,
                    onClose = ::goHome,
                    onUnlock = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_UNLOCK, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                        finish()
                    },
                )
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_RESET_AT = "resetAt"

        fun intent(context: Context, info: BlockInfo): Intent = Intent(context, BlockActivity::class.java)
            .putExtra(EXTRA_LABEL, info.label)
            .putExtra(EXTRA_REASON, info.reason.name)
            .putExtra(EXTRA_GROUP, info.groupName)
            .putExtra(EXTRA_RESET_AT, info.resetAtMillis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

@Composable
private fun BlockScreen(
    label: String,
    reason: BlockReason,
    group: String?,
    resetText: String,
    onClose: () -> Unit,
    onUnlock: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(R.drawable.ic_lock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Text(
                stringResource(R.string.block_title, label),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            val text = when (reason) {
                BlockReason.LIMIT_REACHED -> stringResource(R.string.block_reason_limit, resetText)
                BlockReason.UNSUPPORTED_BROWSER -> stringResource(R.string.block_reason_browser)
                BlockReason.ALWAYS_ON -> stringResource(R.string.block_reason_always)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            group?.let {
                Text(stringResource(R.string.block_group, it), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(stringResource(R.string.block_close))
            }
            OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_unlock))
            }
        }
    }
}
