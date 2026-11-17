# AI stickers

Type a message, or select part of it, then tap **Sticker** immediately left of the keyboard microphone. The keyboard creates an image and shows a preview. **Recreate** makes another version of the same message. **Send** hands the previewed WebP to the current app. **Back** returns to typing. The typed draft is preserved.

Choose an image model under **AI models → Sticker images**. The default is GPT Image 2.6 Flare with low planning and medium rendering quality. GPT Image 2.6 models expose low, medium, high or default planning. Other image models use their own defaults. Available model IDs can be loaded from the saved OpenAI account. Unsupported selections report an error rather than silently switching models.

Creating or recreating a sticker sends the source message to OpenAI using the saved key and incurs image API charges. Input, output and cached token usage feed the existing daily, weekly and monthly estimates. Known image-model rates use text-input and image-output prices. Unknown rates or missing usage remain marked as incomplete estimates.

The generated image is converted on the phone to a transparent 512 × 512 static WebP no larger than 100 KB. The preview shows the encoded file. Images use unique private cache filenames so recreating a preview cannot change an image already handed to another app. Cache files are excluded from Android backups and old files are removed after seven days. Source messages are held only for the active preview session, not saved in accounting or filenames.

Closing the panel, hiding the keyboard or changing editors cancels the active request. Late results cannot reopen the panel or send into another chat. Generation never sends automatically. Cancellation cannot guarantee that a request already being processed by OpenAI will avoid charges.

For an editor explicitly advertising `image/webp.wasticker`, that sticker-specific type takes priority. Other compatible editors receive `image/webp`. WhatsApp 2.26.46.5 on Android 16 was verified with the same delivery contract as a native sticker. Receiving apps control whether a handoff sends immediately or opens a preview; Telegram classification has not yet been checked on a phone.

The former bundled test sticker and Test button are removed from the release APK. A synthetic artwork fixture remains only in the Android test APK for PNG decoding, size, transparency and preview-control regression checks.
