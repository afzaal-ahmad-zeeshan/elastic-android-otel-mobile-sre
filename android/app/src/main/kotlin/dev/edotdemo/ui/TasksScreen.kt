package dev.edotdemo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.insert
import dev.edotdemo.Task
import kotlinx.coroutines.launch

/** What the screen can ask the activity to do. All of it ends up in TaskStore / the same telemetry actions as before. */
class TaskActions(
    val add: (String) -> Unit,
    val complete: (Task) -> Unit,
    val delete: (Task) -> Unit,
    val search: (String) -> Unit,
    val sync: () -> Unit,
    val stats: () -> Unit,
    val crash: () -> Unit,
    val hang: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(ui: UiModel, mode: String, user: String, actions: TaskActions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { for (m in ui.messages) snackbar.showSnackbar(m) }

    val scope = rememberCoroutineScope()
    val searchState = rememberSearchBarState()
    val queryState = rememberTextFieldState()
    val listState = rememberLazyListState()
    var showAdd by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    val toolbarExpanded by remember { derivedStateOf { !listState.canScrollBackward || listState.lastScrolledBackward } }

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            searchBarState = searchState,
            textFieldState = queryState,
            onSearch = { actions.search(it) },
            placeholder = { Text("Search tasks") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchState.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = {
                        scope.launch { searchState.animateToCollapsed() }
                        queryState.clearText()
                        ui.searchResults = null
                    }) { Icon(Icons.Default.Close, contentDescription = "Close search") }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            AppBarWithSearch(
                state = searchState,
                inputField = inputField,
                actions = {
                    if (ui.busy > 0) LoadingIndicator(Modifier.size(44.dp))
                },
            )
            ExpandedFullScreenSearchBar(state = searchState, inputField = inputField) {
                val hits = ui.searchResults
                when {
                    hits == null -> Hint("Type a word and search")
                    hits.isEmpty() -> Hint("No tasks match")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        itemsIndexed(hits, key = { _, t -> t.id }) { i, t ->
                            SegmentedListItem(
                                onClick = {},
                                shapes = ListItemDefaults.segmentedShapes(i, hits.size),
                                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                leadingContent = { CheckBadge(t.done) },
                            ) { Text(t.title, textDecoration = if (t.done) TextDecoration.LineThrough else null) }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = 88.dp)) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val open = ui.tasks.count { !it.done }
            val done = ui.tasks.size - open
            val shown = when (ui.filter) {
                Filter.ALL -> ui.tasks
                Filter.OPEN -> ui.tasks.filter { !it.done }
                Filter.DONE -> ui.tasks.filter { it.done }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(
                                onClick = {},
                                label = { Text(mode) },
                                leadingIcon = {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                                },
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(user, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "Tasks",
                            style = MaterialTheme.typography.displayLargeEmphasized,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "$open open · $done done",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ui.stats?.let { s ->
                    item(key = "stats") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).animateItem(),
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Completion", style = MaterialTheme.typography.titleLargeEmphasized)
                                LinearWavyProgressIndicator(
                                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${s.done} of ${s.total} tasks done", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                if (ui.tasks.isNotEmpty()) {
                    item(key = "filter") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            Filter.entries.forEachIndexed { i, f ->
                                ToggleButton(
                                    checked = ui.filter == f,
                                    onCheckedChange = { ui.filter = f },
                                    shapes = when (i) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        Filter.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(f.label) }
                            }
                        }
                    }
                }

                if (ui.tasks.isEmpty()) {
                    item(key = "empty") { EmptyState(onAdd = { showAdd = true }) }
                } else {
                    itemsIndexed(shown, key = { _, t -> t.id }) { i, t ->
                        TaskRow(
                            task = t, index = i, count = shown.size,
                            onComplete = { actions.complete(t) },
                            onDelete = { actions.delete(t) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            HorizontalFloatingToolbar(
                expanded = toolbarExpanded,
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add task")
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            ) {
                IconButton(onClick = actions.sync) { Icon(Icons.Default.Refresh, contentDescription = "Sync") }
                IconButton(onClick = actions.stats) { Icon(Icons.Default.Info, contentDescription = "Stats") }
                IconButton(onClick = { showDebug = true }) { Icon(Icons.Default.Build, contentDescription = "Debug") }
            }
        }
    }

    if (showAdd) AddSheet(onDismiss = { showAdd = false }, onAdd = { actions.add(it); showAdd = false })
    if (showDebug) DebugSheet(user, mode, onDismiss = { showDebug = false }, actions)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TaskRow(task: Task, index: Int, count: Int, onComplete: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> if (!task.done) onComplete()
            SwipeToDismissBoxValue.EndToStart -> onDelete()
            else -> {}
        }
        false // always spring back; the list updates when the store operation finishes
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !task.done,
        modifier = modifier,
        backgroundContent = {
            val dir = state.dismissDirection
            val color by animateColorAsState(
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }, label = "swipe-bg",
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(color).padding(horizontal = 24.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        Icon(Icons.Default.Check, "Complete", tint = MaterialTheme.colorScheme.onPrimary)
                    SwipeToDismissBoxValue.EndToStart ->
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onError)
                    else -> {}
                }
            }
        },
    ) {
        SegmentedListItem(
            onClick = { if (!task.done) onComplete() },
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(
                containerColor = if (task.done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            leadingContent = { CheckBadge(task.done) },
            supportingContent = { Text(if (task.done) "Done" else "Added ${ago(task.created)}") },
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
        }
    }
}

/** A cookie-shaped badge that pops when a task is completed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckBadge(done: Boolean) {
    val scale by animateFloatAsState(if (done) 1f else 0.85f, label = "badge-scale")
    val bg by animateColorAsState(
        if (done) MaterialTheme.colorScheme.primary else Color.Transparent, label = "badge-bg",
    )
    Box(
        Modifier.size(40.dp).scale(scale)
            .clip(if (done) MaterialShapes.Cookie9Sided.toShape() else CircleShape)
            .background(bg)
            .then(if (done) Modifier else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(168.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text("Nothing to do", style = MaterialTheme.typography.headlineMediumEmphasized)
        Text(
            "Add a task, or pull one in with Sync.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onAdd, shapes = ButtonDefaults.shapes()) { Text("Add your first task") }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AddSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val text = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Skip the half-expanded state: its fixed height would hide the field behind the keyboard.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("New task", style = MaterialTheme.typography.headlineSmallEmphasized)
            OutlinedTextField(
                state = text,
                label = { Text("What needs doing?") },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                shape = MaterialTheme.shapes.large,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Buy milk", "Email Sam", "Plan sprint", "Book dentist").forEach { s ->
                    SuggestionChip(onClick = { text.edit { replace(0, length, s) } }, label = { Text(s) })
                }
            }
            Button(
                onClick = { text.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onAdd) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Add task", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DebugSheet(user: String, mode: String, onDismiss: () -> Unit, actions: TaskActions) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Debug", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text("$mode build · $user", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { onDismiss(); actions.hang() },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Freeze the main thread for 8 s") }
            Button(
                onClick = { onDismiss(); actions.crash() },
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Crash the app") }
        }
    }
}

private fun ago(createdMs: Long): String {
    val s = ((System.currentTimeMillis() - createdMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
