package com.yboard.keyboard.ime

import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One editor in one recording. Observes edits without changing what is sent to the editor. */
internal class TypingTrace(val recording: String, info: EditorInfo, restarting: Boolean) {
    private val editor = UUID.randomUUID().toString()
    private var lastSnapshot: Snapshot? = null
    private var numberedEvent = 0
    private data class Strip(val source: String, val words: List<String>, val primary: Int, val before: String?, val cursor: Int)
    private var lastStrip: Strip? = null

    init {
        event("editor_started") {
            put("app", info.packageName)
            put("inputType", info.inputType)
            put("imeOptions", info.imeOptions)
            put("restarting", restarting)
            put("selection", JSONArray(listOf(info.initialSelStart, info.initialSelEnd)))
        }
    }

    fun nextId() = ++numberedEvent

    fun event(name: String, fields: JSONObject.() -> Unit = {}) {
        if (TypingDiagnostics.recording == recording) TypingDiagnostics.record(name, editor, fields)
    }

    fun strip(source: String, words: List<String>, primary: Int, before: String?, cursor: Int) {
        val strip = Strip(source, words, primary, before, cursor)
        if (strip == lastStrip) return
        lastStrip = strip
        event("suggestions_shown") {
            put("source", source)
            put("slots", JSONArray(words))
            put("primary", primary)
            put("beforeCursor", before)
            put("cursor", cursor)
        }
    }

    fun suggestionSource() = lastStrip?.source

    fun selection(connection: InputConnection?, oldStart: Int, oldEnd: Int, start: Int, end: Int) {
        val prior = lastSnapshot
        val captured = if (prior?.start == minOf(start, end) && prior.end == maxOf(start, end)) null
            else connection?.let(::snapshot)
        if (captured != null) lastSnapshot = captured
        event("selection") {
            put("from", JSONArray(listOf(oldStart, oldEnd)))
            put("to", JSONArray(listOf(start, end)))
            put("editorText", captured?.json())
            put("changeSinceLastObservation", prior?.changeTo(captured))
        }
    }

    fun observe(connection: InputConnection, source: String, operation: String, action: () -> Unit) {
        val before = snapshot(connection)
        try {
            action()
        } finally {
            changed(connection, source, before, JSONArray().put(JSONObject().put("operation", operation)))
        }
    }

    fun wrap(connection: InputConnection, source: String, relatedId: Int?): InputConnection =
        ObservedConnection(connection, source, relatedId)

    private inner class ObservedConnection(
        private val target: InputConnection,
        private val source: String,
        private val relatedId: Int?,
    ) : InputConnectionWrapper(target, false) {
        private var depth = 0
        private var before: Snapshot? = null
        private var operations = JSONArray()

        private inline fun edit(operation: String, fields: JSONObject.() -> Unit = {}, action: () -> Boolean): Boolean {
            if (TypingDiagnostics.recording != recording) return action()
            if (operations.length() == 0) before = snapshot(target)
            val result = action()
            operations.put(JSONObject().put("operation", operation).put("accepted", result).apply(fields))
            if (depth == 0) flush()
            return result
        }

        private fun flush() {
            if (operations.length() == 0) return
            changed(target, source, before, operations, relatedId)
            operations = JSONArray()
            before = null
        }

        override fun beginBatchEdit(): Boolean {
            val result = super.beginBatchEdit()
            depth++
            return result
        }

        override fun endBatchEdit(): Boolean {
            val result = super.endBatchEdit()
            if (depth > 0) depth--
            if (depth == 0) flush()
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int) =
            edit("commit", { put("text", text?.toString()); put("cursor", newCursorPosition) }) {
                super.commitText(text, newCursorPosition)
            }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
            edit("compose", { put("text", text?.toString()); put("cursor", newCursorPosition) }) {
                super.setComposingText(text, newCursorPosition)
            }

        override fun finishComposingText() = edit("finish_composing") { super.finishComposingText() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            edit("delete", { put("before", beforeLength); put("after", afterLength) }) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }

        @RequiresApi(34)
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit("replace", { put("start", start); put("end", end); put("text", text.toString()); put("cursor", newCursorPosition) }) {
                super.replaceText(start, end, text, newCursorPosition, textAttribute)
            }

        override fun setSelection(start: Int, end: Int) =
            edit("select", { put("start", start); put("end", end) }) { super.setSelection(start, end) }

        override fun performEditorAction(editorAction: Int) =
            edit("editor_action", { put("action", editorAction) }) { super.performEditorAction(editorAction) }

        override fun performContextMenuAction(id: Int) =
            edit("context_menu", { put("action", id) }) { super.performContextMenuAction(id) }

        override fun sendKeyEvent(event: KeyEvent) =
            edit("send_key", {
                put("key", KeyEvent.keyCodeToString(event.keyCode))
                put("action", event.action)
                put("modifiers", event.metaState)
            }) { super.sendKeyEvent(event) }
    }

    private fun changed(connection: InputConnection, source: String, before: Snapshot?, operations: JSONArray, relatedId: Int? = null) {
        if (TypingDiagnostics.recording != recording) return
        val sinceLastEdit = lastSnapshot?.changeTo(before)
        val after = snapshot(connection)
        lastSnapshot = after
        event("editor_edit") {
            put("source", source)
            put("relatedId", relatedId)
            put("operations", operations)
            put("before", before?.json())
            put("after", after?.json())
            put("changeBeforeOperation", sinceLastEdit)
            put("change", before?.changeTo(after))
        }
    }

    private data class Snapshot(val before: String, val selected: String?, val after: String?, val offset: Int?) {
        val start get() = offset?.plus(before.length)
        val end get() = start?.plus(selected.orEmpty().length)

        fun json() = JSONObject()
            .put("beforeCursor", before)
            .put("selected", selected)
            .put("afterCursor", after)
            .put("offset", offset)

        // Compare only when the two windows start at the same offset and reach the end of the field.
        fun changeTo(next: Snapshot?): JSONObject? {
            if (next == null || offset == null || offset != next.offset ||
                after == null || next.after == null || after.length >= AFTER || next.after.length >= AFTER) return null
            val old = before + selected.orEmpty() + after
            val new = next.before + next.selected.orEmpty() + next.after
            if (old == new) return null
            var left = old.commonPrefixWith(new).length
            if (left > 0 && old[left - 1].isHighSurrogate()) left--
            var right = old.substring(left).commonSuffixWith(new.substring(left)).length
            if (right > 0 && old[old.length - right].isLowSurrogate()) right--
            return JSONObject().put("start", offset + left)
                .put("removed", old.substring(left, old.length - right))
                .put("inserted", new.substring(left, new.length - right))
        }
    }

    private fun snapshot(connection: InputConnection): Snapshot? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            val text = connection.getSurroundingText(BEFORE, AFTER, 0)
            if (text != null) {
                return Snapshot(
                    text.text.subSequence(0, text.selectionStart).toString(),
                    text.text.subSequence(text.selectionStart, text.selectionEnd).toString(),
                    text.text.subSequence(text.selectionEnd, text.text.length).toString(),
                    text.offset.takeIf { it >= 0 },
                )
            }
        }
        val before = connection.getTextBeforeCursor(BEFORE, 0)?.toString() ?: return null
        Snapshot(before, connection.getSelectedText(0)?.toString(), connection.getTextAfterCursor(AFTER, 0)?.toString(), null)
    }.getOrNull()

    private companion object {
        const val BEFORE = 2048
        const val AFTER = 512
    }
}
