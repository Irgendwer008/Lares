/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package de.lares_app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lares_app.R
import de.lares_app.core.data.CodeRequiredException
import de.lares_app.core.data.InvalidScheduleException
import de.lares_app.core.data.RuleRepository
import de.lares_app.core.data.ScheduleInUseException
import de.lares_app.core.data.ScheduleRepository
import de.lares_app.core.policy.Schedule
import de.lares_app.core.policy.ScheduleWindow
import de.lares_app.core.policy.WindowMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---- View models -------------------------------------------------------------------------------

data class ScheduleListItem(val schedule: Schedule, val usedBy: Int)

@HiltViewModel
class SchedulesViewModel @Inject constructor(schedules: ScheduleRepository, rules: RuleRepository) : ViewModel() {
    /** null while loading. */
    val items: StateFlow<List<ScheduleListItem>?> = combine(schedules.schedules, rules.rules, rules.groups) { list, r, g ->
        list.map { s -> ScheduleListItem(s, r.count { it.scheduleId == s.id } + g.count { it.scheduleId == s.id }) }
    }.stateIn(viewModelScope, WhileSubscribed, null)
}

sealed interface ScheduleEditorEvent {
    data object Done : ScheduleEditorEvent

    data object NeedsCode : ScheduleEditorEvent

    data class Invalid(val reason: InvalidScheduleException.Reason) : ScheduleEditorEvent

    data class InUse(val usedBy: List<String>) : ScheduleEditorEvent
}

/** The unsaved draft in the SavedStateHandle (survives process death). */
@Serializable
private data class ScheduleDraft(val name: String, val outside: WindowMode, val outsideMinutes: Int, val windows: List<ScheduleWindow>) {
    fun toSchedule(id: Long) = Schedule(id, name, windows, outside, outsideMinutes)
}

@HiltViewModel
class ScheduleEditorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repo: ScheduleRepository,
) : ViewModel() {
    val scheduleId: Long? = savedState.get<Long>(ARG_ID)?.takeIf { it > 0 }
    val isNew: Boolean get() = scheduleId == null

    private val restored: Schedule? = savedState.get<String>(KEY_DRAFT)?.let { text ->
        runCatching { draftJson.decodeFromString(ScheduleDraft.serializer(), text).toSchedule(scheduleId ?: 0) }.getOrNull()
    }

    private val _draft = MutableStateFlow(restored ?: if (scheduleId == null) NEW else null)

    /** The schedule being edited (null while loading). Saved only with [save]. */
    val draft: StateFlow<Schedule?> = _draft

    private var original: Schedule? = if (scheduleId == null) NEW else null

    private val _saving = MutableStateFlow(false)

    /** A save / delete is running (the buttons are disabled meanwhile). */
    val saving: StateFlow<Boolean> = _saving

    // A channel keeps events until the screen collects them (e.g. "not found" right at the start).
    private val _events = Channel<ScheduleEditorEvent>(Channel.BUFFERED)
    val events: Flow<ScheduleEditorEvent> = _events.receiveAsFlow()

    init {
        if (scheduleId != null) {
            viewModelScope.launch {
                val loaded = repo.get(scheduleId)
                if (loaded == null) {
                    _events.send(ScheduleEditorEvent.Done)
                } else {
                    original = loaded
                    if (_draft.value == null) _draft.value = loaded
                }
            }
        }
    }

    val isDirty: Boolean get() = _draft.value != original

    fun edit(transform: (Schedule) -> Schedule) {
        val updated = _draft.value?.let(transform) ?: return
        _draft.value = updated
        savedState[KEY_DRAFT] = draftJson.encodeToString(
            ScheduleDraft.serializer(),
            ScheduleDraft(updated.name, updated.outside, updated.outsideMinutes, updated.windows),
        )
    }

    fun save() = run(onSuccess = { original = it }) { s -> if (scheduleId == null) repo.create(s) else repo.update(s) }

    fun delete() = run { _ -> repo.delete(requireNotNull(scheduleId)) }

    private fun run(onSuccess: (Schedule) -> Unit = {}, action: suspend (Schedule) -> Unit) {
        val s = _draft.value ?: return
        if (_saving.value) return // no double save
        _saving.value = true
        viewModelScope.launch {
            val event = try {
                action(s)
                onSuccess(s)
                savedState.remove<String>(KEY_DRAFT)
                ScheduleEditorEvent.Done
            } catch (_: CodeRequiredException) {
                ScheduleEditorEvent.NeedsCode
            } catch (e: InvalidScheduleException) {
                ScheduleEditorEvent.Invalid(e.reason)
            } catch (e: ScheduleInUseException) {
                ScheduleEditorEvent.InUse(e.usedBy)
            } catch (_: IllegalArgumentException) {
                ScheduleEditorEvent.Done // deleted meanwhile (e.g. elsewhere): nothing left to edit
            } finally {
                _saving.value = false
            }
            _events.send(event)
        }
    }

    companion object {
        const val ARG_ID = "scheduleId"
        private const val KEY_DRAFT = "draft"
        private val NEW = Schedule(id = 0, name = "", outside = WindowMode.FREE)
        private val draftJson = Json { ignoreUnknownKeys = true }
    }
}

// ---- List --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(onBack: () -> Unit, onOpen: (Long?) -> Unit, vm: SchedulesViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(null) }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.schedule_new))
            }
        },
    ) { padding ->
        val list = items ?: return@Scaffold
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.schedules_empty), textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        stringResource(R.string.schedules_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(list, key = { it.schedule.id }) { item ->
                    ListItem(
                        colors = clearListItemColors(),
                        headlineContent = { Text(item.schedule.name) },
                        supportingContent = {
                            val usage = if (item.usedBy == 0) {
                                stringResource(R.string.schedule_unused)
                            } else {
                                pluralStringResource(R.plurals.schedule_used_by, item.usedBy, item.usedBy)
                            }
                            Text(stringResource(R.string.schedule_list_line, scheduleSummary(item.schedule), usage))
                        },
                        leadingContent = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
                        modifier = Modifier.clickable { onOpen(item.schedule.id) },
                    )
                }
            }
        }
    }
}

/** Short description, e.g. "4 windows on Mon–Fri · outside: 60 min". */
@Composable
fun scheduleSummary(schedule: Schedule): String {
    val outside = outsideLabel(schedule.outside, schedule.outsideMinutes)
    if (schedule.windows.isEmpty()) return stringResource(R.string.schedule_summary_no_windows, outside)
    val count = schedule.windows.size
    val days = dayRanges(schedule.windows.map { it.dayOfWeek }.toSet(), currentLocale())
    return pluralStringResource(R.plurals.schedule_summary, count, count, days, outside)
}

@Composable
private fun outsideLabel(mode: WindowMode, minutes: Int): String = when (mode) {
    WindowMode.BLOCK -> stringResource(R.string.schedule_mode_block)
    WindowMode.FREE -> stringResource(R.string.schedule_mode_free)
    WindowMode.TIMER -> pluralStringResource(R.plurals.rule_limit_minutes, minutes, minutes)
}

/** "Mon–Fri, Sun" in the order of the locale's week. */
private fun dayRanges(days: Set<DayOfWeek>, locale: Locale): String {
    val order = weekDays(locale)
    val parts = mutableListOf<String>()
    var i = 0
    while (i < order.size) {
        if (order[i] !in days) {
            i++
            continue
        }
        var j = i
        while (j + 1 < order.size && order[j + 1] in days) j++
        val first = order[i].getDisplayName(TextStyle.SHORT, locale)
        val last = order[j].getDisplayName(TextStyle.SHORT, locale)
        parts += when (j - i) {
            0 -> first
            1 -> "$first, $last"
            else -> "$first–$last"
        }
        i = j + 1
    }
    return parts.joinToString(", ")
}

/** The app's current locale (follows the in-app language switch). */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** The seven days starting with the locale's first day of the week. */
internal fun weekDays(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map { first.plus(it) }
}

// ---- Editor ------------------------------------------------------------------------------------

/** A window being edited in the dialog; [isNew] = not in the schedule yet. */
@Serializable
private data class EditingWindow(val window: ScheduleWindow, val isNew: Boolean)

/** Keeps the open window dialog across rotation / process death. */
private val EditingSaver = Saver<EditingWindow?, String>(
    save = { it?.let { e -> Json.encodeToString(EditingWindow.serializer(), e) } ?: "" },
    restore = { text -> runCatching { Json.decodeFromString(EditingWindow.serializer(), text) }.getOrNull() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(onDone: () -> Unit, vm: ScheduleEditorViewModel = hiltViewModel()) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showUnlock by rememberSaveable { mutableStateOf(false) }
    var inUse by rememberSaveable { mutableStateOf<String?>(null) } // names of who uses it
    var editing by rememberSaveable(stateSaver = EditingSaver) { mutableStateOf<EditingWindow?>(null) }
    var outsideValid by rememberSaveable { mutableStateOf(true) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val needsCodeText = stringResource(R.string.schedule_needs_code)
    val enterCodeText = stringResource(R.string.home_unlock)
    val overlapText = stringResource(R.string.schedule_overlap)
    val invalidTexts = mapOf(
        InvalidScheduleException.Reason.BLANK_NAME to stringResource(R.string.schedule_name_missing),
        InvalidScheduleException.Reason.DUPLICATE_NAME to stringResource(R.string.schedule_name_taken),
        InvalidScheduleException.Reason.INVALID_WINDOWS to overlapText,
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            // Snackbars in their own coroutine: one waiting for its action doesn't hold up later events.
            when (event) {
                ScheduleEditorEvent.Done -> onDone()
                ScheduleEditorEvent.NeedsCode -> scope.launch {
                    val r = snackbar.showSnackbar(needsCodeText, actionLabel = enterCodeText, withDismissAction = true)
                    if (r == SnackbarResult.ActionPerformed) showUnlock = true
                }
                is ScheduleEditorEvent.Invalid -> scope.launch { snackbar.showSnackbar(invalidTexts.getValue(event.reason)) }
                is ScheduleEditorEvent.InUse -> inUse = event.usedBy.joinToString(", ")
            }
        }
    }
    fun cancel() {
        if (vm.isDirty) confirmCancel = true else onDone()
    }
    BackHandler { cancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isNew) R.string.schedule_new else R.string.schedule_edit)) },
                navigationIcon = {
                    IconButton(onClick = ::cancel) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    // Also the accessible way to add a window (the grid itself is gesture-based).
                    IconButton(
                        onClick = {
                            val current = draft ?: return@IconButton
                            val now = LocalDateTime.now()
                            val window = (now.hour until 24).firstNotNullOfOrNull { h -> newWindowAt(current, now.dayOfWeek, h * 60) }
                            if (window == null) scope.launch { snackbar.showSnackbar(overlapText) } else editing = EditingWindow(window, isNew = true)
                        },
                        enabled = draft != null,
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.schedule_window_add))
                    }
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }, enabled = !saving) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.schedule_delete))
                        }
                    }
                    TextButton(onClick = vm::save, enabled = draft != null && outsideValid && !saving) { Text(stringResource(R.string.save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val schedule = draft ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = schedule.name,
                    onValueChange = { v -> vm.edit { it.copy(name = v.take(MAX_NAME)) } },
                    label = { Text(stringResource(R.string.schedule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutsideSelector(
                    schedule,
                    onChange = { mode, minutes ->
                        outsideValid = minutes != null
                        vm.edit { it.copy(outside = mode, outsideMinutes = minutes ?: it.outsideMinutes) }
                    },
                )
                Text(stringResource(R.string.schedule_grid_hint), style = MaterialTheme.typography.bodySmall)
            }
            WeekGrid(
                schedule = schedule,
                onTapEmpty = { day, minute ->
                    val window = newWindowAt(schedule, day, minute)
                    if (window == null) scope.launch { snackbar.showSnackbar(overlapText) } else editing = EditingWindow(window, isNew = true)
                },
                onWindowClick = { editing = EditingWindow(it, isNew = false) },
                onDrawn = { window ->
                    if (window == null) scope.launch { snackbar.showSnackbar(overlapText) } else editing = EditingWindow(window, isNew = true)
                },
                onChange = { changed ->
                    // From the current draft – a gesture may have started before other edits.
                    val current = vm.draft.value ?: return@WeekGrid false
                    val updated = current.copy(windows = current.windows.map { if (it.id == changed.id) changed else it })
                    if (updated.isValid()) {
                        vm.edit { updated }
                        true
                    } else {
                        scope.launch { snackbar.showSnackbar(overlapText) }
                        false
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    editing?.let { e ->
        val current = draft ?: return@let
        WindowDialog(
            schedule = current,
            editing = e,
            onApply = { updated ->
                vm.edit { updated }
                editing = null
            },
            onDelete = {
                vm.edit { s -> removeWindow(s, e.window.id) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.schedule_discard_title)) },
            text = { Text(stringResource(R.string.schedule_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onDone()
                }) { Text(stringResource(R.string.schedule_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.schedule_keep_editing)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.schedule_delete)) },
            text = { Text(stringResource(R.string.schedule_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete()
                }) { Text(stringResource(R.string.schedule_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    inUse?.let { names ->
        InfoDialog(
            title = stringResource(R.string.schedule_in_use_title),
            text = stringResource(R.string.schedule_in_use_body, names),
            onDismiss = { inUse = null },
        )
    }
    if (showUnlock) UnlockDialog(onDismiss = { showUnlock = false })
}

/** "Outside the windows": free / full block / timer with minutes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutsideSelector(schedule: Schedule, onChange: (WindowMode, Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.schedule_outside), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            ModeChips(schedule.outside) { onChange(it, schedule.outsideMinutes.takeIf { m -> m > 0 } ?: DEFAULT_MINUTES) }
            if (schedule.outside == WindowMode.TIMER) {
                MinutesField(schedule.outsideMinutes, { onChange(WindowMode.TIMER, it) }, Modifier.width(120.dp))
            }
        }
    }
}

@Composable
private fun ModeChips(selected: WindowMode, onSelect: (WindowMode) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            WindowMode.FREE to R.string.schedule_mode_free,
            WindowMode.BLOCK to R.string.schedule_mode_block,
            WindowMode.TIMER to R.string.schedule_mode_timer,
        ).forEach { (mode, label) ->
            FilterChip(selected = selected == mode, onClick = { onSelect(mode) }, label = { Text(stringResource(label)) })
        }
    }
}

/** @param onChange the minutes, or null while the text isn't a valid number (then nothing may be saved). */
@Composable
private fun MinutesField(minutes: Int, onChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(minutes.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }.take(4)
            onChange(text.toIntOrNull()?.takeIf { it in 0..Schedule.MINUTES_PER_DAY })
        },
        label = { Text(stringResource(R.string.limit_minutes_label)) },
        isError = text.toIntOrNull()?.let { it in 0..Schedule.MINUTES_PER_DAY } != true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// ---- Week grid ---------------------------------------------------------------------------------

private val HOUR_HEIGHT = 44.dp
private val AXIS_WIDTH = 44.dp
private const val SNAP_MINUTES = 15
private const val DEFAULT_LENGTH = 60
private const val DEFAULT_MINUTES = 30
private const val MAX_NAME = 40
private val HANDLE_HEIGHT = 12.dp

/** Blocks smaller than this have no resize handles (they'd cover the whole block). */
private val HANDLE_MIN_BLOCK = 36.dp

/** One drawn piece of a window in a day column (a window across midnight has two). */
private data class Piece(val window: ScheduleWindow, val fromMinute: Int, val toMinute: Int, val isStart: Boolean, val isEnd: Boolean)

private fun piecesFor(schedule: Schedule, day: DayOfWeek): List<Piece> = buildList {
    for (w in schedule.windows) {
        if (w.dayOfWeek == day) add(Piece(w, w.startMinute, minOf(w.endMinute, Schedule.MINUTES_PER_DAY), isStart = true, isEnd = w.endMinute <= Schedule.MINUTES_PER_DAY))
        if (w.dayOfWeek.plus(1) == day && w.endMinute > Schedule.MINUTES_PER_DAY) {
            add(Piece(w, 0, w.endMinute - Schedule.MINUTES_PER_DAY, isStart = false, isEnd = true))
        }
    }
}

@Composable
private fun WeekGrid(
    schedule: Schedule,
    onTapEmpty: (DayOfWeek, Int) -> Unit,
    onWindowClick: (ScheduleWindow) -> Unit,
    onDrawn: (ScheduleWindow?) -> Unit,
    onChange: (ScheduleWindow) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val currentSchedule by rememberUpdatedState(schedule)
    val currentOnDrawn by rememberUpdatedState(onDrawn)
    /** A window being drawn (long press on an empty spot, then drag): its preview. */
    var drawing by remember { mutableStateOf<ScheduleWindow?>(null) }
    val locale = currentLocale()
    val days = remember(locale) { weekDays(locale) }
    val density = LocalDensity.current
    val pxPerMinute = with(density) { HOUR_HEIGHT.toPx() } / 60f
    val scroll = rememberScrollState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay((60 - now.second) * 1_000L) // the "now" line moves along every minute
        }
    }
    // Opens scrolled to the current time (a third from the top).
    LaunchedEffect(Unit) {
        val max = snapshotFlow { scroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }
        val viewport = scroll.viewportSize
        val target = (now.hour * 60 + now.minute) * pxPerMinute - viewport / 3f
        scroll.scrollTo(target.roundToInt().coerceIn(0, max))
    }
    var columnWidthPx by remember { mutableFloatStateOf(0f) }
    val outsideShade = modeColors(schedule.outside).first.copy(alpha = if (schedule.outside == WindowMode.FREE) 0f else 0.35f)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        // Weekday header (stays while the hours scroll).
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Box(Modifier.width(AXIS_WIDTH))
            for (day in days) {
                Text(
                    day.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().verticalScroll(scroll).height(HOUR_HEIGHT * 24)) {
            TimeAxis()
            for (day in days) {
                val isToday = now.dayOfWeek == day
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onSizeChanged { columnWidthPx = it.width.toFloat() }
                        .background(outsideShade)
                        .drawBehind {
                            for (h in 1 until 24) {
                                val y = h * 60 * pxPerMinute
                                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            }
                            drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                        }
                        .pointerInput(schedule, day) {
                            detectTapGestures { offset -> onTapEmpty(day, snap((offset.y / pxPerMinute).toInt(), floor = true)) }
                        }
                        // Long press on an empty spot and drag: draw a window (calendar style, within this
                        // day, stops at neighbouring windows). Without moving, the tap above adds one.
                        .pointerInput(day) {
                            var press = 0f
                            var current = 0f
                            var moved = false
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    press = offset.y / pxPerMinute
                                    current = press
                                    moved = false
                                    drawing = newWindowAt(currentSchedule, day, snap(press.toInt(), floor = true))
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    current += amount.y / pxPerMinute
                                    moved = true
                                    drawing = drawnWindow(currentSchedule, day, press, current)
                                },
                                onDragEnd = {
                                    val drawn = drawing
                                    drawing = null
                                    if (moved) currentOnDrawn(drawn)
                                },
                                onDragCancel = { drawing = null },
                            )
                        },
                ) {
                    drawing?.takeIf { it.dayOfWeek == day }?.let { preview ->
                        val (fill, content) = modeColors(WindowMode.BLOCK)
                        Box(
                            Modifier
                                .offset { IntOffset(0, (preview.startMinute * pxPerMinute).roundToInt()) }
                                .fillMaxWidth()
                                .height(with(density) { (preview.lengthMinutes * pxPerMinute).toDp() })
                                .padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(fill.copy(alpha = 0.5f))
                                .border(1.dp, content, RoundedCornerShape(4.dp)),
                        )
                    }
                    for (piece in piecesFor(schedule, day)) {
                        WindowBlock(
                            schedule = schedule,
                            piece = piece,
                            pxPerMinute = pxPerMinute,
                            columnWidthPx = columnWidthPx,
                            onClick = { onWindowClick(piece.window) },
                            onChange = onChange,
                        )
                    }
                    if (isToday) {
                        val y = (now.hour * 60 + now.minute) * pxPerMinute
                        val nowDescription = stringResource(R.string.schedule_now)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(0, y.roundToInt() - with(density) { 4.dp.roundToPx() }) }
                                .height(8.dp)
                                .semantics { contentDescription = nowDescription }
                                .drawBehind {
                                    val mid = size.height / 2
                                    drawLine(nowColor, Offset(0f, mid), Offset(size.width, mid), strokeWidth = with(density) { 2.dp.toPx() })
                                    drawCircle(nowColor, radius = with(density) { 4.dp.toPx() }, center = Offset(0f, mid))
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeAxis() {
    val context = LocalContext.current
    Column(Modifier.width(AXIS_WIDTH).fillMaxHeight()) {
        for (h in 0 until 24) {
            Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                if (h > 0) {
                    Text(
                        hourLabel(context, h),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset(y = (-7).dp),
                    )
                }
            }
        }
    }
}

/** Fill / content color of a mode. */
@Composable
internal fun modeColors(mode: WindowMode): Pair<Color, Color> = when (mode) {
    WindowMode.BLOCK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    WindowMode.TIMER -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    WindowMode.FREE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

/**
 * A window piece: tap = edit, long-press + drag = move (also to another day), long-press + drag on
 * the top / bottom edge = resize (tall enough blocks only; otherwise in the dialog). Everything
 * snaps to 15 minutes; an overlapping result is rejected.
 */
@Composable
private fun WindowBlock(
    schedule: Schedule,
    piece: Piece,
    pxPerMinute: Float,
    columnWidthPx: Float,
    onClick: () -> Unit,
    onChange: (ScheduleWindow) -> Boolean,
) {
    val w = piece.window
    val density = LocalDensity.current
    val context = LocalContext.current
    // The gestures run in long-lived coroutines: always call the latest callbacks.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnChange by rememberUpdatedState(onChange)
    var dragX by remember(w) { mutableFloatStateOf(0f) }
    var dragY by remember(w) { mutableFloatStateOf(0f) }
    var resizeTop by remember(w) { mutableFloatStateOf(0f) }
    var resizeBottom by remember(w) { mutableFloatStateOf(0f) }
    val (fill, content) = modeColors(w.mode)
    // Snapped while dragging – 15-minute steps and whole day columns, the same steps the drop uses –
    // so the block always shows where it will land.
    fun snapPx(px: Float) = snap((px / pxPerMinute).roundToInt()) * pxPerMinute
    val shownX = if (columnWidthPx > 0) (dragX / columnWidthPx).roundToInt() * columnWidthPx else 0f
    val shownTop = snapPx(resizeTop)
    val top = piece.fromMinute * pxPerMinute + snapPx(dragY) + shownTop
    val naturalHeight = (piece.toMinute - piece.fromMinute) * pxPerMinute
    val height = (naturalHeight - shownTop + snapPx(resizeBottom)).coerceAtLeast(SNAP_MINUTES * pxPerMinute)
    val handles = naturalHeight >= with(density) { HANDLE_MIN_BLOCK.toPx() }
    val label = windowLabel(schedule, w)
    val editLabel = stringResource(R.string.schedule_window_edit)
    val description = stringResource(
        R.string.schedule_window_description,
        w.dayOfWeek.getDisplayName(TextStyle.FULL, currentLocale()),
        timeLabel(context, w.startMinute),
        timeLabel(context, w.endMinute % Schedule.MINUTES_PER_DAY),
        label,
    )
    Box(
        Modifier
            .offset { IntOffset(shownX.roundToInt(), top.roundToInt()) }
            .zIndex(if (dragX != 0f || dragY != 0f) 1f else 0f)
            .fillMaxWidth()
            .height(with(density) { height.toDp() })
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            // Outline: a "full block" window stays visible on a "full block" outside shade.
            .border(1.dp, content.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .semantics {
                contentDescription = description
                onClick(editLabel) {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(w) { detectTapGestures(onTap = { currentOnClick() }) }
            .pointerInput(w, columnWidthPx) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    },
                    onDragEnd = {
                        val dayShift = if (columnWidthPx > 0) (dragX / columnWidthPx).roundToInt() else 0
                        var start = w.startMinute + snap((dragY / pxPerMinute).roundToInt())
                        var day = w.dayOfWeek.plus(dayShift.toLong())
                        while (start < 0) {
                            start += Schedule.MINUTES_PER_DAY
                            day = day.minus(1)
                        }
                        while (start >= Schedule.MINUTES_PER_DAY) {
                            start -= Schedule.MINUTES_PER_DAY
                            day = day.plus(1)
                        }
                        if (start != w.startMinute || day != w.dayOfWeek) currentOnChange(w.copy(day = day.value, startMinute = start))
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                )
            },
    ) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = content, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (height > with(density) { 40.dp.toPx() } && piece.isStart) {
                Text(
                    timeLabel(context, w.startMinute) + "–" + timeLabel(context, w.endMinute % Schedule.MINUTES_PER_DAY),
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                    maxLines = 2,
                )
            }
        }
        // Resize handles (top on the start piece, bottom on the end piece) – long-press first, so a
        // swipe starting on an edge still scrolls the grid.
        if (handles && piece.isStart) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(HANDLE_HEIGHT).pointerInput(w) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount ->
                            change.consume()
                            resizeTop += amount.y
                        },
                        onDragEnd = {
                            val delta = snap((resizeTop / pxPerMinute).roundToInt())
                            val start = w.startMinute + delta
                            val length = w.lengthMinutes - delta
                            if (delta != 0 && start in 0 until Schedule.MINUTES_PER_DAY && length >= SNAP_MINUTES) {
                                currentOnChange(w.copy(startMinute = start, lengthMinutes = length))
                            }
                            resizeTop = 0f
                        },
                        onDragCancel = { resizeTop = 0f },
                    )
                },
            )
        }
        if (handles && piece.isEnd) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(HANDLE_HEIGHT).pointerInput(w) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount ->
                            change.consume()
                            resizeBottom += amount.y
                        },
                        onDragEnd = {
                            val delta = snap((resizeBottom / pxPerMinute).roundToInt())
                            val length = w.lengthMinutes + delta
                            if (delta != 0 && length in SNAP_MINUTES..Schedule.MINUTES_PER_DAY) currentOnChange(w.copy(lengthMinutes = length))
                            resizeBottom = 0f
                        },
                        onDragCancel = { resizeBottom = 0f },
                    )
                },
            )
        }
    }
}

/** "Blocked", "30 min", "Free" – plus "· A" for a shared timer. */
@Composable
private fun windowLabel(schedule: Schedule, w: ScheduleWindow): String {
    val base = when (w.mode) {
        WindowMode.BLOCK -> stringResource(R.string.schedule_mode_block)
        WindowMode.FREE -> stringResource(R.string.schedule_mode_free)
        WindowMode.TIMER -> stringResource(R.string.schedule_minutes_short, schedule.budgetMinutes(w))
    }
    return w.shareGroup?.let { "$base · ${shareMarker(schedule, it)}" } ?: base
}

/** Letter for a shared timer group: A, B, … in the order of the groups. */
private fun shareMarker(schedule: Schedule, group: Int): String {
    val groups = schedule.windows.mapNotNull { it.shareGroup }.distinct().sorted()
    val index = groups.indexOf(group).coerceAtLeast(0)
    return ('A' + index % 26).toString()
}

private fun snap(minutes: Int, floor: Boolean = false): Int =
    if (floor) (minutes / SNAP_MINUTES) * SNAP_MINUTES else (minutes.toFloat() / SNAP_MINUTES).roundToInt() * SNAP_MINUTES

private fun is24h(context: Context) = DateFormat.is24HourFormat(context)

/** "09:00" / "9:00 AM" – as the device formats times. */
private fun timeLabel(context: Context, minuteOfDay: Int): String {
    val time = LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60)
    val pattern = if (is24h(context)) "HH:mm" else DateFormat.getBestDateTimePattern(context.resources.configuration.locales[0], "hmma")
    return time.format(DateTimeFormatter.ofPattern(pattern, context.resources.configuration.locales[0]))
}

/** Axis label: "06" / "6 AM" (localized). */
private fun hourLabel(context: Context, hour: Int): String {
    val locale = context.resources.configuration.locales[0]
    val pattern = if (is24h(context)) "HH" else DateFormat.getBestDateTimePattern(locale, "ha")
    return LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** A new 1-hour window at the tapped spot (shorter if the next window is closer), or null if there's no room. */
private fun newWindowAt(schedule: Schedule, day: DayOfWeek, minute: Int): ScheduleWindow? {
    val id = (schedule.windows.maxOfOrNull { it.id } ?: 0) + 1
    val start = minute.coerceIn(0, Schedule.MINUTES_PER_DAY - SNAP_MINUTES)
    for (length in listOf(DEFAULT_LENGTH, 45, 30, SNAP_MINUTES)) {
        val w = ScheduleWindow(id, day.value, start, length, WindowMode.BLOCK)
        if (schedule.copy(windows = schedule.windows + w).isValid()) return w
    }
    return null
}

/**
 * A window drawn by pressing at [pressMinute] and dragging to [currentMinute] on [day] (calendar
 * style, like the browser extension): from the press point down or up to the finger, snapped to
 * 15 minutes, at least 15 minutes, within the day. It stops at a neighbouring window instead of
 * overlapping it. @return the new window (full block, like a tap), or null if the press point isn't free.
 */
internal fun drawnWindow(schedule: Schedule, day: DayOfWeek, pressMinute: Float, currentMinute: Float): ScheduleWindow? {
    val id = (schedule.windows.maxOfOrNull { it.id } ?: 0) + 1
    val anchor = snap(pressMinute.toInt(), floor = true).coerceIn(0, Schedule.MINUTES_PER_DAY - SNAP_MINUTES)
    val current = currentMinute.coerceIn(0f, Schedule.MINUTES_PER_DAY.toFloat())
    fun valid(start: Int, end: Int): ScheduleWindow? {
        val w = ScheduleWindow(id, day.value, start, end - start, WindowMode.BLOCK)
        return w.takeIf { schedule.copy(windows = schedule.windows + it).isValid() }
    }
    if (current >= anchor) {
        // Downwards: the end follows the finger (rounded up), shortened until it fits.
        var end = maxOf(anchor + SNAP_MINUTES, kotlin.math.ceil(current / SNAP_MINUTES).toInt() * SNAP_MINUTES)
        while (end > anchor) {
            valid(anchor, minOf(end, Schedule.MINUTES_PER_DAY))?.let { return it }
            end -= SNAP_MINUTES
        }
        return null
    }
    // Upwards: the start follows the finger, the end is the press point's slot.
    val end = anchor + SNAP_MINUTES
    var start = snap(current.toInt(), floor = true)
    while (start < end) {
        valid(start, end)?.let { return it }
        start += SNAP_MINUTES
    }
    return null
}

/**
 * The "share the timer with" options for [candidates] (timers of the same day): windows that
 * already share a timer form one option – ticking it joins the whole group (ticking one of its
 * windows alone would pull just that window out and leave its partner on its own).
 */
internal fun shareOptions(candidates: List<ScheduleWindow>): List<List<ScheduleWindow>> {
    val sorted = candidates.sortedBy { it.startMinute }
    val groups = sorted.filter { it.shareGroup != null }.groupBy { it.shareGroup }
    val seen = mutableSetOf<Int>()
    return sorted.mapNotNull { w ->
        val g = w.shareGroup ?: return@mapNotNull listOf(w)
        if (seen.add(g)) groups.getValue(g) else null
    }
}

/** Removes a window; a shared timer group left with one window is dissolved. */
private fun removeWindow(schedule: Schedule, id: Int): Schedule =
    dissolveSingleShares(schedule.copy(windows = schedule.windows.filterNot { it.id == id }))

private fun dissolveSingleShares(schedule: Schedule): Schedule {
    val counts = schedule.windows.mapNotNull { it.shareGroup }.groupingBy { it }.eachCount()
    return schedule.copy(windows = schedule.windows.map { if (it.shareGroup != null && counts[it.shareGroup] == 1) it.copy(shareGroup = null) else it })
}

// ---- Window dialog -----------------------------------------------------------------------------

/** A set of ints across rotation / process death. */
private val IntSetSaver = Saver<Set<Int>, String>(
    save = { it.joinToString(",") },
    restore = { text -> text.split(',').mapNotNull(String::toIntOrNull).toSet() },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowDialog(
    schedule: Schedule,
    editing: EditingWindow,
    onApply: (Schedule) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val w = editing.window
    val context = LocalContext.current
    val locale = currentLocale()
    /**
     * The days the window is on (several at once instead of "copy to", like the browser extension):
     * the window itself stays on its own day if that's still selected, otherwise moves to the first
     * selected day; every other selected day gets a copy (a copy that would overlap is skipped).
     */
    var selectedDays by rememberSaveable(stateSaver = IntSetSaver) { mutableStateOf(setOf(w.day)) }
    val day = if (w.day in selectedDays) w.day else weekDays(locale).firstOrNull { it.value in selectedDays }?.value ?: w.day
    val copyDays = selectedDays - day
    var start by rememberSaveable { mutableIntStateOf(w.startMinute) }
    var end by rememberSaveable { mutableIntStateOf(w.endMinute % Schedule.MINUTES_PER_DAY) }
    var mode by rememberSaveable { mutableStateOf(w.mode) }
    /** null while the minutes field isn't a valid number. */
    var minutes by rememberSaveable { mutableStateOf<Int?>(if (w.mode == WindowMode.TIMER) schedule.budgetMinutes(w) else DEFAULT_MINUTES) }
    val initiallyShared = w.shareGroup?.let { g -> schedule.windows.filter { it.shareGroup == g && it.id != w.id }.map { it.id } }.orEmpty()
    var shared by rememberSaveable(stateSaver = IntSetSaver) { mutableStateOf(initiallyShared.toSet()) }
    var pickTime by rememberSaveable { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val overlapText = stringResource(R.string.schedule_overlap)
    val skippedText = stringResource(R.string.schedule_copy_skipped)
    val length = ((end - start + Schedule.MINUTES_PER_DAY - 1) % Schedule.MINUTES_PER_DAY) + 1 // end == start: 24 h
    val crossesMidnight = start + length > Schedule.MINUTES_PER_DAY
    // Sharing only with timers of the same (chosen) day.
    val shareCandidates = schedule.windows.filter { it.id != w.id && it.day == day && it.mode == WindowMode.TIMER }
    val timerValid = mode != WindowMode.TIMER || minutes != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing.isNew) R.string.schedule_window_new else R.string.schedule_window_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.schedule_window_days), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in weekDays(locale)) {
                        val selected = d.value in selectedDays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (!(selected && selectedDays.size == 1)) { // at least one day
                                    selectedDays = if (selected) selectedDays - d.value else selectedDays + d.value
                                    shared = emptySet() // shared timers only within one day
                                }
                            },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime = true }) { Text(timeLabel(context, start)) }
                    Text("–")
                    OutlinedButton(onClick = { pickTime = false }) { Text(timeLabel(context, end)) }
                }
                when {
                    length == Schedule.MINUTES_PER_DAY -> Text(stringResource(R.string.schedule_full_day), style = MaterialTheme.typography.bodySmall)
                    crossesMidnight -> Text(stringResource(R.string.schedule_next_day), style = MaterialTheme.typography.bodySmall)
                }
                ModeChips(mode) { mode = it }
                if (mode == WindowMode.TIMER) {
                    MinutesField(minutes ?: DEFAULT_MINUTES, { minutes = it }, Modifier.fillMaxWidth())
                    // Sharing only with one day selected; windows that already share a timer are one
                    // option, so joining keeps their whole group together.
                    if (shareCandidates.isNotEmpty() && selectedDays.size == 1) {
                        Text(stringResource(R.string.schedule_share_with), style = MaterialTheme.typography.titleSmall)
                        for (option in shareOptions(shareCandidates)) {
                            val checked = option.all { it.id in shared }
                            val ids = option.map { it.id }.toSet()
                            Row(
                                Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { shared = if (it) shared + ids else shared - ids },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                val ranges = option.joinToString(", ") {
                                    timeLabel(context, it.startMinute) + "–" + timeLabel(context, it.endMinute % Schedule.MINUTES_PER_DAY)
                                }
                                val marker = option.first().shareGroup?.let { " · " + shareMarker(schedule, it) }.orEmpty()
                                Text(ranges + marker)
                            }
                        }
                    }
                }
                if (!editing.isNew) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.schedule_window_delete), color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = timerValid,
                onClick = {
                    val result = applyWindow(
                        schedule,
                        w.copy(day = day),
                        start,
                        length,
                        mode,
                        minutes ?: 0,
                        shared.filter { id -> shareCandidates.any { it.id == id } }.toSet().takeIf { mode == WindowMode.TIMER && selectedDays.size == 1 }.orEmpty(),
                        copyDays.map { DayOfWeek.of(it) }.toSet(),
                    )
                    when {
                        result == null -> error = overlapText
                        else -> {
                            onApply(result.first)
                            if (result.second > 0) android.widget.Toast.makeText(context, skippedText, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    pickTime?.let { isStart ->
        TimeDialog(
            initialMinute = if (isStart) start else end,
            onPick = { m ->
                if (isStart) start = m else end = m
                error = null
                pickTime = null
            },
            onDismiss = { pickTime = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialMinute: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60, is24Hour = is24h(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Puts the edited window into [schedule]: day, times, mode, budget, the shared timer, and copies
 * to other days (a copy that would overlap is skipped). Sharing: the window keeps its group (or
 * gets a new one); the ticked windows join it (leaving any other group), windows of the group that
 * aren't ticked any more leave it; all members get the entered budget. Without sharing the window
 * leaves its group, the rest of the group stays.
 * @return the new schedule and the number of skipped copies, or null if the window itself overlaps.
 */
internal fun applyWindow(
    schedule: Schedule,
    edited: ScheduleWindow,
    start: Int,
    length: Int,
    mode: WindowMode,
    minutes: Int,
    sharedWith: Set<Int>,
    copyDays: Set<DayOfWeek>,
): Pair<Schedule, Int>? {
    val oldGroup = schedule.windows.firstOrNull { it.id == edited.id }?.shareGroup
    val group = when {
        sharedWith.isEmpty() -> null
        oldGroup != null -> oldGroup
        else -> (schedule.windows.mapNotNull { it.shareGroup }.maxOrNull() ?: 0) + 1
    }
    val window = edited.copy(
        startMinute = start,
        lengthMinutes = length,
        mode = mode,
        minutes = if (mode == WindowMode.TIMER) minutes else 0,
        shareGroup = group,
    )
    val windows = (schedule.windows.filterNot { it.id == window.id } + window).map { win ->
        when {
            win.id == window.id -> win
            group != null && win.id in sharedWith -> win.copy(shareGroup = group, minutes = minutes)
            // Still in its group: members not ticked any more leave it. Leaving the group itself
            // (sharing switched off, other day): the others keep sharing among themselves.
            group != null && win.shareGroup == group -> win.copy(shareGroup = null)
            else -> win
        }
    }
    var result = dissolveSingleShares(schedule.copy(windows = windows))
    if (!result.isValid()) return null
    var skipped = 0
    var nextId = (result.windows.maxOfOrNull { it.id } ?: 0) + 1
    for (day in copyDays) {
        val copy = window.copy(id = nextId, day = day.value, shareGroup = null)
        val candidate = result.copy(windows = result.windows + copy)
        if (candidate.isValid()) {
            result = candidate
            nextId++
        } else {
            skipped++
        }
    }
    return result to skipped
}
