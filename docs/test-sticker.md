# Bundled sticker compatibility test

The keyboard's **Test** button, immediately left of the microphone, hands one static sticker to the focused app through Android's Commit Content API. The receiving app decides whether to send it immediately, show a preview, or reject it. The button remains available while word suggestions are showing. There is no image-generation request, sticker-pack import or image-sharing fallback in this test.

When an editor advertises `image/webp.wasticker`, that sticker-specific type takes priority. Other compatible editors receive ordinary `image/webp`. The underlying file and its provider MIME type remain standard WebP.

Verified on Android 16 with WhatsApp 2.26.46.5 on 2026-11-17: the sticker-specific handoff sent the fixture as a native sticker, and the existing draft stayed intact. The plain WebP-only implementation in 1.6.0 stopped before sending because that WhatsApp editor did not advertise `image/webp`. Telegram classification has not been verified on a physical device.

Asset: [`coffee_test.webp`](../app/src/main/assets/stickers/coffee_test.webp), 512 × 512 pixels, transparent WebP, under 100 KB. The artwork was generated once using the built-in imagegen tool and bundled in the APK. ImageMagick resized it to fit within a 512-pixel transparent canvas and encoded the final WebP. The app's saved OpenAI key is not used.

The Android regression fixture verifies a real handoff from the running keyboard into a separate receiver app, including URI read permissions, MIME type, dimensions, transparency, rejection, unsupported content types and preservation of an existing draft. It cannot determine how WhatsApp or Telegram classify the content; that needs a test in those apps.

With the debug app and Android test APK installed on the dedicated emulator and Yboard selected, run `./scripts/Test-Sticker.ps1`. The fixture sends only to its own receiver activity and makes no network requests. The script drives the keyboard from outside its process, reads its position from the receiver's IME insets, and keeps test captures outside the workspace.

## Generation prompt

```text
Use case: stylized-concept
Asset type: one static messaging sticker for an Android keyboard compatibility test.
Primary request: create a cheerful cartoon coffee mug giving a thumbs-up, centered and isolated with a genuinely transparent background.
Style/medium: crisp, charming sticker illustration, bold simple shapes, warm coffee colors, expressive friendly face, a clean white sticker outline around the silhouette.
Composition/framing: square image, entire silhouette visible, generous transparent padding, legible at small chat-sticker size.
Text: none.
Constraints: real alpha transparency, no background scene, no square tile, no checkerboard painted into the image, no watermark, no brand or logo.
```
