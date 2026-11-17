package com.yboard.keyboard

import com.yboard.keyboard.stickers.TestSticker
import org.junit.Assert.*
import org.junit.Test

class StickerMimeTest {
    @Test fun whatsappAdvertisedTypesSelectItsStickerContract() {
        // Reproduces the actual EditorInfo from WhatsApp, which doesn't advertise ordinary WebP.
        val types = listOf("image/gif", "video/x.looping_mp4", "image/jpeg", "image/jpg", "image/png", "image/webp.wasticker")
        assertEquals(TestSticker.WHATSAPP_MIME, TestSticker.mimeTypeFor(types))
    }
    @Test fun explicitStickerTypeTakesPriorityOverGenericImages() {
        assertEquals(TestSticker.WHATSAPP_MIME,
            TestSticker.mimeTypeFor(listOf("image/webp", "image/*", "image/webp.wasticker")))
    }
    @Test fun genericWebpReceiversKeepTheirOrdinaryMimeType() {
        assertEquals(TestSticker.MIME, TestSticker.mimeTypeFor(listOf("image/webp")))
    }
    @Test fun wildcardsDoNotImplyWhatsappStickerSupport() {
        assertEquals(TestSticker.MIME, TestSticker.mimeTypeFor(listOf("image/*")))
        assertEquals(TestSticker.MIME, TestSticker.mimeTypeFor(listOf("*/*")))
    }
    @Test fun unsupportedEditorsDoNotGetAnUnexpectedFormat() {
        assertNull(TestSticker.mimeTypeFor(emptyList()))
        assertNull(TestSticker.mimeTypeFor(listOf("image/png", "image/jpeg")))
    }
}
