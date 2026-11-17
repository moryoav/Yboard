package com.yboard.keyboard.stickers

import android.content.Context
import android.util.Base64
import com.yboard.keyboard.settings.AiFeature
import com.yboard.keyboard.settings.AiModel
import com.yboard.keyboard.usage.TokenUsage
import com.yboard.keyboard.usage.UsageStore
import com.yboard.keyboard.voice.AiStatus
import com.yboard.keyboard.voice.OpenAi
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** One text-to-image request. Credentials stay in the app; the selected model is never substituted. */
object StickerImages {
    private val http by lazy {
        OpenAi.http.newBuilder().callTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    }
    fun requestJson(message: String, choice: AiModel): JSONObject {
        require(message.isNotBlank() && message.length <= 1500) { "Select a message of up to 1,500 characters." }
        val prompt = """
            Create ONE expressive messaging sticker inspired by the meaning, mood or punchline of this message.
            Message: ${JSONObject.quote(message)}
            Use a bold, charming illustration that reads clearly at small size. Use a genuinely transparent
            background, a clean white outline around the subject, and leave transparent padding on all sides.
            Center the complete subject in a square composition. Do not draw a background, checkerboard,
            rectangular card or watermark. Prefer a visual expression over writing out the message.
            If a caption helps, use only a few words in the message's original language, spelled correctly.
        """.trimIndent()
        return JSONObject().put("model", choice.model).put("prompt", prompt).put("n", 1)
            .put("size", "1024x1024").put("quality", "medium").put("background", "transparent")
            .put("output_format", "png").apply {
                if (choice.model.startsWith("gpt-image-2.6") && choice.effort in listOf("low", "medium", "high")) put("complexity", choice.effort)
            }
    }
    fun call(key: String, message: String, choice: AiModel): Call = http.newCall(
        Request.Builder().url("${OpenAi.API}/images/generations")
            .header("Authorization", "Bearer $key")
            .post(requestJson(message, choice).toString().toRequestBody("application/json".toMediaType())).build()
    )

    /** Run on the image worker, including decoding and WebP encoding. */
    fun execute(context: Context, call: Call, choice: AiModel): StickerImage {
        var accounted = false
        try { call.execute().use { response ->
            val body = response.body ?: error("The image service returned an empty response.")
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= 32 * 1024 * 1024) { "The generated image was too large. Try recreating it." }
                    bytes.write(buffer, 0, count)
                }
            }
            val text = bytes.toString("UTF-8")
            if (!response.isSuccessful) throw OpenAi.ApiError(response.code, OpenAi.errorMessage(response.code, text))
            val result = JSONObject(text)
            UsageStore.record(AiFeature.STICKER, choice.model, TokenUsage.parse(result.optJSONObject("usage")))
            accounted = true
            val encoded = result.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
            check(encoded.isNotEmpty()) { "The image model returned no image. Choose a GPT Image model in AI models." }
            val image = StickerCodec.encode(context, Base64.decode(encoded, Base64.DEFAULT), choice.model)
            AiStatus.report(AiFeature.STICKER, "Last sticker created with ${choice.model}")
            return image
        } } catch (error: Throwable) {
            // A cancelled or broken connection can leave a billed generation without returned usage.
            if (!accounted && (error !is OpenAi.ApiError || error.code >= 500)) {
                UsageStore.record(AiFeature.STICKER, choice.model, TokenUsage(reported = false))
            }
            throw error
        }
    }

    fun friendly(error: Throwable): String = when (error) {
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "Couldn't prepare the sticker. Try recreating it."
        else -> OpenAi.friendly(error)
    }
}

/** Request tokens are tied to one editor session. A late result cannot replace a newer preview. */
class StickerRequestGate {
    private var version = 0
    private var open = true
    fun next(): Int { check(open); return ++version }
    fun accepts(token: Int) = open && token == version
    fun close() { open = false; version++ }
}
