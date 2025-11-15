package com.symbioza.daymind.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LogStore(private val maxEntries: Int = 200) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun add(message: String) {
        val ts = formatter.format(Instant.now())
        val updated = listOf("[$ts] $message") + _entries.value
        _entries.value = updated.take(maxEntries)
    }
}
