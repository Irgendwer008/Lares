/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lares_app.core.data.AddRuleResult
import de.lares_app.core.data.CodeRequiredException
import de.lares_app.core.data.RuleRepository
import de.lares_app.core.data.SettingsRepository
import de.lares_app.core.data.StateStore
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleType
import de.lares_app.core.time.Clock
import de.lares_app.core.unlock.TotpSetup
import de.lares_app.core.unlock.UnlockGrant
import de.lares_app.core.unlock.UnlockManager
import de.lares_app.core.unlock.VerifyResult
import de.lares_app.platform.AppCatalog
import de.lares_app.platform.InstalledApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val WhileSubscribed = SharingStarted.WhileSubscribed(5_000)

// ---- App gate: setup or home ---------------------------------------------------------------------

sealed interface AppGate {
    data object Loading : AppGate

    /** @property reSetup true after the recovery code was used (skip the intro). */
    data class Setup(val reSetup: Boolean) : AppGate

    data object Home : AppGate
}

@HiltViewModel
class MainViewModel @Inject constructor(store: StateStore) : ViewModel() {
    val gate: StateFlow<AppGate> = store.state.map { s ->
        when {
            s.unlock.reSetupRequired -> AppGate.Setup(reSetup = true)
            !s.unlock.isSetUp || !s.settings.setupComplete -> AppGate.Setup(reSetup = false)
            else -> AppGate.Home
        }
    }.stateIn(viewModelScope, WhileSubscribed, AppGate.Loading)
}

// ---- Setup ---------------------------------------------------------------------------------------

sealed interface SetupStep {
    data object Intro : SetupStep

    data object ChooseMethod : SetupStep

    data class Password(val error: PasswordError? = null) : SetupStep

    data class Totp(val setup: TotpSetup, val wrongCode: Boolean = false) : SetupStep

    data class Recovery(val code: String) : SetupStep
}

enum class PasswordError { TOO_SHORT, MISMATCH }

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val unlock: UnlockManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _step = MutableStateFlow<SetupStep>(SetupStep.Intro)
    val step: StateFlow<SetupStep> = _step.asStateFlow()

    fun skipIntro() {
        if (_step.value == SetupStep.Intro) _step.value = SetupStep.ChooseMethod
    }

    fun back() {
        _step.value = when (_step.value) {
            is SetupStep.Password, is SetupStep.Totp -> SetupStep.ChooseMethod
            else -> SetupStep.Intro
        }
    }

    fun choosePassword() {
        _step.value = SetupStep.Password()
    }

    fun chooseTotp() {
        _step.value = SetupStep.Totp(unlock.beginTotpSetup(account = Build.MODEL ?: "Android"))
    }

    fun submitPassword(password: String, repeat: String) {
        val error = when {
            password.length < UnlockManager.MIN_PASSWORD_LENGTH -> PasswordError.TOO_SHORT
            password != repeat -> PasswordError.MISMATCH
            else -> null
        }
        if (error != null) {
            _step.value = SetupStep.Password(error)
            return
        }
        viewModelScope.launch { _step.value = SetupStep.Recovery(unlock.setupPassword(password)) }
    }

    fun confirmTotp(code: String) {
        val current = _step.value as? SetupStep.Totp ?: return
        viewModelScope.launch {
            val recovery = unlock.confirmTotpSetup(current.setup, code)
            _step.value = if (recovery != null) SetupStep.Recovery(recovery) else current.copy(wrongCode = true)
        }
    }

    fun finish() {
        viewModelScope.launch { settings.markSetupComplete() }
    }
}

// ---- Home ----------------------------------------------------------------------------------------

data class RuleItem(val rule: Rule, val label: String, val icon: androidx.compose.ui.graphics.ImageBitmap?)

data class SessionInfo(val pauseRemainingMillis: Long? = null, val editRemainingMillis: Long? = null)

sealed interface HomeEvent {
    data object NeedsCode : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val rules: RuleRepository,
    private val unlock: UnlockManager,
    store: StateStore,
    clock: Clock,
    private val catalog: AppCatalog,
) : ViewModel() {
    val ruleItems: StateFlow<List<RuleItem>> = rules.rules.map { list ->
        list.map { rule ->
            when (rule.type) {
                RuleType.APP -> catalog.describeOrFallback(rule.target).let { RuleItem(rule, it.label, it.icon) }
                RuleType.WEBSITE -> RuleItem(rule, rule.target, null)
            }
        }
    }.stateIn(viewModelScope, WhileSubscribed, emptyList())

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }

    val session: StateFlow<SessionInfo> = combine(store.state, ticker) { s, _ ->
        SessionInfo(
            pauseRemainingMillis = s.unlock.pause?.remainingMillis(clock)?.takeIf { it > 0 },
            editRemainingMillis = s.unlock.editSession?.remainingMillis(clock)?.takeIf { it > 0 },
        )
    }.stateIn(viewModelScope, WhileSubscribed, SessionInfo())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events

    fun setLimit(id: Long, limitMinutes: Int?) = guarded { rules.setLimit(id, limitMinutes) }

    fun setEnabled(id: Long, enabled: Boolean) = guarded { rules.setEnabled(id, enabled) }

    fun remove(id: Long) = guarded { rules.remove(id) }

    fun endPause() = guarded { unlock.endPause() }

    fun endEditSession() = guarded { unlock.endEditSession() }

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: CodeRequiredException) {
                _events.tryEmit(HomeEvent.NeedsCode)
            }
        }
    }
}

// ---- Unlock (code entry → pause / edit session) --------------------------------------------------

sealed interface UnlockUi {
    data class EnterCode(val error: CodeError? = null, val busy: Boolean = false) : UnlockUi

    data class Choose(val viaRecoveryCode: Boolean) : UnlockUi

    data object Done : UnlockUi
}

sealed interface CodeError {
    data class Wrong(val attemptsBeforeLockout: Int, val lockoutMillis: Long) : CodeError

    data class LockedOut(val remainingMillis: Long) : CodeError
}

@HiltViewModel
class UnlockViewModel @Inject constructor(private val unlock: UnlockManager) : ViewModel() {
    private val _ui = MutableStateFlow<UnlockUi>(UnlockUi.EnterCode())
    val ui: StateFlow<UnlockUi> = _ui.asStateFlow()
    private var grant: UnlockGrant? = null

    fun reset() {
        grant = null
        _ui.value = UnlockUi.EnterCode()
    }

    fun submit(code: String) {
        if (code.isBlank()) return
        _ui.value = UnlockUi.EnterCode(busy = true)
        viewModelScope.launch {
            _ui.value = when (val r = unlock.verify(code.trim())) {
                is VerifyResult.Success -> {
                    grant = r.grant
                    UnlockUi.Choose(r.viaRecoveryCode)
                }
                is VerifyResult.Wrong -> UnlockUi.EnterCode(CodeError.Wrong(r.attemptsBeforeLockout, r.lockoutMillis))
                is VerifyResult.LockedOut -> UnlockUi.EnterCode(CodeError.LockedOut(r.remainingMillis))
                VerifyResult.NotSetUp -> UnlockUi.Done
            }
        }
    }

    fun startPause(minutes: Long) = start { unlock.startPause(it, minutes * 60_000L) }

    fun startEditSession(minutes: Long) = start { unlock.startEditSession(it, minutes * 60_000L) }

    private fun start(action: suspend (UnlockGrant) -> Unit) {
        val g = grant ?: return reset()
        grant = null
        viewModelScope.launch {
            runCatching { action(g) }
                .onSuccess { _ui.value = UnlockUi.Done }
                .onFailure { reset() } // grant expired: enter the code again
        }
    }
}

// ---- Add rule ------------------------------------------------------------------------------------

sealed interface AddRuleEvent {
    data object Added : AddRuleEvent

    data object Duplicate : AddRuleEvent

    data object Invalid : AddRuleEvent
}

@HiltViewModel
class AddRuleViewModel @Inject constructor(
    private val rules: RuleRepository,
    catalog: AppCatalog,
) : ViewModel() {
    val apps: StateFlow<List<InstalledApp>?> = flow { emit(catalog.launchableApps()) }
        .stateIn(viewModelScope, WhileSubscribed, null)

    private val _events = MutableSharedFlow<AddRuleEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AddRuleEvent> = _events

    fun add(type: RuleType, target: String, limitMinutes: Int?) {
        viewModelScope.launch {
            _events.tryEmit(
                when (rules.add(type, target, limitMinutes)) {
                    is AddRuleResult.Added -> AddRuleEvent.Added
                    is AddRuleResult.Duplicate -> AddRuleEvent.Duplicate
                    AddRuleResult.InvalidTarget -> AddRuleEvent.Invalid
                },
            )
        }
    }
}
