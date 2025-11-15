package com.symbioza.daymind.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class LogStore(private val maxEntries: Int = 200) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun add(message: String, notify: Boolean = false) {
        val ts = formatter.format(Instant.now())
        val updated = listOf("[$ts] $message") + _entries.value
        _entries.value = updated.take(maxEntries)
        if (notify) {
            _events.tryEmit(message)
        }
    }
}
