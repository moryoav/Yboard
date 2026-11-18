# Local word predictions

Typing and swipe ranking use Qwen3-0.6B-Base, quantized to Q4_K_M, through a pinned
llama.cpp runtime. No prediction text goes to an API. The one-time model download is
397 MB and is verified by size and SHA-256 before activation. It lives in private,
no-backup storage and survives app updates. The Typing and AI models screens show
its download, pause, resume and ready states.

All word suggestions come from Qwen. The old dictionary, phrase corpus, learned
pair ranking, cloud prediction requests and emoji suggestion fallback are disconnected
from the word strip. Explicit text shortcuts and email helper buttons remain available.
Old learned vocabulary can still supply names to the gesture lexicon; it does not
supply prediction scores. Historical cloud prediction usage remains readable.

## Context and latency

The model receives the full current sentence and one preceding completed sentence,
plus the unfinished word as a completion constraint. A brief message cue keeps short fragments in a natural-text context rather than code completion. InputConnection reads up to
32,768 UTF-16 characters. If this would cut a sentence, Yboard reports the limit
instead of silently using a short tail. The native context grows to 32,768 tokens
when needed. Very long sentences cost more time and memory.

The model stays resident, reuses sentence token evaluation and computes candidates
on one dedicated worker. New incompatible edits cancel old work. Compatible prefix
updates can reuse an in-flight result. Only Qwen-produced words that match the current
prefix are shown. Word scores include a word-boundary probability, so incomplete
subword tokens are not treated as complete predictions. Suggestions remain stable
while the strip is pressed. Cold model loading is slower than warm inference.

Swipe geometry and the existing lexicon supply up to 24 plausible matches without
frequency scores. Qwen evaluates their conditional word probabilities against the
sentence in a shared-context batch. The final score combines that model probability
with path distance. Geometry still rejects impossible paths; Qwen resolves linguistic
ambiguities. A gesture starts preparing the context before the finger lifts.

Password, private-learning, number, phone and incompatible editor fields do not feed
prediction inference. Context is cleared when the input session ends. A missing model
or inference error shows a status, with no dictionary or cloud fallback. Keyboard text
and predictions are not logged or persisted by the model engine.

## Model provenance and reproduction

- Original model: [Qwen/Qwen3-0.6B-Base](https://huggingface.co/Qwen/Qwen3-0.6B-Base/tree/da87bfb608c14b7cf20ba1ce41287e8de496c0cd), Apache 2.0.
- Runtime and converter: [llama.cpp](https://github.com/ggml-org/llama.cpp/tree/2d8a32502909ca065c41c4cf5f2fb1c427c047fc), MIT.
- Derivative: original safetensors converted to F16 GGUF, then Q4_K_M with the pinned converter/quantizer. No third-party weights.
- Size: 396,704,544 bytes. SHA-256: `ea719cfda3898e7eaf3fcebef8250857b9c7e3152b54632cf0293fdc6069cd58`.
- Model and runtime licenses ship in `assets/licenses/`. Conversion changes the representation, not the model weights by training.

Run `python scripts/Prepare-Qwen.py C:/dev/artifacts/Yboard/qwen-rebuild` with Python
3.11+, PyTorch, transformers 4.57.6, safetensors, sentencepiece and gguf 0.19.0 installed.
Build `llama-quantize` from the extracted pinned runtime, then run the two commands
printed by that script. The script keeps all large artifacts outside the Git workspace.
The release publisher requires the exact pinned model and verifies GitHub's uploaded
asset digest before making v1.9.0 public. Later app versions reuse that immutable asset.

## Validation

`LocalPredictionTest` checks full sentence preservation, Hebrew/English boundaries,
absolute cursor anchors, frequency-independent geometry and model/shape ranking.
`qwen_core` instrumentation runs actual local English/Hebrew completion and ambiguous
swipe word scoring. `prediction_ime` drives the real service in a separate synthetic
editor to check completion, consecutive suggestion acceptance, literal typing, full
context, glide ranking, cancellation and private/password fields. These fixtures do not
establish broad language accuracy. Phone latency must be measured on the actual device.
