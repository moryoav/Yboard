# Yboard

A clean, customizable Android keyboard for English and Hebrew, styled after Gboard.

**Install:** copy `Yboard.apk` to your phone, open it, allow "install unknown apps", then open Yboard and follow the two setup steps.

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
- Tapping a word accepts it, inserts a space and immediately offers the next word. Optional AI predictions use GPT-6 Luna with reasoning off by default, with local results available immediately and cancellation of obsolete requests.
- AI models: choose the model and reasoning for each text feature, plus both transcription models. Load available model IDs from your own OpenAI account. Unsupported choices report an error without silently changing the selection.
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
