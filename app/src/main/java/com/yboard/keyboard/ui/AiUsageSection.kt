package com.yboard.keyboard.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.usage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AiUsageSection() {
    val context = LocalContext.current
    var periodName by rememberSaveable { mutableStateOf(UsagePeriod.TODAY.name) }
    val period = UsagePeriod.valueOf(periodName)
    var details by rememberSaveable { mutableStateOf(false) }
    val revision by UsageStore.changes.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<UsageOverview?>(null) }
    LaunchedEffect(period, revision, tick) {
        overview = withContext(Dispatchers.IO) { UsageStore.overview(period) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    Section("Usage & estimated cost", searchable = true) {
        SegmentRow(null, listOf("Day", "Week", "Month"), period.ordinal) { periodName = UsagePeriod.entries[it].name }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val data = overview
            if (data == null || data.since == 0L && data.error == null) {
                Text("Loading usage…", style = MaterialTheme.typography.bodyMedium)
            } else if (data.error != null) {
                Text(data.error, color = MaterialTheme.colorScheme.error)
            } else {
                val total = data.totals
                val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                val end = LocalDate.now()
                val start = end.minusDays(period.days - 1)
                Text(if (period == UsagePeriod.TODAY) "Today · ${end.format(dateFormat)}" else
                    "Last ${period.days} days · ${start.format(dateFormat)} – ${end.format(dateFormat)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${if (total.incomplete > 0) "Partial estimate:" else "Estimated cost:"} ${UsageFormat.money(total.totalCost)} USD",
                    style = MaterialTheme.typography.titleLarge)
                UsageRow("Input tokens", UsageFormat.count(total.input))
                UsageRow("Cached input", UsageFormat.count(total.cached))
                if (total.writes > 0) UsageRow("Cache writes", UsageFormat.count(total.writes))
                UsageRow("Output tokens", UsageFormat.count(total.output))
                if (total.seconds > 0) {
                    UsageRow("Speech duration", String.format(Locale.getDefault(), "%.2f min", total.seconds / 60))
                    UsageRow("Speech cost (included)", UsageFormat.money(total.speechCost))
                }
                UsageRow("Calls / speech sessions", UsageFormat.count(total.requests))
                Text("Cached reads and cache writes are included in input tokens and priced separately. Output includes any reasoning tokens.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (total.requests == 0L) Text("No usage recorded in this period yet.", style = MaterialTheme.typography.bodyMedium)
                if (total.incomplete > 0) Text("${UsageFormat.count(total.incomplete)} calls could not be fully priced. Their unknown cost is excluded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (total.missingUsage > 0) Text("${UsageFormat.count(total.missingUsage)} calls did not return usage, including interrupted requests.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (total.estimatedSpeech > 0) Text("Some speech duration is estimated from audio sent because the API did not report a duration.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (data.models.isNotEmpty()) {
                    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (details) "Hide model breakdown" else "Show model breakdown")
                    }
                    if (details) data.models.forEach { model ->
                        HorizontalDivider()
                        Text(model.model, style = MaterialTheme.typography.titleSmall)
                        val modelCost = if (model.totals.incomplete > 0) {
                            if (model.totals.totalCost == 0L) "Not priced" else "Partial ${UsageFormat.money(model.totals.totalCost)}"
                        } else UsageFormat.money(model.totals.totalCost)
                        UsageRow("${UsageFormat.count(model.totals.requests)} calls / sessions", modelCost)
                        Text("Input ${UsageFormat.count(model.totals.input)} · Cached ${UsageFormat.count(model.totals.cached)} · Output ${UsageFormat.count(model.totals.output)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val since = Instant.ofEpochMilli(data.since).atZone(ZoneId.systemDefault()).toLocalDate()
                Text("Tracking since ${since.format(dateFormat)} on this phone. Earlier usage is unavailable. Estimates use saved prices, not your OpenAI bill.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UsagePricing.SOURCE))) } },
                    contentPadding = PaddingValues(0.dp)) {
                    Text("OpenAI prices · checked ${UsagePricing.CHECKED}")
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
