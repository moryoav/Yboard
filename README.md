# Yboard

A clean, customizable Android keyboard for English and Hebrew, styled after Gboard.

**Install:** download the APK from [GitHub Releases](https://github.com/moryoav/Yboard/releases/latest), open it, allow installation when Android asks, then open Yboard and follow the setup steps.

**Updates:** open Yboard → About → Check for updates. When a newer release is available, tap Download and install. Android asks you to allow updates from Yboard the first time and confirm each installation. Settings, learned words and the saved OpenAI key are retained. Wireless debugging is not needed.

## What's in it
- English (QWERTY) and Hebrew layouts, symbols, number pad; 🌐 switches language
- Looks like Gboard: the default dark theme uses Gboard's measured colors (#1E1E1E / #393939 / #444444),
  key sizes, letter size and icon sizes; Hebrew uses Gboard's layout (' - ק ר א ט ו ן ם פ, backspace
  at the end of the third row). `screenshots/compare_gboard_*.png` show them side by side.
- 15 themes + your own saved themes
- Customize: background/key transparency, color / gradient / photo background (dim + blur), key style (filled/outline/flat), roundness, gaps, borders, shadows, all colors, font, letter size
- Reset (top right of Customize): back to the default look, optionally size & layout too;
  saved themes, languages, typing and sound settings are kept (`YboardSettings.withDefaultLook`)
- Layout: height, lift, number row, number hints, toolbar, one-handed mode
- Toolbar (Gboard order): app menu, undo, quick themes + transparency sliders, clipboard history,
  text editing (arrows, select, copy/paste, undo); one-handed mode is in the app under Layout & size
- Long-press: accents (é ñ…), numbers, punctuation; comma → emoji
- Space-bar slide moves the cursor, vibration & sound with strength/volume
- Quiet hours: under Sound & vibration, set a daily start and end time and pause key sounds, vibration, or both. Overnight schedules are supported; matching times mean all day. Usual settings resume afterward.
- Local typing and swipe decoding work offline. Optional AI predictions, voice typing, name spellings and emoji sprinkle use your OpenAI key.
- Font: Google Sans (what Gboard uses), bundled under the SIL Open Font License, trimmed to
  Latin + Hebrew (`res/font/`, license in `assets/licenses/`)
- Registers as ONE keyboard (no Android subtypes); 🌐 only flips English/Hebrew and never
  leaves Yboard. Android's own switch-keyboard button under the keys only disappears when
  other keyboards (e.g. Gboard) are turned off — the app's home screen links there.
- Bottom bar: the keyboard window reaches the screen bottom; `KeyboardRootView` pads the keys
  by the bar height (caption-bar inset on gesture nav, nav-bar inset on 3-button nav).

## Typing smarts
- Suggestions + autocorrect (`text/Suggester.kt`): keyboard-aware typo distance, undo with
  backspace right after a fix, learns your words (`text/UserWords.kt`, on-device only).
- Dictionaries: Google's open-source Android keyboard (AOSP LatinIME) word lists, English +
  Hebrew, 60k words each, Apache-2.0 (`assets/dict/`, notices in `assets/licenses/`).
- Swipe typing works in ordinary text editors even when an app requests no suggestions. Password and numeric fields retain their specialized behavior.
- Swipe decoding combines path alignment, endpoints, the bundled frequency dictionary and your local word usage. A rejected swipe guess is not learned as an accepted word.
- Tapping a word accepts it, inserts a space and immediately offers the next word. Local predictions use up to four preceding words, bundled English/Hebrew conversational phrases and repeated personal habits. They also help complete the word being typed.
- Optional AI predictions stream complete suggestions as they arrive and stay useful while the next word is typed. Cached phrase tails can supply the following words immediately. Predictions have their own worker, separate from background AI tasks. GPT-6 Luna with reasoning off remains the default. See [prediction behavior and validation](docs/predictions.md).
- AI models: choose the model and reasoning for each text feature, plus both transcription models. Load available model IDs from your own OpenAI account. Unsupported choices report an error without silently changing the selection.
- AI usage and cost: the AI models screen shows input, output and cached token totals with USD cost estimates for today, the last 7 days and the last 30 days. A model breakdown is available. Cache-write charges and duration-billed speech are included separately.
- AI stickers: type a message and tap **Sticker** beside the microphone, then preview, recreate or send it. Choose the image model and supported planning level in AI models. The draft is preserved. WhatsApp receives its native sticker type; other apps control how they handle the image. See [AI stickers](docs/stickers.md).
- Slide-left-on-backspace deletes whole words.
- Emoji search in English + Hebrew from Unicode CLDR keywords (`assets/emoji/keywords.tsv`),
  kaomoji tab (`assets/emoji/kaomoji.txt`).
- Look: wallpaper colors (Material You, Android 12+), key press effects (pop/glow/ripple),
  RGB lights, 6 synthesized click sounds (`res/raw/key_*.wav`).

## AI voice typing (OpenAI, your own key)
- Yboard app → Voice typing: paste an OpenAI API key (stored encrypted with the Android Keystore,
  excluded from backups, never logged; API-key-looking text is kept out of clipboard history),
  allow the microphone, pick languages, smart commands on/off.
- Keyboard mic: live words via Realtime transcription (`gpt-live-transcribe`, WebSocket,
  24 kHz PCM, client-side pause detection in `voice/Vad.kt`), shown underlined as you talk.
  If live transcription is unavailable it falls back to one recording per phrase (`gpt-transcribe`).
- Listening preferences: Faster / Balanced / More accurate transcription, a 250–1500 ms phrase pause,
  and automatic stop after 8, 15 or 30 seconds of silence, or Never. Balanced and 15 seconds are the defaults.
- Unfinished phrases stay in memory until confirmed, so slow or disconnected live sessions can recover them in order.
  Tapping the mic waits for pending words; typing a key, moving the cursor or closing the keyboard ends immediately.
- Smart commands off types command words literally, including punctuation and send commands.
- Commands ("delete that", "question mark", "new line", "תמחק את זה"): phrases that look like
  commands go to the selected model (default `gpt-6.1-terra`, Responses API, structured output), which returns
  `{remove, insert}`; `remove` must match the end of the text or nothing is deleted.
- AI features use the internet; the microphone is used only during voice typing. Passwords and fields requesting no personalized learning do not feed prediction requests or learning. Code: `voice/`.

## Code map
- `ime/YboardIME.kt` — the keyboard service (typing, shift, enter, panels)
- `keyboard/` — key layouts, drawing (`KeyboardView`), toolbar, panels, theme rendering
- `settings/` — settings model, theme presets, storage
- `ui/` — the Yboard app screens (Jetpack Compose)

## Build
```
.\gradlew.bat :app:assembleRelease  # APK -> app/build/outputs/apk/release/
gradle :app:recordPaparazziDebug     # screenshots -> app/src/test/snapshots/images/
```

For updates on the owner's phone, set `yboard.signingStore` in the ignored `local.properties` to the original computer's debug keystore. Both build types use it. Keep the keystore outside the project and preserve a backup; a different key cannot update the installed app while retaining its data.

## Publish a release

1. Synchronize `main` with `origin/main` before starting work. Commit and push completed changes.
2. Set the release name and a larger Android version code in `version.properties`. Version codes are fixed per release and must always increase.
3. Keep the original signing key outside the checkout, configure it locally, and set `JAVA_HOME` (JDK 17) and `ANDROID_HOME`.
4. Write release notes to a file, then run PowerShell 7:

```powershell
./scripts/Publish-Release.ps1 -NotesFile /path/to/release-notes.md
```

The script runs the logic tests, builds the release APK, checks the original signing certificate, confirms local and remote source agree, and publishes a tag, APK and `update.json` to GitHub Releases. Names containing a suffix such as `-rc.1` become prereleases and are not offered by the app. `-SkipBuild` can publish an already tested APK after verifying its version and signature.

Every distributed APK belongs in a GitHub Release. APKs, local agent instructions, local settings, credentials and signing files are excluded from source history. The GitHub test workflow runs without signing keys or API keys. Publishing from another trusted machine requires the original signing key; the repository does not store or transfer it.

The app checks stable public releases without GitHub credentials, compares Android version codes, verifies the APK checksum, package, version and signing certificate, and hands installation to Android. Update checks send no keyboard text, typing history or OpenAI key.

## AI usage estimates

Tracking starts when the version with usage accounting first runs on a device. Earlier token usage cannot be recovered from Yboard. Only timestamps, feature/model names, token counts, speech duration and calculated charges are stored. The usage database stays in Android's private no-backup directory and survives app updates; it contains no prompts, replies, recordings or API keys. Records are retained for 90 days.

Costs use the [OpenAI API price card](https://developers.openai.com/api/docs/pricing), checked on November 17, 2026, and are stored at request time. Cached input is already part of total input, and reasoning is already part of output. Ordinary input, cached reads and both cache-write lifetimes are priced once each. Current transcription models use their published per-minute rates; when the API omits duration, sent audio duration is marked as estimated.

These figures cover this Yboard installation, not the whole OpenAI account or its bill. Missing usage, unsupported rates and interrupted requests are identified instead of being presented as fully priced. Prices can change, and the UI shows the date of the bundled price card.
