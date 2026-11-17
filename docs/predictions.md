# Next-word predictions

Yboard displays local suggestions immediately. It uses up to four preceding words,
the current word prefix, common word pairs, and original English/Hebrew conversational
examples bundled with the app. The phrase index loads in the background. Typed,
accepted, swiped and dictated words can teach longer contexts when learning is enabled.
Existing learned pairs survive the update. A single observation has less influence
than a repeated habit, and frequent broad pairs cannot overwhelm a more specific context.

When AI predictions are enabled, a dedicated worker starts a request after a 25 ms
pause. Background name spelling and emoji requests use another worker. The request
contains at most 300 characters before the unfinished word, eight personal vocabulary
items and eight relevant repeated phrase hints. GPT-6 Luna with reasoning off remains
the default. The selected model is never silently replaced.

The Responses API streams structured output. Only complete, validated words are
displayed; partial JSON strings and unfinished Unicode escapes never reach the strip.
The first word of each predicted phrase is shown. Remaining words stay available for
subsequent taps or typing. A result for `See you ` can still suggest `tomorrow` after
`tom` is typed. Incompatible edits, cursor movement, field changes and settings changes
invalidate pending work. Suggestions stay stable while a finger is pressing the strip.

Cloud results stay in memory for at most two minutes, with a maximum of 64 entries.
They are cleared when the input session ends or settings change. Passwords, URLs,
email fields and fields requesting no personalized learning do not feed cloud prediction
requests. Personal local history is also excluded when learning is disabled or the field
requests no personalized learning. No prompt, prediction or editor text is added to
usage accounting or timing diagnostics.

The response still has a four-second deadline. A failed request leaves local suggestions
available and briefly backs off rather than retrying on every key. Usage is recorded
from the terminal response, including incomplete responses. Interrupted requests without
reported usage remain marked as partial cost estimates.

## Validation

Logic tests cover contextual ranking, English and Hebrew completions, repeated habits,
legacy persistence, sentence boundaries, cache expiry, absolute cursor positions,
phrase continuation, streaming JSON and interrupted responses. A small synthetic
conversation fixture contains 20 English and 20 Hebrew cases. Phrase patterns are
represented in the bundled examples; this is a regression fixture, not a broad accuracy
benchmark or a claim about real messages.

Android diagnostics exercise 1,000 warm contextual lookups, actual preferences saves
and reopening, and the real IME with a separate editor app. The IME checks include
prefix typing during pending work, consecutive suggestion taps, cursor cancellation,
private/password fields, stale replies and worker isolation. Synthetic cached cloud
replies make these checks repeatable without an API key.

An optional `prediction_benchmark` instrumentation mode compares selected models with
synthetic English/Hebrew prompts using the saved API key. It reports p50/p95 time to the
first complete suggestion and the complete response, valid results and failures. It does
not change model settings or export the key. Real API requests incur the account's normal
charges. Emulator results and synthetic fixtures cannot establish phone or network latency.

The implementation follows the OpenAI documentation for
[streaming Responses](https://developers.openai.com/api/docs/guides/streaming-responses)
and [latency measurement](https://developers.openai.com/api/docs/guides/latency-optimization).
