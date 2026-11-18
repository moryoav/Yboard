#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
std::atomic<int64_t> generation{0};
std::once_flag initialized;
constexpr int BEAM = 4;
constexpr int MAX_CANDIDATES = 32;

// JNI modified UTF-8 is not UTF-8: convert surrogate pairs before passing editor text to Qwen.
std::string utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const jchar * chars = env->GetStringChars(value, nullptr);
    const int length = env->GetStringLength(value);
    std::string result;
    for (int i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + chars[++i] - 0xdc00;
        else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) result += char(c);
        else if (c < 0x800) { result += char(0xc0 | (c >> 6)); result += char(0x80 | (c & 63)); }
        else if (c < 0x10000) { result += char(0xe0 | (c >> 12)); result += char(0x80 | ((c >> 6) & 63)); result += char(0x80 | (c & 63)); }
        else { result += char(0xf0 | (c >> 18)); result += char(0x80 | ((c >> 12) & 63)); result += char(0x80 | ((c >> 6) & 63)); result += char(0x80 | (c & 63)); }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

std::string folded(std::string text) {
    for (char & c : text) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
    return text;
}
bool starts(const std::string & text, const std::string & prefix) { return text.compare(0, prefix.size(), prefix) == 0; }
bool space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
bool delimiter(unsigned char c) { return c < 128 && std::string(" \t\n\r.,!?:;()[]{}\"").find(char(c)) != std::string::npos; }

struct Word { std::string text; bool valid = true; bool complete = false; bool unicode_complete = true; };
bool supported_range(uint32_t low, uint32_t high, bool internal) {
    auto overlaps = [&](uint32_t start, uint32_t end) { return low <= end && high >= start; };
    return overlaps('a', 'z') || overlaps('A', 'Z') || overlaps(0xc0, 0x24f) ||
        overlaps(0x5d0, 0x5ea) || overlaps(0x5ef, 0x5f2) ||
        (internal && (overlaps(0x300, 0x36f) || overlaps(0x591, 0x5c7) ||
            overlaps(0x2019, 0x2019) || overlaps(0x5f3, 0x5f4)));
}
Word word_from(const std::string & raw, bool need_space) {
    Word word;
    if (raw.empty() || (need_space && !space(raw.front()))) { word.valid = false; return word; }
    size_t i = 0;
    while (i < raw.size() && space(raw[i])) ++i;
    const size_t begin = i;
    while (i < raw.size()) {
        unsigned char first = raw[i];
        if (delimiter(first)) {
            if (i == begin) word.valid = false;
            else word.complete = true;
            break;
        }
        size_t size = first < 128 ? 1 : (first & 0xe0) == 0xc0 ? 2 : (first & 0xf0) == 0xe0 ? 3 : (first & 0xf8) == 0xf0 ? 4 : 0;
        if (size == 0) { word.valid = false; return word; }
        if (i + size > raw.size()) {
            uint32_t prefix = first & (size == 2 ? 0x1f : size == 3 ? 0x0f : 0x07);
            for (size_t j = i + 1; j < raw.size(); ++j) {
                auto byte = static_cast<unsigned char>(raw[j]);
                if ((byte & 0xc0) != 0x80) { word.valid = false; return word; }
                prefix = (prefix << 6) | (byte & 63);
            }
            const int missing_bits = 6 * (size - (raw.size() - i));
            const uint32_t low = prefix << missing_bits;
            const uint32_t high = low + (1u << missing_bits) - 1;
            // Do not let dead-end byte fragments fill the beam. For example,
            // an unfinished CJK token cannot become an English or Hebrew word.
            if (!supported_range(low, high, i > begin)) { word.valid = false; return word; }
            word.unicode_complete = false; i = raw.size(); break;
        }
        uint32_t c = first & (size == 1 ? 0x7f : size == 2 ? 0x1f : size == 3 ? 0x0f : 0x07);
        for (size_t j = 1; j < size; ++j) {
            unsigned char next = raw[i + j];
            if ((next & 0xc0) != 0x80) { word.valid = false; return word; }
            c = (c << 6) | (next & 63);
        }
        const bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= 0xc0 && c <= 0x24f) || (c >= 0x5d0 && c <= 0x5ea) || (c >= 0x5ef && c <= 0x5f2);
        const bool internal = c == '\'' || c == '-' || c == 0x2019 || c == 0x5f3 || c == 0x5f4 ||
            (c >= 0x300 && c <= 0x36f) || (c >= 0x591 && c <= 0x5c7);
        if (!letter && !(internal && i > begin)) { word.valid = false; return word; }
        i += size;
    }
    word.text = raw.substr(begin, i - begin);
    if (word.text.size() > 96 || raw.size() > 128) word.valid = false;
    return word;
}

double log_sum(const float * logits, int count) {
    const float maximum = *std::max_element(logits, logits + count);
    double sum = 0;
    for (int i = 0; i < count; ++i) sum += std::exp(double(logits[i]) - maximum);
    return maximum + std::log(sum);
}

struct Engine {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model_owner{nullptr, llama_model_free};
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<std::string> pieces;
    std::vector<bool> boundary;
    std::vector<llama_token> cached;
    std::vector<float> root_logits;
    int threads;
    int capacity = 0;
    int64_t expected = 0;
    int vocabulary;

    explicit Engine(const std::string & path, int worker_threads) : threads(worker_threads) {
        std::call_once(initialized, [] { llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr); llama_backend_init(); });
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model = llama_model_load_from_file(path.c_str(), params);
        if (!model) throw std::runtime_error("Could not load the verified Qwen model");
        model_owner.reset(model);
        vocab = llama_model_get_vocab(model);
        vocabulary = llama_vocab_n_tokens(vocab);
        pieces.resize(vocabulary);
        boundary.resize(vocabulary);
        for (int i = 0; i < vocabulary; ++i) {
            char buffer[512];
            int length = llama_token_to_piece(vocab, i, buffer, sizeof(buffer), 0, false);
            if (length > 0) pieces[i].assign(buffer, length);
            boundary[i] = llama_vocab_is_eog(vocab, i) || (!pieces[i].empty() && delimiter(static_cast<unsigned char>(pieces[i][0])));
        }
    }
    ~Engine() { if (context) llama_free(context); }
    static bool abort(void * data) { auto * self = static_cast<Engine *>(data); return generation.load() != self->expected; }
    void check() const { if (generation.load() != expected) throw std::runtime_error("Prediction cancelled"); }
    void reset() { cached.clear(); root_logits.clear(); if (context) llama_memory_clear(llama_get_memory(context), true); }
    void branches_clear() { if (context) for (int i = 1; i <= MAX_CANDIDATES; ++i) llama_memory_seq_rm(llama_get_memory(context), i, -1, -1); }

    std::vector<llama_token> tokenize(const std::string & text, bool special) {
        int size = llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, special, false);
        if (size >= 0) return {};
        std::vector<llama_token> tokens(-size);
        size = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), special, false);
        if (size < 0) throw std::runtime_error("Could not tokenize the sentence");
        tokens.resize(size);
        return tokens;
    }
    void ensure_capacity(int required) {
        if (required > 32768) throw std::runtime_error("The full sentence exceeds Qwen's context limit");
        if (context && capacity >= required) return;
        int size = 2048;
        while (size < required) size *= 2;
        if (context) { llama_free(context); context = nullptr; }
        capacity = 0;
        cached.clear(); root_logits.clear();
        auto params = llama_context_default_params();
        params.n_ctx = size;
        params.n_batch = 256;
        params.n_ubatch = 128;
        params.n_seq_max = MAX_CANDIDATES + 1;
        params.n_outputs_max = MAX_CANDIDATES + 1;
        params.n_outputs_max_per_seq = 1;
        params.kv_unified = true;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        params.type_k = GGML_TYPE_Q8_0;
        params.type_v = GGML_TYPE_Q8_0;
        params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        params.offload_kqv = false;
        params.abort_callback = abort;
        params.abort_callback_data = this;
        context = llama_init_from_model(model, params);
        if (!context) throw std::runtime_error("Not enough memory to prepare Qwen");
        capacity = size;
    }
    void decode(llama_batch batch) {
        check();
        if (llama_decode(context, batch) != 0) { reset(); check(); throw std::runtime_error("Qwen could not evaluate this sentence"); }
        check();
    }
    void prepare(std::string text, int64_t ticket) {
        expected = ticket; check();
        while (!text.empty() && space(text.back())) text.pop_back();
        // Frame a short fragment as ordinary message text, rather than an
        // isolated code/file-name completion in the base model's training mix.
        text = "Text message: " + text;
        auto tokens = tokenize(text, true);
        if (tokens.empty()) throw std::runtime_error("The editor did not provide sentence context");
        ensure_capacity(tokens.size() + 768);
        branches_clear();
        size_t common = 0;
        while (common < cached.size() && common < tokens.size() && cached[common] == tokens[common]) ++common;
        if (common == tokens.size() && common == cached.size() && !root_logits.empty()) return;
        // Re-evaluate the last common token to obtain its logits after rewinding.
        common = common > 0 ? common - 1 : 0;
        if (!llama_memory_seq_rm(llama_get_memory(context), 0, common, -1)) { reset(); common = 0; }
        for (size_t offset = common; offset < tokens.size();) {
            const int count = std::min<size_t>(256, tokens.size() - offset);
            auto batch = llama_batch_init(count, 0, 1);
            batch.n_tokens = count;
            for (int i = 0; i < count; ++i) {
                batch.token[i] = tokens[offset + i]; batch.pos[i] = offset + i;
                batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0;
                batch.logits[i] = offset + i + 1 == tokens.size();
            }
            try { decode(batch); } catch (...) { llama_batch_free(batch); throw; }
            llama_batch_free(batch);
            offset += count;
        }
        cached = tokens;
        const float * logits = llama_get_logits_ith(context, -1);
        if (!logits) throw std::runtime_error("Qwen did not return word probabilities");
        root_logits.assign(logits, logits + vocabulary);
    }
    double boundary_log_probability(const float * logits, double total) const {
        double mass = 0;
        for (int i = 0; i < vocabulary; ++i) if (boundary[i]) mass += std::exp(double(logits[i]) - total);
        return std::log(std::max(mass, 1e-30));
    }

    struct Node { std::string raw; double score; int seq; int parent; llama_token token; };
    std::vector<std::string> predict(const std::string & text, const std::string & typed, int64_t ticket) {
        prepare(text, ticket);
        const std::string prefix = folded(typed);
        const bool need_space = !text.empty();
        std::unordered_map<std::string, std::pair<std::string, double>> completed;
        std::vector<Node> live{{"", 0.0, 0, 0, 0}};
        std::vector<std::vector<float>> logits{root_logits};
        for (int depth = 0; depth < 6 && !live.empty(); ++depth) {
            check();
            std::vector<Node> next;
            for (size_t index = 0; index < live.size(); ++index) {
                const Node & node = live[index];
                const float * distribution = logits[index].data();
                const double total = log_sum(distribution, vocabulary);
                auto word = word_from(node.raw, need_space);
                if (!word.text.empty() && word.unicode_complete && starts(folded(word.text), prefix)) {
                    const double score = node.score + boundary_log_probability(distribution, total);
                    const auto key = folded(word.text);
                    auto found = completed.find(key);
                    if (found == completed.end() || score > found->second.second) completed[key] = {word.text, score};
                }
                // Keep only the highest-probability valid extensions, not dictionary completions.
                std::vector<Node> options;
                for (int token = 0; token < vocabulary; ++token) {
                    if (pieces[token].empty() || llama_vocab_is_eog(vocab, token)) continue;
                    const double score = node.score + distribution[token] - total;
                    if (options.size() >= BEAM && score <= options.back().score) continue;
                    const auto raw = node.raw + pieces[token];
                    const auto candidate = word_from(raw, need_space);
                    if (!candidate.valid || (candidate.text.empty() && depth > 0)) continue;
                    const auto norm = folded(candidate.text);
                    if (!starts(norm, prefix) && !starts(prefix, norm)) continue;
                    if (candidate.complete && !candidate.text.empty() && candidate.unicode_complete && starts(norm, prefix)) {
                        auto found = completed.find(norm);
                        if (found == completed.end() || score > found->second.second) completed[norm] = {candidate.text, score};
                        continue;
                    }
                    if (candidate.complete) continue;
                    options.push_back({raw, score, 0, node.seq, token});
                    std::sort(options.begin(), options.end(), [](const Node & a, const Node & b) { return a.score > b.score; });
                    if (options.size() > BEAM) options.resize(BEAM);
                }
                next.insert(next.end(), options.begin(), options.end());
            }
            std::sort(next.begin(), next.end(), [](const Node & a, const Node & b) { return a.score > b.score; });
            if (next.size() > BEAM) next.resize(BEAM);
            if (next.empty()) break;
            const int first_seq = depth % 2 == 0 ? 1 : BEAM + 1;
            auto batch = llama_batch_init(next.size(), 0, 1);
            batch.n_tokens = next.size();
            for (size_t i = 0; i < next.size(); ++i) {
                next[i].seq = first_seq + i;
                llama_memory_seq_rm(llama_get_memory(context), next[i].seq, -1, -1);
                llama_memory_seq_cp(llama_get_memory(context), next[i].parent, next[i].seq, -1, -1);
                batch.token[i] = next[i].token; batch.pos[i] = cached.size() + depth;
                batch.n_seq_id[i] = 1; batch.seq_id[i][0] = next[i].seq; batch.logits[i] = true;
            }
            try { decode(batch); } catch (...) { llama_batch_free(batch); throw; }
            logits.clear();
            for (size_t i = 0; i < next.size(); ++i) {
                const float * output = llama_get_logits_ith(context, i);
                if (!output) { llama_batch_free(batch); throw std::runtime_error("Missing Qwen branch probabilities"); }
                logits.emplace_back(output, output + vocabulary);
            }
            llama_batch_free(batch);
            live = std::move(next);
            if (completed.size() >= 3) {
                std::vector<double> scores;
                for (const auto & item : completed) scores.push_back(item.second.second);
                std::sort(scores.begin(), scores.end(), std::greater<double>());
                if (live.front().score < scores[2]) break;
            }
        }
        branches_clear(); check();
        std::vector<std::pair<std::string, double>> ranked;
        for (const auto & item : completed) ranked.push_back(item.second);
        std::sort(ranked.begin(), ranked.end(), [](const auto & a, const auto & b) { return a.second > b.second; });
        std::vector<std::string> result;
        for (size_t i = 0; i < std::min<size_t>(3, ranked.size()); ++i) result.push_back(ranked[i].first);
        return result;
    }

    std::vector<double> score(const std::string & text, const std::vector<std::string> & words, int64_t ticket) {
        prepare(text, ticket);
        if (words.size() > MAX_CANDIDATES) throw std::runtime_error("Too many swipe candidates");
        std::vector<std::vector<llama_token>> tokens;
        std::vector<double> scores(words.size(), -std::numeric_limits<double>::infinity());
        std::vector<std::vector<float>> outputs(words.size(), root_logits);
        for (size_t i = 0; i < words.size(); ++i) {
            tokens.push_back(tokenize((text.empty() ? "" : " ") + words[i], false));
            if (!tokens.back().empty() && tokens.back().size() <= 32) scores[i] = 0;
            llama_memory_seq_cp(llama_get_memory(context), 0, i + 1, -1, -1);
        }
        for (size_t depth = 0; depth < 32; ++depth) {
            check();
            std::vector<int> active;
            for (size_t i = 0; i < words.size(); ++i) if (std::isfinite(scores[i]) && depth < tokens[i].size()) active.push_back(i);
            if (active.empty()) break;
            auto batch = llama_batch_init(active.size(), 0, 1);
            batch.n_tokens = active.size();
            for (size_t j = 0; j < active.size(); ++j) {
                int i = active[j];
                scores[i] += outputs[i][tokens[i][depth]] - log_sum(outputs[i].data(), vocabulary);
                batch.token[j] = tokens[i][depth]; batch.pos[j] = cached.size() + depth;
                batch.n_seq_id[j] = 1; batch.seq_id[j][0] = i + 1; batch.logits[j] = true;
            }
            try { decode(batch); } catch (...) { llama_batch_free(batch); throw; }
            for (size_t j = 0; j < active.size(); ++j) {
                const float * output = llama_get_logits_ith(context, j);
                if (!output) { llama_batch_free(batch); throw std::runtime_error("Missing swipe probabilities"); }
                outputs[active[j]].assign(output, output + vocabulary);
            }
            llama_batch_free(batch);
        }
        for (size_t i = 0; i < words.size(); ++i) if (std::isfinite(scores[i]))
            scores[i] += boundary_log_probability(outputs[i].data(), log_sum(outputs[i].data(), vocabulary));
        branches_clear(); check();
        return scores;
    }
};

void fail(JNIEnv * env, const std::exception & error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); }
Engine * engine(jlong handle) { if (!handle) throw std::runtime_error("Qwen is not loaded"); return reinterpret_cast<Engine *>(handle); }
}

extern "C" JNIEXPORT jlong JNICALL Java_com_yboard_keyboard_local_QwenNative_load(JNIEnv * env, jobject, jstring path, jint threads) {
    try { return reinterpret_cast<jlong>(new Engine(utf8(env, path), std::clamp<int>(threads, 1, 4))); } catch (const std::exception & error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_yboard_keyboard_local_QwenNative_close(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Engine *>(handle); }
extern "C" JNIEXPORT jlong JNICALL Java_com_yboard_keyboard_local_QwenNative_cancel(JNIEnv *, jobject) { return ++generation; }
extern "C" JNIEXPORT void JNICALL Java_com_yboard_keyboard_local_QwenNative_clear(JNIEnv *, jobject, jlong handle) { if (handle) reinterpret_cast<Engine *>(handle)->reset(); }
extern "C" JNIEXPORT void JNICALL Java_com_yboard_keyboard_local_QwenNative_prepare(JNIEnv * env, jobject, jlong handle, jstring text, jlong ticket) {
    try { engine(handle)->prepare(utf8(env, text), ticket); } catch (const std::exception & error) { if (handle) engine(handle)->reset(); fail(env, error); }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_yboard_keyboard_local_QwenNative_predict(JNIEnv * env, jobject, jlong handle, jstring text, jstring prefix, jlong ticket) {
    try {
        auto words = engine(handle)->predict(utf8(env, text), utf8(env, prefix), ticket);
        auto result = env->NewObjectArray(words.size(), env->FindClass("java/lang/String"), nullptr);
        for (size_t i = 0; i < words.size(); ++i) { auto value = env->NewStringUTF(words[i].c_str()); env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value); }
        return result;
    } catch (const std::exception & error) { if (handle) engine(handle)->reset(); fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_yboard_keyboard_local_QwenNative_score(JNIEnv * env, jobject, jlong handle, jstring text, jobjectArray candidates, jlong ticket) {
    try {
        std::vector<std::string> words;
        for (jsize i = 0; i < env->GetArrayLength(candidates); ++i) { auto value = static_cast<jstring>(env->GetObjectArrayElement(candidates, i)); words.push_back(utf8(env, value)); env->DeleteLocalRef(value); }
        auto scores = engine(handle)->score(utf8(env, text), words, ticket);
        auto result = env->NewDoubleArray(scores.size()); env->SetDoubleArrayRegion(result, 0, scores.size(), scores.data()); return result;
    } catch (const std::exception & error) { if (handle) engine(handle)->reset(); fail(env, error); return nullptr; }
}
