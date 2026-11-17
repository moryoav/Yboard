# Changelog

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
