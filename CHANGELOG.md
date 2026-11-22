# Changelog

## 1.14.1

- Fixed the key press effect getting stuck after opening the emoji picker and returning to the letter keys.

## 1.14.0

- Added toolbar shortcut visibility and ordering in Layout & size, with a live preview and a reset button.
- Added an optional emoji picker shortcut above the keys.

## 1.13.0

- Added a small on-device boost for words you use often when ranking close glide matches and typed-word completions.

## 1.12.6

- Enabled glide typing in browser address bars and other URL fields. Word suggestions, autocorrect, and learning stay off in these fields.

## 1.12.5

- Reduced duplicate work when scoring glide candidates and reused sentence preparation from the current gesture.

## 1.12.4

- Kept plausible glide words in contention when a turn clips one key, so sentence context can help pick the word.
- Fixed mixed-case model suggestions and kept glide alternatives available when the caret returns to the end of a word.

## 1.12.3

- Recognized spoken periods between sentences during continuous dictation and preserved common literal uses such as “trial period”.
- Improved spoken punctuation in English and Hebrew, including commas, colons, question marks, exclamation marks, and ellipses.

## 1.12.2

- Added automatic spacing when typing letters after a glided word. Punctuation stays attached to the preceding word.
- Capitalized the standalone English pronoun “I” at word boundaries.

## 1.12.1

- Added a Remove action for downloaded local models. Removing the active model switches to another installed model when one is available.

## 1.12.0

- Added Gemma 3 270M Base as a 241 MB downloadable model for local word suggestions and swipe typing.

## 1.11.0

- Reopened replacement suggestions when you tap inside an earlier word in editable text. Picking a replacement keeps the surrounding spaces and punctuation.
- Restored recent glide alternatives when available and offered local spelling alternatives for other words.

## 1.10.0

- Added Qwen3.5-0.8B-Base as an optional on-device model for word suggestions and swipe typing.
- Added a selector to switch between downloaded models in Typing or AI models.

## 1.9.5

- Kept dictation together across pauses so automatic punctuation can use the words on both sides of a hesitation.
- Kept pauses for recognized voice commands and the optional microphone timeout.

## 1.9.4

- Kept short hesitations in the same voice transcription segment when a sentence appears unfinished.
- Fixed extra capitalization in dictated text and kept capitalization after spoken sentence punctuation.

## 1.9.3

- Added optional update notifications under About. Yboard checks for new releases in the background roughly every three days and shows one alert per version.
- Opened the in-app updater when an update notification is tapped.

## 1.9.2

- Fixed commas and periods after tapping a suggestion for a typed or swiped word. Punctuation now replaces the space added by autocomplete.
- Improved automatic punctuation in English and Hebrew voice typing for both live and recorded transcription.

## 1.9.1

- Queued consecutive glides so you can swipe the next word immediately. Recognized words appear in the order you swiped them.

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
