package com.yboard.keyboard

import com.yboard.keyboard.text.WordReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordReplacementTest {
    @Test fun findsWholeWordAndLeavesQuotesAndPunctuationOutside() {
        assertEquals(WordReplacement.Span("three", 2, 3),
            WordReplacement.atCursor("I said 'th", "ree', then left.", false))
        assertEquals(WordReplacement.Span("don't", 3, 2),
            WordReplacement.atCursor("I don", "'t know.", false))
        assertEquals(WordReplacement.Span("three", 5, 0),
            WordReplacement.atCursor("I said three", ", then left.", true))
    }

    @Test fun readsHebrewFromLogicalTextOrder() {
        assertEquals(WordReplacement.Span("שלוֹם", 2, 3),
            WordReplacement.atCursor("אמרתי של", "וֹם, ואז הלכתי.", false))
    }

    @Test fun wordEndNeedsNavigationAndIdentifiersAreIgnored() {
        assertNull(WordReplacement.atCursor("I said three", ", then left.", false))
        assertNull(WordReplacement.atCursor("prefix12a", "bc3 ", false))
        assertNull(WordReplacement.atCursor("After ", " punctuation", false))
    }
}
