package com.recorder.core.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One place that decides what a time looks like.
 *
 * Times appeared in nine different files, each with its own SimpleDateFormat and its own
 * opinion, so a preference for one clock could only ever have been applied to some of them.
 * Everything that shows a time now comes through here, and the setting is read once at
 * start-up into a flow the UI observes — a DataStore read per rendered row would be absurd.
 *
 * Formatters are not thread-safe, so each call builds one. They are cheap next to laying
 * out the row that displays the result.
 */
object Clocks {

    private val _use24Hour = MutableStateFlow(false)

    /** Observed by the UI so changing the setting re-renders every visible timestamp. */
    val use24Hour: StateFlow<Boolean> = _use24Hour.asStateFlow()

    fun set(use24: Boolean) {
        _use24Hour.value = use24
    }

    /** "14:32" or "2:32 PM". */
    fun shortTime(ts: Long): String = format(if (_use24Hour.value) "HH:mm" else "h:mm a", ts)

    /** "14:32:05" or "2:32:05 PM" — the live feed and diagnostics, where seconds matter. */
    fun time(ts: Long): String = format(if (_use24Hour.value) "HH:mm:ss" else "h:mm:ss a", ts)

    /** "Tue 3 Mar 14:32" or "Tue 3 Mar 2:32 PM". */
    fun dayAndTime(ts: Long): String =
        format(if (_use24Hour.value) "EEE d MMM HH:mm" else "EEE d MMM h:mm a", ts)

    /** Date and time for a log line or an export header. */
    fun stamp(ts: Long): String =
        format(if (_use24Hour.value) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd h:mm:ss a", ts)

    /** A date with no time in it, which the clock setting has nothing to say about. */
    fun date(ts: Long): String = format("EEE d MMM", ts)

    /** "September 2026" — the label a month-wide group carries. */
    fun monthAndYear(ts: Long): String = format("LLLL yyyy", ts)

    private fun format(pattern: String, ts: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}
