# Local word predictions

Typing and swipe ranking use the model selected under **AI models → Local predictions**.
Qwen3-0.6B-Base is the default. Download
Qwen3.5-0.8B-Base or Gemma 3 270M Base and tap its row to use it. The downloads
stay in private no-backup storage across app updates, and the selection survives
app restarts. The selected model runs through the pinned llama.cpp runtime on
the device. Every download is verified by size and SHA-256.

Use **Remove** next to a downloaded model to free its storage. If you remove
the active model, Yboard switches to another installed model when available.
After removing the last model, download a model to resume predictions and glide
typing. Removed models can be downloaded again from the same screen.

While typing, the selected model supplies and ranks word suggestions. The dictionary
can supply their established spelling. Explicit text shortcuts and email helper buttons
remain available.
With **Learn my words** enabled, Yboard gives frequently used words a small,
capped boost among the model's typed-prefix completions and plausible glide
matches. Learned vocabulary can also supply names to the gesture lexicon.
Explicitly choosing a suggestion counts more than keeping an automatic glide
guess. Historical cloud prediction usage remains readable.

## Context and latency

The model receives the full current sentence and one preceding completed sentence,
plus the unfinished word as a completion constraint. A brief message cue keeps short fragments in a natural-text context rather than code completion. InputConnection reads up to
32,768 UTF-16 characters. If this would cut a sentence, Yboard reports the limit
instead of silently using a short tail. The native context grows to 32,768 tokens
when needed. Very long sentences cost more time and memory.

The model stays resident, reuses sentence token evaluation and computes candidates
on one dedicated worker. New incompatible edits cancel old work. Compatible prefix
updates can reuse an in-flight result. Model suggestions match the current prefix
and use established spellings where available. Unrecognized mixed-case suggestions
need support from the typed capitalization. Word scores include a word-boundary
probability to distinguish complete words from partial tokens. Suggestions remain stable
while the strip is pressed. Cold model loading is slower than warm inference.

Swipe geometry and the existing lexicon supply up to 24 plausible matches without
frequency scores. The model evaluates their conditional word probabilities against the
sentence in a shared-context batch. The final score combines that model probability
with path distance. Geometry allows one rounded turn and still checks that the
path passes the keys in order. The model resolves linguistic
ambiguities. A gesture starts preparing the context before the finger lifts.
Ranking reuses that preparation when the editor target and model still match.

Tapping inside an earlier word in an editable draft opens replacement suggestions
automatically. The replacements stay available when the caret moves from inside the
word to its end. Yboard can reuse recent glide alternatives from the current editing
session. For other words, it offers nearby spellings from its English or Hebrew
dictionary and ranks them with the selected model when available. Accepting a
replacement edits only the target word and preserves surrounding text.

Password, private-learning, number, phone and incompatible editor fields do not feed
prediction inference. Context is cleared when the input session ends. A missing model
or inference error shows a status for typing predictions and glide ranking. Keyboard text
and predictions are not logged or persisted by the model engine.

## Model provenance and reproduction

- Original models are [Qwen/Qwen3-0.6B-Base](https://huggingface.co/Qwen/Qwen3-0.6B-Base/tree/da87bfb608c14b7cf20ba1ce41287e8de496c0cd)
  and [Qwen3.5-0.8B-Base](https://huggingface.co/Qwen/Qwen3.5-0.8B-Base/tree/dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68), Apache 2.0.
- Runtime and converter: [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/2d8a32502909ca065c41c4cf5f2fb1c427c047fc), MIT.
- Qwen weights were converted from the original safetensors to F16 GGUF, then Q4_K_M.
- Qwen3-0.6B-Base is 396,704,544 bytes. SHA-256 `ea719cfda3898e7eaf3fcebef8250857b9c7e3152b54632cf0293fdc6069cd58`.
- Qwen3.5-0.8B-Base is 547,308,064 bytes. SHA-256 `250053ed8e33a8ddc4e4516cbfe06b51f2cc6c77da298c1c9a1e72c6df012123`.
- Gemma 3 270M Base uses [ggml-org's pinned Q4_0 GGUF](https://huggingface.co/ggml-org/gemma-3-270m-qat-GGUF/tree/455826a6464d1f352ff560df17f6f9eeec230082), from [Google's pretrained QAT checkpoint](https://huggingface.co/google/gemma-3-270m-qat-q4_0-unquantized). It is 241,409,024 bytes. SHA-256 `0c4a546e737759fd7c60f4c34704f12cc3273222ba44a5bc3d6ba9d6053a9f9f`.
- Gemma's first download requires agreement to the [Gemma Terms of Use](https://gemma.ai/resources/terms/), which incorporate the [Gemma Prohibited Use Policy](https://gemma.ai/resources/prohibited_use_policy/). The terms and notice ship in `assets/licenses/` alongside the other model and runtime licenses.

Run `python scripts/Prepare-Qwen.py C:/dev/artifacts/Yboard/qwen-rebuild` with Python
3.11+, PyTorch, transformers 4.57.6, safetensors, sentencepiece and gguf 0.19.0 installed.
For Qwen3.5, pass `--qwen35` and use a separate output directory. Run the two commands
printed by the script. The Qwen release models were quantized with the llama.cpp Windows
CPU build b12359. The script keeps large artifacts outside the Git workspace.
The release publisher verifies GitHub's uploaded model asset digest before publication.
The app downloads Qwen3 from v1.9.0 and Qwen3.5 from v1.10.0.
Gemma comes directly from the pinned ggml-org revision on Hugging Face.

## Validation

`LocalPredictionTest` checks full sentence preservation, Hebrew/English boundaries,
absolute cursor anchors, frequency-independent geometry and model/shape ranking.
`qwen_core` instrumentation runs actual local English/Hebrew completion and ambiguous
swipe word scoring. `qwen_switch` checks both engines, cancellation during a switch and
availability of both downloaded models. `prediction_ime` drives the real service in a
separate synthetic editor to check completion, consecutive suggestion acceptance,
literal typing, full context, glide ranking, cancellation and private/password fields.
These fixtures do not establish broad language accuracy. Phone latency must be measured
on the actual device.

`prediction_benchmark` times the three installed models on the same synthetic
English and Hebrew next-word, prefix, and 24-candidate glide ranking workloads
after an initial warm-up prediction.
`glide_rank_benchmark` reports repeated warm ranking times and exact score bits
for those 24-candidate batches. `glide_ime_benchmark` replays English and Hebrew
touches with a fixed interval before release and reports time from release to
editor insertion, along with the suggestion strip.
