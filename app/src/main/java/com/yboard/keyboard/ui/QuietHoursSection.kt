package com.yboard.keyboard.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import java.util.Date
import java.util.TimeZone

@Composable
fun QuietHoursSection(settings: YboardSettings) {
    val quiet = settings.quietHours
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val currentQuiet by rememberUpdatedState(quiet)

    Section("Quiet hours") {
        SwitchRow("Use quiet hours", "Daily, using your phone's local time", quiet.enabled) { enabled ->
            Prefs.update { it.copy(quietHours = it.quietHours.copy(enabled = enabled)) }
        }
        if (quiet.enabled) {
            QuietTimeRow("Start time", quiet.startMinute) { editing = "start" }
            QuietTimeRow("End time", quiet.endMinute) { editing = "end" }
            RowDivider()
            SwitchRow("Mute sounds", "Pause Yboard's key sounds and sound previews", quiet.muteSound) { mute ->
                Prefs.update { it.copy(quietHours = it.quietHours.copy(muteSound = mute)) }
            }
            SwitchRow("Disable vibration", "Pause Yboard's touch vibration", quiet.muteVibration) { mute ->
                Prefs.update { it.copy(quietHours = it.quietHours.copy(muteVibration = mute)) }
            }
            HintText(when {
                !quiet.muteSound && !quiet.muteVibration -> "Choose sounds, vibration, or both to pause during quiet hours."
                quiet.startMinute == quiet.endMinute -> "Matching start and end times keep quiet hours on all day."
                quiet.startMinute > quiet.endMinute -> "Ends the next day. Your usual settings resume afterward."
                else -> "Your usual sound and vibration settings resume afterward."
            })
        }
    }

    val selection = editing
    if (selection != null && quiet.enabled) {
        DisposableEffect(selection, context) {
            val minute = if (selection == "start") currentQuiet.startMinute else currentQuiet.endMinute
            val dialog = TimePickerDialog(
                context,
                R.style.Theme_Yboard_QuietHoursPicker,
                { _, hour, minuteOfHour ->
                    Prefs.update {
                        val next = if (selection == "start") it.quietHours.copy(startMinute = hour * 60 + minuteOfHour)
                        else it.quietHours.copy(endMinute = hour * 60 + minuteOfHour)
                        it.copy(quietHours = next)
                    }
                    editing = null
                },
                minute / 60, minute % 60, DateFormat.is24HourFormat(context),
            )
            dialog.setTitle(if (selection == "start") "Start time" else "End time")
            dialog.setOnDismissListener { editing = null }
            dialog.show()
            onDispose {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
    }
}

@Composable
private fun QuietTimeRow(title: String, minute: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    // Format a time of day without today's daylight-saving rules shifting the chosen hour.
    val format = DateFormat.getTimeFormat(context).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            format.format(Date(minute * 60_000L)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
