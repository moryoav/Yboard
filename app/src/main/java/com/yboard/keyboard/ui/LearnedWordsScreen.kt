package com.yboard.keyboard.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yboard.keyboard.R
import com.yboard.keyboard.text.UserWords

internal const val VIEW_LEARNED_WORDS = "View learned words"

@Composable
fun LearnedWordsScreen(activeLanguage: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf(activeLanguage) }
    val hebrew = language == "he"
    val store = remember(context, language) { UserWords.get(context, language) }
    var words by remember(store) { mutableStateOf(store.learnedWords()) }

    ScreenScaffold(Screen.LEARNED_WORDS.title, onBack, scrollContent = false) {
        SegmentRow(null, listOf("English", "עברית"), if (hebrew) 1 else 0) {
            language = if (it == 1) "he" else "en"
        }
        if (words.isEmpty()) {
            val label = if (hebrew) "Hebrew" else "English"
            HintText("No learned words in $label yet.")
        } else {
            key(language) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(words, key = { _, word -> word.normalized }) { index, word ->
                        if (index > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(word.spelling, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = {
                                store.forget(word.normalized)
                                words = words.filterNot { it.normalized == word.normalized }
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = "Remove ${word.spelling}",
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
