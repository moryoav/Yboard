package com.yboard.keyboard

import com.yboard.keyboard.stickers.StickerShare
import org.junit.Assert.*
import org.junit.Test

class StickerMimeTest {
    @Test fun whatsappAdvertisedTypesSelectItsStickerContract() {
        // Reproduces the actual EditorInfo from WhatsApp, which doesn't advertise ordinary WebP.
        val types = listOf("image/gif", "video/x.looping_mp4", "image/jpeg", "image/jpg", "image/png", "image/webp.wasticker")
        assertEquals(StickerShare.WHATSAPP_MIME, StickerShare.mimeTypeFor(types))
    }
    @Test fun explicitStickerTypeTakesPriorityOverGenericImages() {
        assertEquals(StickerShare.WHATSAPP_MIME,
            StickerShare.mimeTypeFor(listOf("image/webp", "image/*", "image/webp.wasticker")))
    }
    @Test fun genericWebpReceiversKeepTheirOrdinaryMimeType() {
        assertEquals(StickerShare.MIME, StickerShare.mimeTypeFor(listOf("image/webp")))
    }
    @Test fun wildcardsDoNotImplyWhatsappStickerSupport() {
        assertEquals(StickerShare.MIME, StickerShare.mimeTypeFor(listOf("image/*")))
        assertEquals(StickerShare.MIME, StickerShare.mimeTypeFor(listOf("*/*")))
    }
    @Test fun unsupportedEditorsDoNotGetAnUnexpectedFormat() {
        assertNull(StickerShare.mimeTypeFor(emptyList()))
        assertNull(StickerShare.mimeTypeFor(listOf("image/png", "image/jpeg")))
    }
}
