package dev.edotdemo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.edotdemo.Stats
import dev.edotdemo.Task
import kotlinx.coroutines.channels.Channel

enum class Filter(val label: String) { ALL("All"), OPEN("Open"), DONE("Done") }

/** Everything the screen renders. Only written from the main thread. */
class UiModel {
    var tasks by mutableStateOf<List<Task>>(emptyList())
    /** Number of store operations currently in flight (drives the loading indicator). */
    var busy by mutableIntStateOf(0)
    var stats by mutableStateOf<Stats?>(null)
    var searchResults by mutableStateOf<List<Task>?>(null)
    var filter by mutableStateOf(Filter.ALL)
    val messages = Channel<String>(Channel.BUFFERED)
}
