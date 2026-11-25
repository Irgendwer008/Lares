/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lares_app.R
import de.lares_app.core.blocking.UsageSnapshot
import de.lares_app.core.data.ScheduleRepository
import de.lares_app.core.policy.PolicyEngine
import de.lares_app.core.policy.Schedule
import de.lares_app.core.policy.WindowMode
import de.lares_app.blocking.BlockingController
import de.lares_app.core.alerts.AlertChannelConfig
import de.lares_app.core.alerts.AlertChannelRepository
import de.lares_app.core.alerts.AlertSender
import de.lares_app.core.alerts.ChannelDraft
import de.lares_app.core.alerts.ChannelType
import de.lares_app.core.data.AppSettings
import de.lares_app.core.data.AppTheme
import de.lares_app.core.data.BackupCodec
import de.lares_app.core.data.BlockingMode
import de.lares_app.core.data.ImportResult
import de.lares_app.core.data.Protection
import de.lares_app.core.data.SetupIssue
import de.lares_app.core.data.SetupRequirements
import de.lares_app.core.data.AddRuleResult
import de.lares_app.core.data.CodeRequiredException
import de.lares_app.core.data.RuleRepository
import de.lares_app.core.data.SettingsRepository
import de.lares_app.core.data.StateStore
import de.lares_app.core.data.UnlockMethod
import de.lares_app.core.policy.Rule
import de.lares_app.core.policy.RuleGroup
import de.lares_app.core.policy.RuleTemplate
import de.lares_app.core.policy.RuleTemplates
import de.lares_app.core.policy.RuleType
import de.lares_app.core.policy.UrlMatcher
import de.lares_app.core.time.Clock
import de.lares_app.core.unlock.TotpSetup
import de.lares_app.core.unlock.UnlockManager
import de.lares_app.core.unlock.RestoreResult
import de.lares_app.core.unlock.TotpTransfer
import de.lares_app.core.unlock.VerifyResult
import de.lares_app.platform.AlertMessages
import de.lares_app.platform.AppCatalog
import de.lares_app.platform.ExemptApps
import de.lares_app.platform.InstalledApp
import de.lares_app.platform.LimitNotifier
import de.lares_app.platform.PermissionStatus
import de.lares_app.platform.UsageRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

internal val WhileSubscribed = SharingStarted.WhileSubscribed(5_000)

// ---- App gate: setup or home ---------------------------------------------------------------------

sealed interface AppGate {
    data object Loading : AppGate

    data class Setup(val mode: SetupMode) : AppGate

    data object Home : AppGate
}

enum class SetupMode {
    /** First start: intro, then choosing the code. */
    FIRST,

    /** The recovery code was used: a new code must be set up. */
    RECOVERY,

    /** Changed from settings while unlocked. */
    CHANGE,
}

@HiltViewModel
class MainViewModel @Inject constructor(store: StateStore) : ViewModel() {
    val gate: StateFlow<AppGate> = store.state.map { s ->
        when {
            !s.unlock.isSetUp || !s.settings.setupComplete -> AppGate.Setup(SetupMode.FIRST)
            s.unlock.reSetupRequired -> AppGate.Setup(SetupMode.RECOVERY)
            else -> AppGate.Home
        }
    }.stateIn(viewModelScope, WhileSubscribed, AppGate.Loading)
}

// ---- Setup ---------------------------------------------------------------------------------------

sealed interface SetupStep {
    /** First setup only: welcome (and on Android 12+ the choice of colors). */
    data object Welcome : SetupStep

    /** First setup only: short, skippable tutorial ([page] 0 until [TUTORIAL_PAGES]). */
    data class Tutorial(val page: Int) : SetupStep

    /** First setup only: hand the phone to the guardian. */
    data object Intro : SetupStep

    /** First setup only: the guardian may already choose the blocking mode (or decide later). */
    data object BlockingMode : SetupStep

    data object ChooseMethod : SetupStep

    data class Password(val error: PasswordError? = null) : SetupStep

    data class Totp(val setup: TotpSetup, val wrongCode: Boolean = false) : SetupStep

    data class Recovery(val code: String) : SetupStep
}

enum class PasswordError { TOO_SHORT, MISMATCH }

/** Number of tutorial pages in the first setup. */
const val TUTORIAL_PAGES = 4

data class SetupProtections(val websiteBlocking: Boolean = false, val protections: Set<Protection> = emptySet())

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val unlock: UnlockManager,
    private val settings: SettingsRepository,
    private val rules: RuleRepository,
    store: StateStore,
) : ViewModel() {
    /** Message after "Restore from export" (string resource), shown on the code page. */
    private val _restoreMessage = MutableStateFlow<Int?>(null)
    val restoreMessage: StateFlow<Int?> = _restoreMessage.asStateFlow()

    /**
     * First setup, "Restore from export": imports the file's rules (adding only) and returns its
     * authenticator package, if any – then the guardian enters the old phone's recovery code.
     */
    suspend fun importForRestore(text: String?): String? {
        val backup = text?.let { runCatching { BackupCodec.decode(it) }.getOrNull() }
        if (backup == null) {
            _restoreMessage.value = R.string.setup_restore_failed
            return null
        }
        runCatching { rules.importBackup(backup) }
        val pkg = backup.guardianTotp?.takeIf(TotpTransfer::isPackage)
        _restoreMessage.value = if (pkg == null) R.string.setup_restore_no_totp else null
        return pkg
    }

    suspend fun restoreTotp(sealed: String, recoveryCode: String): RestoreResult? =
        runCatching { unlock.restoreTotp(sealed, recoveryCode) }.getOrNull()

    /** Recovery code dialog cancelled: the rules are already in – say so. */
    fun restoreCancelled() {
        _restoreMessage.value = R.string.setup_restore_cancelled
    }

    /** Restored: the guardian already has the recovery code, so the setup is complete. */
    fun finishRestore() = finish()

    /** Colors, chosen on the first screen (before handing the phone to the guardian). */
    private val settingsState = store.state.map { it.settings }

    val theme: StateFlow<AppTheme> = settingsState.map { it.theme }.stateIn(viewModelScope, WhileSubscribed, AppTheme.LARES)

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    /** null until [start] – nothing is shown before, so no wrong step flashes up for a frame. */
    private val _step = MutableStateFlow<SetupStep?>(null)
    val step: StateFlow<SetupStep?> = _step.asStateFlow()

    private var startedMode: SetupMode? = null
    private var finished = false

    /** Emitted once the new recovery code was confirmed. */
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done

    /**
     * (Re)starts the flow. Idempotent for the same run (e.g. after rotation); starts fresh for a
     * new run, so an old recovery code is never shown again.
     */
    fun start(mode: SetupMode) {
        if (startedMode == mode && !finished) return
        startedMode = mode
        finished = false
        _step.value = if (mode == SetupMode.FIRST) SetupStep.Welcome else SetupStep.ChooseMethod
    }

    /** True while hashing / storing runs (shows a progress indicator, blocks double taps). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Unlocked mode ran out before the new code was saved: the code must be entered again. */
    private val _needsCode = MutableStateFlow(false)
    val needsCode: StateFlow<Boolean> = _needsCode.asStateFlow()

    fun codeEntered() {
        _needsCode.value = false
    }

    /** Chosen blocking mode (null = not yet), to mark the current choice. */
    val blockingMode: StateFlow<BlockingMode?> = settingsState.map { it.blockingMode }.stateIn(viewModelScope, WhileSubscribed, null)

    /** Website blocking and protections, switchable on the blocking-mode page. */
    val protections: StateFlow<SetupProtections> = settingsState.map { SetupProtections(it.websiteBlocking, it.protections) }
        .stateIn(viewModelScope, WhileSubscribed, SetupProtections())

    /**
     * Choosing the mode and the protections during the first setup is free (nothing is protected
     * until the setup is complete).
     */
    fun chooseBlockingMode(mode: BlockingMode) {
        viewModelScope.launch { runCatching { settings.setBlockingMode(mode) } }
    }

    fun setWebsiteBlocking(enabled: Boolean) {
        viewModelScope.launch { runCatching { settings.setWebsiteBlocking(enabled) } }
    }

    fun setProtection(protection: Protection, enabled: Boolean) {
        viewModelScope.launch { runCatching { settings.setProtection(protection, enabled) } }
    }

    /** Mode and protections done (a mode can also be chosen later in the settings). */
    fun continueFromMode() {
        if (_step.value == SetupStep.BlockingMode) _step.value = SetupStep.ChooseMethod
    }

    fun welcomeDone() {
        if (_step.value == SetupStep.Welcome) _step.value = SetupStep.Tutorial(0)
    }

    /** Next tutorial page, after the last one on to the guardian. */
    fun tutorialNext() {
        val step = _step.value as? SetupStep.Tutorial ?: return
        _step.value = if (step.page + 1 < TUTORIAL_PAGES) SetupStep.Tutorial(step.page + 1) else SetupStep.Intro
    }

    fun skipTutorial() {
        if (_step.value is SetupStep.Tutorial) _step.value = SetupStep.Intro
    }

    fun skipIntro() {
        if (_step.value == SetupStep.Intro) _step.value = SetupStep.BlockingMode
    }

    fun back() {
        if (_busy.value) return
        _step.value = when (_step.value) {
            is SetupStep.Password, is SetupStep.Totp -> SetupStep.ChooseMethod
            SetupStep.ChooseMethod -> if (startedMode == SetupMode.FIRST) SetupStep.BlockingMode else SetupStep.ChooseMethod
            SetupStep.BlockingMode -> SetupStep.Intro
            SetupStep.Intro -> SetupStep.Tutorial(TUTORIAL_PAGES - 1)
            is SetupStep.Tutorial -> {
                val page = (_step.value as SetupStep.Tutorial).page
                if (page > 0) SetupStep.Tutorial(page - 1) else SetupStep.Welcome
            }
            else -> SetupStep.Welcome
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
        work { _step.value = SetupStep.Recovery(unlock.setupPassword(password)) }
    }

    fun confirmTotp(code: String) {
        val current = _step.value as? SetupStep.Totp ?: return
        work {
            val recovery = unlock.confirmTotpSetup(current.setup, code)
            _step.value = if (recovery != null) SetupStep.Recovery(recovery) else current.copy(wrongCode = true)
        }
    }

    fun finish() = work {
        unlock.completeSetup()
        finished = true
        _done.tryEmit(Unit)
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (_: CodeRequiredException) {
                _needsCode.value = true
            } finally {
                _busy.value = false
            }
        }
    }
}

// ---- Home ----------------------------------------------------------------------------------------

/** @property brandColor template brand color (ARGB) for the letter icon if [icon] is missing. */
data class RuleItem(
    val rule: Rule,
    val label: String,
    val icon: ImageBitmap?,
    val brandColor: Long? = null,
    /** Foreground time today (current usage period). */
    val usedMillis: Long = 0,
    /** What its schedule means right now (standalone rules with a schedule). */
    val schedule: ScheduleStatus? = null,
)

data class GroupItem(val group: RuleGroup, val members: List<RuleItem>, val usedMillis: Long = 0, val schedule: ScheduleStatus? = null)

/**
 * What a schedule means for a rule / group right now: blocked / free until …, or a timer with
 * [usedMillis] of [budgetMinutes] until ….
 */
data class ScheduleStatus(val mode: WindowMode, val until: LocalDateTime?, val usedMillis: Long = 0, val budgetMinutes: Int = 0)

/** @param owner [PolicyEngine.ownerKey]; @param periodUsed the owner's "outside" time this period. */
private fun scheduleStatus(
    scheduleId: Long?,
    owner: String,
    periodUsed: Long,
    schedules: Map<Long, Schedule>,
    used: UsageSnapshot?,
    now: LocalDateTime,
): ScheduleStatus? {
    val id = scheduleId ?: return null
    val schedule = schedules[id] ?: return ScheduleStatus(WindowMode.BLOCK, null)
    val periodEnd = used?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.periodEndMillis), ZoneId.systemDefault()) }
    val seg = schedule.segmentAt(now)
    return when (seg.mode) {
        WindowMode.BLOCK -> ScheduleStatus(WindowMode.BLOCK, PolicyEngine.availableAt(schedule, seg.end, outsideExhausted = false, periodEnd))
        WindowMode.FREE -> ScheduleStatus(WindowMode.FREE, seg.end)
        WindowMode.TIMER -> {
            val key = seg.budgetKey
            val usedMs = if (key != null) used?.windowMillis?.get(PolicyEngine.windowUsageKey(owner, key)) ?: 0L else periodUsed
            // The "outside" budget restarts with the period.
            val until = listOfNotNull(seg.end, periodEnd.takeIf { key == null }).minOrNull()
            ScheduleStatus(WindowMode.TIMER, until, usedMs, seg.minutes)
        }
    }
}

/** Apps first (by name), then websites by base domain, so `youtube.com` and `m.youtube.com` sit together. */
private val RuleOrder: Comparator<RuleItem> = compareBy<RuleItem>(
    { it.rule.type != RuleType.APP },
    { item ->
        if (item.rule.type == RuleType.APP) {
            item.label.lowercase()
        } else {
            UrlMatcher.normalizeRule(item.rule.target)?.host?.let(UrlMatcher::baseHost) ?: item.rule.target
        }
    },
    { it.rule.target },
)

data class HomeContent(
    val groups: List<GroupItem> = emptyList(),
    val standalone: List<RuleItem> = emptyList(),
    /** Website rules only take effect while ON – the list warns otherwise. */
    val websiteBlocking: WebsiteBlockingState = WebsiteBlockingState.ON,
    /** Show the info box "website blocking is off" (not ON and not dismissed). */
    val websiteHint: Boolean = false,
    /** Every rule change needs the code (default since 1.2), not only loosening ones. */
    val everyChangeNeedsCode: Boolean = true,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && standalone.isEmpty()
}

data class SessionInfo(val pauseRemainingMillis: Long? = null, val unlockedRemainingMillis: Long? = null) {
    val isUnlocked: Boolean get() = unlockedRemainingMillis != null
}

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
    private val permissions: PermissionStatus,
    private val usage: UsageRepository,
    private val blocking: BlockingController,
    private val settings: SettingsRepository,
    scheduleRepository: ScheduleRepository,
) : ViewModel() {
    private val permissionRefresh = MutableStateFlow(0)

    /** For the "Schedule" mode and the schedule status of rules / groups (null while loading). */
    val schedules: StateFlow<List<Schedule>?> = scheduleRepository.schedules.stateIn(viewModelScope, WhileSubscribed, null)

    /** What still keeps blocking from working; re-checked when returning from the system settings. */
    val setupIssues: StateFlow<List<SetupIssue>> = combine(store.state, permissionRefresh) { s, _ ->
        SetupRequirements.missing(s.settings, permissions.current())
    }.stateIn(viewModelScope, WhileSubscribed, emptyList())

    fun refreshPermissions() {
        permissionRefresh.value++
    }

    /** Opening a system page to fix [issue]: the settings guard lets it through until it's fixed. */
    fun intentFor(issue: SetupIssue) = permissions.intentFor(issue)?.also { allowSettingsUntilFixed(issue) }

    /** Fallback page when the specific one isn't available (still for fixing [issue]). */
    fun appDetailsIntent(issue: SetupIssue) = permissions.appDetailsIntent().also { allowSettingsUntilFixed(issue) }

    private fun allowSettingsUntilFixed(issue: SetupIssue) {
        // The battery list and its per-app dialog aren't guarded pages – and this issue can be
        // left unfixed forever, so it must not open the guard for anything else.
        if (issue == SetupIssue.BATTERY_OPTIMIZATION) return
        blocking.allowSettingsWhile { settings -> issue in SetupRequirements.missing(settings, permissions.current()) }
    }

    fun dontKillMyAppUrl() = permissions.dontKillMyAppUrl()

    /** Turning website blocking on is tightening – no code. */
    fun enableWebsiteBlocking() {
        viewModelScope.launch { runCatching { settings.setWebsiteBlocking(true) } }
    }

    fun dismissWebsiteHint() {
        viewModelScope.launch { settings.dismissWebsiteHint() }
    }

    init {
        viewModelScope.launch { usage.refreshPeriod() } // load today's usage even before blocking runs
    }

    /** Schedules change their state over time: re-evaluated twice a minute. */
    private val minuteTicker = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    val content: StateFlow<HomeContent> = combine(
        combine(rules.rules, rules.groups, ::Pair),
        usage.usage,
        store.state,
        schedules,
        minuteTicker,
    ) { (list, groups), used, state, scheduleList, now ->
        // While loading, no status (an unknown schedule would read "Blocked").
        val scheduleMap = scheduleList?.associateBy { it.id }
        val items = list.map { rule ->
            when (rule.type) {
                RuleType.APP -> catalog.describeOrFallback(rule.target).let { RuleItem(rule, it.label, it.icon) }
                RuleType.WEBSITE -> RuleItem(rule, rule.target, null)
            }.copy(
                brandColor = RuleTemplates.colorFor(rule.type, rule.target),
                usedMillis = used?.ruleMillis?.get(rule.id) ?: 0,
                schedule = if (scheduleMap != null && (rule.groupId == null || groups.none { it.id == rule.groupId })) {
                    scheduleStatus(rule.scheduleId, PolicyEngine.ownerKey(rule.id, null), used?.ruleMillis?.get(rule.id) ?: 0, scheduleMap, used, now)
                } else {
                    null
                },
            )
        }
        val groupIds = groups.map { it.id }.toSet()
        HomeContent(
            groups = groups.sortedBy { it.name.lowercase() }
                .map { g ->
                    val groupUsed = used?.groupMillis?.get(g.id) ?: 0
                    GroupItem(
                        g,
                        items.filter { it.rule.groupId == g.id }.sortedWith(RuleOrder),
                        groupUsed,
                        scheduleMap?.let { scheduleStatus(g.scheduleId, PolicyEngine.ownerKey(0, g.id), groupUsed, it, used, now) },
                    )
                },
            standalone = items.filter { it.rule.groupId == null || it.rule.groupId !in groupIds }.sortedWith(RuleOrder),
            websiteBlocking = WebsiteBlockingState.of(state.settings),
            websiteHint = state.settings.setupComplete && !state.settings.websiteHintDismissed &&
                WebsiteBlockingState.of(state.settings) != WebsiteBlockingState.ON,
            everyChangeNeedsCode = !state.settings.onlyLooseningNeedsCode,
        )
    }.stateIn(viewModelScope, WhileSubscribed, HomeContent())

    /** Ticks right after every full second of the monotonic clock, so countdowns change smoothly. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1_000 - clock.elapsedMillis() % 1_000 + 1)
        }
    }

    val session: StateFlow<SessionInfo> = combine(store.state, ticker) { s, _ ->
        SessionInfo(
            pauseRemainingMillis = s.unlock.pause?.remainingMillis(clock)?.takeIf { it > 0 },
            unlockedRemainingMillis = s.unlock.unlockedSession?.remainingMillis(clock)?.takeIf { it > 0 },
        )
    }.stateIn(viewModelScope, WhileSubscribed, SessionInfo())

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events

    /** Limit and on/off in one change, so neither overwrites the other (and one policy check covers both). */
    fun updateRule(id: Long, limitMinutes: Int?, enabled: Boolean, scheduleId: Long?) = guarded { rules.updateRule(id, limitMinutes, enabled, scheduleId) }

    fun setEnabled(id: Long, enabled: Boolean) = guarded { rules.setEnabled(id, enabled) }

    fun remove(id: Long) = guarded { rules.remove(id) }

    /** Moves a rule into [groupId], or out of its group (null). */
    fun setGroup(ruleId: Long, groupId: Long?) = guarded { rules.setGroup(ruleId, groupId) }

    fun updateGroup(group: RuleGroup) = guarded { rules.updateGroup(group) }

    fun setGroupEnabled(id: Long, enabled: Boolean) = guarded { rules.setGroupEnabled(id, enabled) }

    fun deleteGroup(id: Long) = guarded { rules.deleteGroup(id) }

    fun lock() = guarded { unlock.lock() }

    fun startPause(minutes: Long) = guarded { unlock.startPause(minutes * 60_000L) }

    fun endPause() = guarded { unlock.endPause() }

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: CodeRequiredException) {
                _events.tryEmit(HomeEvent.NeedsCode)
            } catch (_: IllegalArgumentException) {
                // Rule / group vanished meanwhile (e.g. deleted in another dialog): nothing to do.
            }
        }
    }
}

// ---- Unlock (code entry) -------------------------------------------------------------------------

sealed interface UnlockUi {
    data class EnterCode(val error: CodeError? = null, val busy: Boolean = false) : UnlockUi

    data object Done : UnlockUi
}

/** Lockout ends are `elapsedRealtime` timestamps, so the dialog can count down live. */
sealed interface CodeError {
    /** @property lockedUntilElapsed set if this wrong code started a waiting time. */
    data class Wrong(val attemptsLeft: Int, val lockedUntilElapsed: Long?) : CodeError

    data class LockedOut(val lockedUntilElapsed: Long) : CodeError
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val unlock: UnlockManager,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow<UnlockUi>(UnlockUi.EnterCode())
    val ui: StateFlow<UnlockUi> = _ui.asStateFlow()

    fun reset() {
        _ui.value = UnlockUi.EnterCode()
    }

    fun submit(code: String) {
        if (code.isBlank() || (_ui.value as? UnlockUi.EnterCode)?.busy == true) return
        _ui.value = UnlockUi.EnterCode(busy = true)
        viewModelScope.launch {
            _ui.value = when (val r = unlock.unlock(code.trim())) {
                // After a recovery code, the app switches to the setup screen by itself.
                is VerifyResult.Success, VerifyResult.NotSetUp -> UnlockUi.Done
                is VerifyResult.Wrong -> UnlockUi.EnterCode(
                    CodeError.Wrong(r.attemptsLeft, r.lockoutMillis.takeIf { it > 0 }?.let { clock.elapsedMillis() + it }),
                )
                is VerifyResult.LockedOut -> UnlockUi.EnterCode(CodeError.LockedOut(clock.elapsedMillis() + r.remainingMillis))
            }
        }
    }
}

// ---- Add rule ------------------------------------------------------------------------------------

sealed interface AddRuleEvent {
    data object Added : AddRuleEvent

    /** A new group was created; continue by adding members to it. */
    data class GroupCreated(val groupId: Long) : AddRuleEvent

    data object Duplicate : AddRuleEvent

    data object Invalid : AddRuleEvent

    /** Adding rules needs the code right now (every rule change does by default). */
    data object NeedsCode : AddRuleEvent
}

/**
 * Adds standalone rules, or members of a group if the screen was opened with a `groupId`
 * navigation argument (then the group's limit applies and no limit is asked).
 */
@HiltViewModel
class AddRuleViewModel @Inject constructor(
    private val rules: RuleRepository,
    private val settings: SettingsRepository,
    catalog: AppCatalog,
    savedState: SavedStateHandle,
    private val exemptApps: ExemptApps,
    scheduleRepository: ScheduleRepository,
) : ViewModel() {
    /** Offered in the "Schedule" mode. */
    val schedules: StateFlow<List<Schedule>> = scheduleRepository.schedules.stateIn(viewModelScope, WhileSubscribed, emptyList())

    /** Website rules only take effect while website blocking is on – the screen shows a note otherwise. */
    val websiteBlocking: StateFlow<WebsiteBlockingState> = settings.settings.map { WebsiteBlockingState.of(it) }.stateIn(viewModelScope, WhileSubscribed, WebsiteBlockingState.ON)

    /** Turning website blocking on is tightening, so it's allowed without the code. */
    fun enableWebsiteBlocking() {
        viewModelScope.launch { settings.setWebsiteBlocking(true) }
    }

    val groupId: Long? = savedState.get<Long>(ARG_GROUP_ID)?.takeIf { it > 0 }

    val group: StateFlow<RuleGroup?> = rules.groups.map { list -> list.firstOrNull { it.id == groupId } }
        .stateIn(viewModelScope, WhileSubscribed, null)

    /** Installed apps without the ones that can never be blocked (launcher, phone, settings …). */
    val apps: StateFlow<List<InstalledApp>?> = flow {
        val exempt = exemptApps.packages()
        emit(catalog.launchableApps().filterNot { it.packageName in exempt })
    }.stateIn(viewModelScope, WhileSubscribed, null)

    val templates: List<RuleTemplate> = RuleTemplates.all

    private val _events = MutableSharedFlow<AddRuleEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AddRuleEvent> = _events

    fun add(type: RuleType, target: String, limitMinutes: Int?, scheduleId: Long? = null) = launchAdding {
        when (rules.add(type, target, limitMinutes, groupId, scheduleId)) {
            is AddRuleResult.Added -> AddRuleEvent.Added
            is AddRuleResult.Duplicate -> AddRuleEvent.Duplicate
            AddRuleResult.InvalidTarget -> AddRuleEvent.Invalid
        }
    }

    private fun launchAdding(block: suspend () -> AddRuleEvent) {
        viewModelScope.launch {
            _events.tryEmit(
                try {
                    block()
                } catch (_: CodeRequiredException) {
                    AddRuleEvent.NeedsCode
                },
            )
        }
    }

    fun createGroup(name: String, limitMinutes: Int?, scheduleId: Long?) {
        viewModelScope.launch { _events.tryEmit(AddRuleEvent.GroupCreated(rules.createGroup(name, limitMinutes, scheduleId))) }
    }

    /** @param asGroup create a new group named like the template, so all members share the limit. */
    fun addTemplate(template: RuleTemplate, limitMinutes: Int?, asGroup: Boolean, scheduleId: Long? = null) = launchAdding {
        run {
            val added = when {
                groupId != null -> rules.addAll(template.targets, limitMinutes, groupId)
                asGroup -> {
                    rules.createGroupWith(template.name, limitMinutes, template.targets, scheduleId)
                    1 // a new group is always "added", even if some members already existed elsewhere
                }
                else -> rules.addAll(template.targets, limitMinutes, scheduleId = scheduleId)
            }
            if (added > 0) AddRuleEvent.Added else AddRuleEvent.Duplicate
        }
    }

    companion object {
        const val ARG_GROUP_ID = "groupId"
    }
}

// ---- Settings ------------------------------------------------------------------------------------

data class SettingsUi(
    val method: UnlockMethod? = null,
    val resetTime: LocalTime = LocalTime.MIDNIGHT,
    /** A requested new reset time that applies after the next reset. */
    val pendingResetTime: LocalTime? = null,
    val unlocked: Boolean = false,
    val blockingMode: BlockingMode? = null,
    val websiteBlocking: Boolean = false,
    val protections: Set<Protection> = emptySet(),
    val channels: List<AlertChannelConfig> = emptyList(),
    val limitTimer: Boolean = true,
    val warnFiveMinutes: Boolean = false,
    val warnOneMinute: Boolean = false,
    val theme: AppTheme = AppTheme.LARES,
    val totpTransfer: TotpTransferState = TotpTransferState.NOT_TOTP,
    val exportIncludesTotp: Boolean = true,
    val onlyLooseningNeedsCode: Boolean = false,
)

/** Can the authenticator code go into the rules export? */
enum class TotpTransferState {
    /** Password (just type it again on the new phone). */
    NOT_TOTP,

    /** TOTP set up before this existed – change the code once to create the package. */
    UNAVAILABLE,
    AVAILABLE,
}

sealed interface SettingsEvent {
    data object NeedsCode : SettingsEvent

    data object OpenChangeCode : SettingsEvent

    /** Editing an existing channel is loosening: only while unlocked. */
    data class OpenChannel(val id: String) : SettingsEvent

    data class Exported(val ok: Boolean) : SettingsEvent

    /** @param totpPackage the file also contains an authenticator code (offered to take over). */
    data class Imported(val result: ImportResult?, val totpPackage: String? = null) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: StateStore,
    private val settings: SettingsRepository,
    private val unlock: UnlockManager,
    private val rules: RuleRepository,
    private val notifier: LimitNotifier,
    clock: Clock,
    savedState: SavedStateHandle,
    private val scheduleRepository: ScheduleRepository,
) : ViewModel() {
    /** Setting to scroll to and highlight (navigation argument), fixed for the lifetime of this screen. */
    val highlight: SettingsHighlight? = savedState.get<String>(ARG_HIGHLIGHT)
        ?.let { name -> SettingsHighlight.entries.firstOrNull { it.name == name } }

    /**
     * Absolute start of the highlight animation (`SystemClock.uptimeMillis`), shortly after the
     * screen transition. Kept here so the animation continues correctly if the screen's
     * composition is recreated in between.
     */
    val highlightStartUptime: Long? = highlight?.let { SystemClock.uptimeMillis() + HIGHLIGHT_DELAY_MILLIS }

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }

    val ui: StateFlow<SettingsUi> = combine(store.state, ticker) { s, _ ->
        val schedule = s.settings.resetSchedule
        SettingsUi(
            method = s.unlock.method,
            resetTime = schedule.resetTime,
            pendingResetTime = schedule.pending?.resetTime,
            unlocked = s.unlock.unlockedSession?.isActive(clock) == true,
            blockingMode = s.settings.blockingMode,
            websiteBlocking = s.settings.websiteBlocking,
            protections = s.settings.protections,
            channels = s.settings.alertChannels,
            limitTimer = s.settings.limitTimer,
            warnFiveMinutes = s.settings.warnFiveMinutes,
            warnOneMinute = s.settings.warnOneMinute,
            totpTransfer = when {
                s.unlock.method != UnlockMethod.TOTP -> TotpTransferState.NOT_TOTP
                s.unlock.totpTransfer == null -> TotpTransferState.UNAVAILABLE
                else -> TotpTransferState.AVAILABLE
            },
            exportIncludesTotp = s.settings.exportIncludesTotp,
            onlyLooseningNeedsCode = s.settings.onlyLooseningNeedsCode,
            theme = s.settings.theme,
        )
    }.stateIn(viewModelScope, WhileSubscribed, SettingsUi())

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SettingsEvent> = _events

    /** Changing the guardian code is loosening: only while unlocked. */
    fun changeCode() {
        viewModelScope.launch {
            _events.tryEmit(if (unlock.canChangeMethod()) SettingsEvent.OpenChangeCode else SettingsEvent.NeedsCode)
        }
    }

    fun openChannel(id: String) {
        viewModelScope.launch {
            _events.tryEmit(if (unlock.isUnlocked()) SettingsEvent.OpenChannel(id) else SettingsEvent.NeedsCode)
        }
    }

    companion object {
        const val ARG_HIGHLIGHT = "highlight"
        private const val HIGHLIGHT_DELAY_MILLIS = 400L
    }

    fun setResetTime(time: LocalTime) = guarded { settings.setResetTime(time) }

    fun setBlockingMode(mode: BlockingMode) = guarded { settings.setBlockingMode(mode) }

    fun setWebsiteBlocking(enabled: Boolean) = guarded { settings.setWebsiteBlocking(enabled) }

    fun setProtection(protection: Protection, enabled: Boolean) = guarded { settings.setProtection(protection, enabled) }

    fun setLimitTimer(enabled: Boolean) = guarded { settings.setLimitTimer(enabled) }

    fun setTheme(theme: AppTheme) = guarded { settings.setTheme(theme) }

    /** True if switching a warning on first needs the notification permission from the user. */
    fun warningNeedsPermission(): Boolean = !notifier.hasPermission()

    /** Sets the warnings; returns the notification permission once both are off. */
    fun setWarnings(fiveMinutes: Boolean, oneMinute: Boolean) = guarded {
        settings.setWarnings(fiveMinutes, oneMinute)
        if (!fiveMinutes && !oneMinute) notifier.returnPermission()
    }

    /** Something to export: at least one rule, group or schedule (otherwise the export is greyed out). */
    val hasRules: StateFlow<Boolean> = combine(rules.rules, rules.groups, scheduleRepository.schedules) { r, g, sc ->
        r.isNotEmpty() || g.isNotEmpty() || sc.isNotEmpty()
    }
        .stateIn(viewModelScope, WhileSubscribed, true) // not greyed out for a moment while loading

    /** Hands the rules file content (JSON, no readable secrets) to [share] (the share sheet). */
    fun shareExport(share: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { share(exportContent()) }.onFailure { _events.tryEmit(SettingsEvent.Exported(false)) }
        }
    }

    /** Writes the rules file via [write]. */
    fun export(write: suspend (String) -> Boolean) {
        viewModelScope.launch {
            val ok = runCatching { write(exportContent()) }.getOrDefault(false)
            _events.tryEmit(SettingsEvent.Exported(ok))
        }
    }

    /** Rules and groups – plus the encrypted authenticator code if it exists and is switched on. */
    private suspend fun exportContent(): String {
        val s = store.current()
        val totp = s.unlock.totpTransfer.takeIf { s.unlock.method == UnlockMethod.TOTP && s.settings.exportIncludesTotp }
        return BackupCodec.encode(rules.exportBackup().copy(guardianTotp = totp))
    }

    fun setExportIncludesTotp(enabled: Boolean) = guarded { settings.setExportIncludesTotp(enabled) }

    fun setOnlyLooseningNeedsCode(enabled: Boolean) = guarded { settings.setOnlyLooseningNeedsCode(enabled) }

    fun import(read: suspend () -> String?) {
        viewModelScope.launch {
            val backup = runCatching { read()?.let(BackupCodec::decode) }.getOrNull()
            val imported = backup?.let { runCatching { rules.importBackup(it) } }
            if (imported?.exceptionOrNull() is CodeRequiredException) {
                _events.tryEmit(SettingsEvent.NeedsCode) // every rule change needs the code
                return@launch
            }
            val result = imported?.getOrNull()
            // The authenticator code is only offered if the file was imported at all.
            // Not offered either if it's this phone's own code (same package, e.g. its own export).
            val pkg = backup?.guardianTotp?.takeIf {
                result != null && TotpTransfer.isPackage(it) && it != store.current().unlock.totpTransfer
            }
            _events.tryEmit(SettingsEvent.Imported(result, pkg))
        }
    }

    /** Taking over another phone's authenticator code replaces the guardian code: only while unlocked. */
    suspend fun canTakeOverTotp(): Boolean = unlock.canChangeMethod()

    /** @return the result; [RestoreResult.Restored] replaces the guardian code with the file's one. */
    suspend fun restoreTotp(sealed: String, recoveryCode: String): RestoreResult? =
        runCatching { unlock.restoreTotp(sealed, recoveryCode) }.getOrNull()

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: CodeRequiredException) {
                _events.tryEmit(SettingsEvent.NeedsCode)
            }
        }
    }
}

// ---- Notification channel editor -----------------------------------------------------------------

sealed interface TestState {
    data object Idle : TestState

    data object Sending : TestState

    data object Ok : TestState

    data class Failed(val reason: String?) : TestState

    /** Telegram "Detect chat ID" found no message to the bot (nothing was sent). */
    data object ChatNotFound : TestState
}

sealed interface ChannelEvent {
    data object Saved : ChannelEvent

    data object NeedsCode : ChannelEvent
}

/**
 * Add (no `channelId` argument) or edit a guardian notification channel. Sending a test message
 * works before saving, so the guardian can check the setup first.
 */
@HiltViewModel
class ChannelEditorViewModel @Inject constructor(
    private val channels: AlertChannelRepository,
    private val sender: AlertSender,
    private val messages: AlertMessages,
    savedState: SavedStateHandle,
) : ViewModel() {
    val channelId: String? = savedState.get<String>(ARG_CHANNEL_ID)?.takeIf { it.isNotBlank() }

    private val _draft = MutableStateFlow(ChannelDraft.empty(ChannelType.TELEGRAM, defaultLanguage()))
    val draft: StateFlow<ChannelDraft> = _draft.asStateFlow()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _detecting = MutableStateFlow(false)
    val detecting: StateFlow<Boolean> = _detecting.asStateFlow()

    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ChannelEvent> = _events

    init {
        channelId?.let { id -> viewModelScope.launch { channels.get(id)?.let { _draft.value = channels.draftOf(it) } } }
    }

    fun update(transform: (ChannelDraft) -> ChannelDraft) {
        _draft.value = transform(_draft.value)
        _test.value = TestState.Idle
    }

    fun setType(type: ChannelType) {
        if (type != _draft.value.type) update { ChannelDraft.empty(type, it.language) }
    }

    /** Switching the encryption also switches the port, unless a custom port was entered. */
    fun setStartTls(startTls: Boolean) = update {
        val defaultPorts = setOf(ChannelDraft.SMTPS_PORT, ChannelDraft.SUBMISSION_PORT, 0)
        it.copy(
            startTls = startTls,
            port = if (it.port in defaultPorts) (if (startTls) ChannelDraft.SUBMISSION_PORT else ChannelDraft.SMTPS_PORT) else it.port,
        )
    }

    /** ntfy topics are public by name – a long random one keeps others from reading or spamming it. */
    fun randomTopic() = update { it.copy(address = "lares-" + UUID.randomUUID().toString().replace("-", "").take(20)) }

    fun detectChatId() {
        val token = _draft.value.secret
        if (token.isBlank()) return
        _detecting.value = true
        viewModelScope.launch {
            sender.detectTelegramChatId(token)
                .onSuccess { id -> update { it.copy(address = id) } }
                .onFailure { _test.value = TestState.ChatNotFound }
            _detecting.value = false
        }
    }

    fun sendTest() {
        val d = _draft.value
        if (!d.isComplete) return
        _test.value = TestState.Sending
        viewModelScope.launch {
            val (title, text) = messages.test(d.language)
            _test.value = sender.send(d, title, text).fold({ TestState.Ok }, { TestState.Failed(it.message) })
        }
    }

    fun save() {
        val d = _draft.value
        // Checked as it will be stored (trimmed), so the only IllegalArgumentException left below
        // is "channel deleted meanwhile".
        if (!d.trimmed().isComplete) return
        viewModelScope.launch {
            try {
                if (channelId == null) channels.add(d) else channels.update(channelId, d)
                _events.tryEmit(ChannelEvent.Saved)
            } catch (_: CodeRequiredException) {
                _events.tryEmit(ChannelEvent.NeedsCode)
            } catch (_: IllegalArgumentException) {
                _events.tryEmit(ChannelEvent.Saved) // channel was deleted meanwhile: just leave
            }
        }
    }

    fun delete() {
        val id = channelId ?: return
        viewModelScope.launch {
            try {
                channels.remove(id)
                _events.tryEmit(ChannelEvent.Saved)
            } catch (_: CodeRequiredException) {
                _events.tryEmit(ChannelEvent.NeedsCode)
            }
        }
    }

    private fun defaultLanguage() = Locale.getDefault().language.takeIf { lang -> AppLanguages.any { it.first == lang } } ?: AppLanguages.first().first

    companion object {
        const val ARG_CHANNEL_ID = "channelId"
    }
}

/** ON; OFF (can be switched on); UNAVAILABLE (Usage access mode – needs Accessibility mode). */
enum class WebsiteBlockingState {
    ON,
    OFF,
    UNAVAILABLE,
    ;

    companion object {
        fun of(settings: AppSettings) = when {
            !settings.websiteBlockingAvailable -> UNAVAILABLE
            settings.websiteBlocking -> ON
            else -> OFF
        }
    }
}
