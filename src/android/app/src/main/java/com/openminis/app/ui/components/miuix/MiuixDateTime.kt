package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text

@Stable
class TimePickerState(initialHour: Int, initialMinute: Int) {
    var hour by mutableIntStateOf(initialHour.coerceIn(0, 23))
    var minute by mutableIntStateOf(initialMinute.coerceIn(0, 59))
}
@Composable
fun rememberTimePickerState(initialHour: Int, initialMinute: Int, is24Hour: Boolean = true): TimePickerState =
    remember { TimePickerState(initialHour, initialMinute) }
@Composable
fun TimeInput(state: TimePickerState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        NumberPicker(state.hour, { state.hour = it }, range = 0..23, modifier = Modifier.weight(1f),
            label = { "%02d".format(it) }, visibleItemCount = 3, wrapAround = true)
        Text(":")
        NumberPicker(state.minute, { state.minute = it }, range = 0..59, modifier = Modifier.weight(1f),
            label = { "%02d".format(it) }, visibleItemCount = 3, wrapAround = true)
    }
}
@Stable
class DatePickerState(initial: Long?) {
    var date by mutableStateOf(initial?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: LocalDate.now())
    val selectedDateMillis: Long? get() = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
@Composable
fun rememberDatePickerState(initialSelectedDateMillis: Long?): DatePickerState = remember { DatePickerState(initialSelectedDateMillis) }
@Composable
fun DatePickerDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit, content: @Composable () -> Unit) =
    com.openminis.app.ui.components.MinisDialog(show = true, onDismissRequest = onDismissRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
            confirmButton()
            dismissButton()
        }
    }
@Composable
fun DatePicker(state: DatePickerState) {
    val date = state.date
    fun update(year: Int = date.year, month: Int = date.monthValue, day: Int = date.dayOfMonth) {
        val first = LocalDate.of(year, month, 1)
        state.date = first.withDayOfMonth(day.coerceAtMost(first.lengthOfMonth()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberPicker(date.year, { update(year = it) }, modifier = Modifier.weight(1.4f),
            range = 1900..2100, visibleItemCount = 3)
        NumberPicker(date.monthValue, { update(month = it) }, modifier = Modifier.weight(1f),
            range = 1..12, visibleItemCount = 3)
        NumberPicker(date.dayOfMonth, { update(day = it) }, modifier = Modifier.weight(1f),
            range = 1..date.lengthOfMonth(), visibleItemCount = 3)
    }
}
