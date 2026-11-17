package com.yboard.keyboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.settings.Prefs
import com.yboard.keyboard.settings.YboardSettings
import com.yboard.keyboard.text.Dictionary
import com.yboard.keyboard.text.MyWords
import com.yboard.keyboard.voice.ApiKeyStore
import com.yboard.keyboard.voice.OpenAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "My words": your own dictionary words (names, slang, brands), in English and Hebrew. */
@Composable
fun MyWordsScreen(settings: YboardSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    var input by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf(false) }
    // Words we already asked the AI about (so a failure doesn't repeat every time).
    val asked = remember { HashSet<String>() }

    fun addTyped() {
        MyWords.parsePair(input)?.let { (en, he) ->
            Prefs.update { s ->
                s.copy(
                    myWords = MyWords.add(s.myWords, listOf(en, he)),
                    myWordPairs = MyWords.withPair(s.myWordPairs, en, he) ?: s.myWordPairs,
                )
            }
            done = "Added “$en” and “$he”."
            input = ""
            return
        }
        val words = MyWords.parse(input)
        if (words.isEmpty()) { problem = "Type a word first."; return }
        words.firstNotNullOfOrNull { MyWords.problem(it) }?.let { problem = it; return }
        Prefs.update { it.copy(myWords = MyWords.add(it.myWords, words)) }
        done = if (words.size == 1) "Added “${words[0]}”." else "Added ${words.size} words."
        input = ""
    }

    // New words get their spelling in the other language from the AI (once), so voice typing can
    // write a name in the language you're speaking.
    LaunchedEffect(settings.myWords) {
        if (inPreview) return@LaunchedEffect
        val key = ApiKeyStore.get(context) ?: return@LaunchedEffect
        val missing = settings.myWords.filter { MyWords.pairOf(it, settings.myWordPairs) == null && it !in asked }.take(40)
        if (missing.isEmpty()) return@LaunchedEffect
        asked.addAll(missing)
        finding = true
        val found = withContext(Dispatchers.IO) {
            runCatching { OpenAi.otherSpellings(key, missing, settings.myWords) }.getOrDefault(emptyMap())
        }
        finding = false
        if (found.isNotEmpty()) {
            Prefs.update { s ->
                var pairs = s.myWordPairs
                found.forEach { (w, other) ->
                    if (MyWords.pairOf(w, pairs) == null) MyWords.withPair(pairs, w, other)?.let { pairs = it }
                }
                s.copy(myWordPairs = pairs)
            }
        }
    }

    ScreenScaffold("My words", onBack) {
        Section {
            HintText(
                "Add names, nicknames, slang and brand names Yboard doesn't know, in English or Hebrew. " +
                    "They show up in suggestions, swipe typing finds them, autocorrect won't change them, " +
                    "and voice typing spells them your way: in Hebrew when you speak Hebrew, in English when you " +
                    "speak English. They stay on this phone."
            )
        }

        Section("Add words") {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(200); problem = null; done = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                isError = problem != null,
                label = { Text("New word") },
                placeholder = { Text("Like Yoav, יואב or Yoav = יואב") },
                supportingText = {
                    Text(problem ?: done ?: "Add a few at once with spaces between them. Capital letters are kept.")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addTyped() }),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { addTyped() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Add to my words")
                }
            }
        }

        val all = settings.myWords
        Section(if (all.isEmpty()) "Your words" else "Your words (${all.size})") {
            if (all.isEmpty()) {
                HintText("No words yet. Add one above.")
            } else {
                HintText(
                    if (finding) "Finding how your words are written in the other language…"
                    else "Tap a word to change how it's written in the other language."
                )
            }
            if (all.size > 12) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    label = { Text("Find a word") },
                )
            }
            val q = Dictionary.normalize(search.trim())
            all.filter { q.isEmpty() || Dictionary.normalize(it).contains(q) }.forEach { word ->
                val hebrew = MyWords.languageOf(word) == "he"
                val other = MyWords.pairOf(word, settings.myWordPairs)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = word }
                        .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            (if (hebrew) "עברית" else "English") +
                                (other?.let { if (hebrew) " · in English: $it" else " · in Hebrew: $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        Prefs.update { s ->
                            s.copy(myWords = MyWords.remove(s.myWords, word), myWordPairs = MyWords.withoutPair(s.myWordPairs, word))
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove $word", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    editing?.let { word ->
        val hebrew = MyWords.languageOf(word) == "he"
        var other by remember(word) { mutableStateOf(MyWords.pairOf(word, settings.myWordPairs).orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (hebrew) "“$word” in English" else "“$word” in Hebrew") },
            text = {
                Column {
                    Text(
                        if (hebrew) "When you speak English, voice typing writes it like this:"
                        else "When you speak Hebrew, voice typing writes it like this:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = other,
                        onValueChange = { other = it.take(40) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        singleLine = true,
                        placeholder = { Text(if (hebrew) "Yoav" else "יואב") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.update { s ->
                        s.copy(
                            myWordPairs = if (other.isBlank()) MyWords.withoutPair(s.myWordPairs, word)
                            else MyWords.withPair(s.myWordPairs, word, other) ?: s.myWordPairs
                        )
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

/** "Names and slang Yboard doesn't know" / "3 words: Yoav, Moran, WhatsApp". */
fun myWordsSummary(settings: YboardSettings): String {
    val w = settings.myWords
    return when {
        w.isEmpty() -> "Add names and slang Yboard doesn't know"
        w.size == 1 -> "1 word: ${w[0]}"
        else -> "${w.size} words: " + w.take(3).joinToString(", ") + if (w.size > 3) "…" else ""
    }
}
