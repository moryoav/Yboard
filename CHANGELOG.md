# Changelog

## 1.7.4

- Added daily quiet hours under Sound & vibration, with start and end times and separate sound and vibration controls.
- Added overnight schedules and automatic return to the usual feedback settings when quiet hours ends.
- Applied quiet hours to key sounds, touch vibration and sound previews without changing the usual preferences.

## 1.7.3

- Disabled Android's system spell checker while Yboard is active to prevent incorrect spelling underlines on Hebrew text in apps such as WhatsApp.
- Kept Yboard's own word suggestions and corrections available.

## 1.7.2

- Fixed language switching to immediately show the new language's letters from either symbols page, including in the settings preview.

## 1.7.1

- Matched the sticker toolbar button to the emoji button with a centered icon, no text label and no permanent circle.

## 1.7.0

- Replaced the bundled test button with AI sticker creation from typed or selected text.
- Added an in-keyboard preview with Recreate, Send and Back controls, without changing the typed draft.
- Added image-model selection and supported planning levels under AI models, plus image API usage and cost accounting.
- Added transparent 512 × 512 WebP preparation, private cache sharing and cancellation when leaving an editor.

## 1.6.1

- Fixed WhatsApp sticker insertion by using its advertised image/webp.wasticker content type.
- Added regression coverage for WhatsApp's actual advertised formats and sticker-type preference.

## 1.6.0

- Added a bundled transparent WebP sticker and a Test button beside the keyboard microphone.
- Added direct image handoff with temporary read permission and clear feedback for unsupported or rejecting apps.
- Kept the test button available while suggestions are showing and added an accessibility action.

## 1.5.0

- Preserved unfinished dictation across slow responses and live connection failures, with ordered recorded-phrase recovery.
- Added session readiness checks, bounded transcription requests, and visible recovery messages.
- Prevented late callbacks from editing text or starting work after dictation ends.
- Stopped dictation safely when the cursor moves, and made Smart commands off type commands literally.
- Added Faster, Balanced and More accurate transcription modes, adjustable phrase pauses, and silence timeout choices including Never.

## 1.4.0

- Added daily, weekly and monthly AI usage and USD cost estimates to AI models.
- Added input, output, cached-read and cache-write accounting with model breakdowns.
- Added duration-based speech cost tracking and persistent private usage storage.
- Marked incomplete usage and missing prices explicitly, and preserved historical estimates at the rates used when recorded.

## 1.3.0

- Added update checking under About using public GitHub Releases.
- Added APK download, checksum and signing-certificate validation, and Android's installation prompt.
- Added fixed release version codes and a release-publishing script that checks source synchronization and signing.
- Excluded local agent instructions, keys, credentials, generated files and APKs from Git history.

## 1.2

- Improved glide typing across text editors and word-frequency ranking.
- Added personal word learning and immediate next-word suggestions.
- Added per-feature AI model and reasoning settings.
