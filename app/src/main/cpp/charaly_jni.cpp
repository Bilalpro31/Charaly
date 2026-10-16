// Charaly JNI bridge to llama.cpp.
//
// Design notes:
//  * One model at a time. A phone cannot afford two contexts, so the handle is
//    a single global.
//  * All llama.cpp calls happen on the thread JNI invokes us on; the Kotlin side
//    always dispatches to an IO dispatcher.
//  * Token callbacks are per-call JNI local refs, created and deleted here so a
//    long generation cannot leak references into the JVM.
//  * Cancellation is a flag checked between decoded tokens: llama.cpp has no
//    hard abort, but per-token granularity is what the UI needs.
//
// Locking (this is the part that used to be wrong, so it is spelled out):
//
//   g_state_mutex    guards the LoadedModel fields. Held for a handful of
//                    instructions at a time, never across a llama.cpp call and
//                    never across a callback into Kotlin.
//   g_error_mutex    guards g_last_error. Separate, so reporting a failure from
//                    inside a critical section can never re-enter a held lock.
//   g_session_mutex  held for the whole of load / free / generate / benchmark.
//                    This is the "one context at a time" lock: it stops the
//                    context from being freed while a decode is running, and it
//                    serialises two decodes against each other.
//
// The critical rule: the token and progress callbacks are invoked while
// g_session_mutex is held but g_state_mutex is NOT. A Kotlin callback may
// therefore call back into contextSize(), modelMetadata(), lastError() or
// stopGeneration() without deadlocking. Calling loadModel()/freeModel() from
// *inside* a callback would still be unsafe, so that case is detected and
// refused with a clear error instead of hanging (see g_in_kotlin_callback).

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

namespace {

struct LoadedModel {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int context_size = 0;
    std::vector<std::pair<std::string, std::string>> metadata;
};

std::mutex g_state_mutex;
std::mutex g_error_mutex;
std::mutex g_session_mutex;
std::mutex g_stats_mutex;

LoadedModel g_model;
std::atomic<bool> g_cancel{false};
std::string g_last_error;

// ---------------------------------------------------------------------------
// Per-turn timings for the REAL generation path (not the benchmark).
//
// The UI's "STORY TURN PERF" developer readout is only worth showing if every number
// in it came from a clock. These are taken around the same tokenize / eval / decode
// steps a story turn actually runs, and read back through `lastGenerateStats`.
//
// `reused_tokens` is the number of leading prompt tokens whose key/value state was
// already in the cache from the previous turn (prefix reuse); `prompt_tokens` counts
// tokens actually evaluated this call. They differ exactly when prefix caching paid off.
// ---------------------------------------------------------------------------
struct GenerateStats {
    bool valid = false;
    int64_t prompt_micros = -1;
    int64_t tokenize_micros = -1;
    int64_t eval_micros = -1;
    int64_t generation_micros = -1;
    int64_t first_token_micros = -1;
    int prompt_tokens = 0;
    int generated_tokens = 0;
    int reused_tokens = 0;
    int cache_tokens = 0;
    bool reuse_fallback = false;
    int context_size = 0;
    // Why the decode loop ended, and how much room it had. "eog" is the model stopping
    // itself; "length" is the requested budget being reached. The two are very different
    // facts, and reporting both as "COMPLETED" is how a capped ramble looks like a normal
    // reply.
    std::string stop_reason = "none";
    int budget = 0;
    int requested_max = 0;
    int threads = 0;
    /**
     * PHASE 10: the prompt-evaluation pool, reported separately from the decode pool.
     *
     * They are different numbers for a reason and the difference was the bug: time to
     * first token is dominated by the batch pool, so a readout that named only `threads`
     * described the half of the work that is not the bottleneck.
     */
    int threads_batch = 0;
};

GenerateStats g_last_stats;

// The exact token sequence currently represented in the model's key/value cache, so the
// next turn evaluates only what changed, and is touched only while `g_session_mutex` is
// held (generate / load / free / benchmark all take it). It is cleared whenever the context
// is released, so it can never outlive the state it describes.
std::vector<llama_token> g_cached_tokens;

void set_stats(const GenerateStats & stats) {
    std::lock_guard<std::mutex> lock(g_stats_mutex);
    g_last_stats = stats;
}

GenerateStats copy_stats() {
    std::lock_guard<std::mutex> lock(g_stats_mutex);
    return g_last_stats;
}

// True while this thread is inside a Kotlin callback that native code invoked.
// A Kotlin callback that re-enters load/free from the *same* thread would
// otherwise wait on g_session_mutex, which it already owns, and hang the app.
thread_local bool g_in_kotlin_callback = false;

std::once_flag g_backend_once;

void set_error(const std::string & message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
}

std::string current_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

void clear_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error.clear();
}

// ---------------------------------------------------------------------------
// JNI string conversion
//
// NewStringUTF takes a NUL-terminated *modified* UTF-8 string and offers no way
// to pass a length, so two things have to hold before a token piece or a
// finished reply can cross the boundary:
//
//   1. no embedded NUL - everything after it would be silently dropped, which
//      truncates a reply rather than failing loudly;
//   2. valid UTF-8 - otherwise the decoder decides what the bytes meant.
//
// llama.cpp's detokenizer emits raw bytes, and a byte-fallback BPE token is a
// *single byte* of a multi-byte character. So an individual piece is routinely
// not valid UTF-8 on its own, and handing it straight to NewStringUTF is how a
// Turkish "i" with a dot or an emoji becomes replacement characters in the
// middle of a story. Bytes are validated here; anything malformed becomes U+FFFD,
// which is visible and harmless rather than silent and wrong.
// ---------------------------------------------------------------------------

constexpr const char * UTF8_REPLACEMENT = "\xEF\xBF\xBD";

bool is_continuation(unsigned char c) {
    return (c & 0xC0) == 0x80;
}

void append_utf8(std::string & out, unsigned int cp) {
    if (cp < 0x80u) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800u) {
        out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp < 0x10000u) {
        out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else {
        out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    }
}

// Returns how many bytes of `in` starting at `i` form a complete, valid UTF-8
// sequence. Returns 0 for a malformed byte, and sets `truncated` when the tail
// is a valid prefix that only the next token can complete.
size_t utf8_sequence_length(const unsigned char * in, size_t remaining, bool & truncated) {
    truncated = false;
    if (remaining == 0) return 0;

    const unsigned char lead = in[0];
    size_t length = 0;
    unsigned int cp = 0;

    if (lead < 0x80u) return 1;
    if ((lead & 0xE0u) == 0xC0u) { length = 2; cp = lead & 0x1Fu; }
    else if ((lead & 0xF0u) == 0xE0u) { length = 3; cp = lead & 0x0Fu; }
    else if ((lead & 0xF8u) == 0xF0u) { length = 4; cp = lead & 0x07u; }
    else return 0;  // continuation byte or 5/6-byte lead: never valid here

    if (remaining < length) {
        // Could still be a valid sequence once the rest of the token arrives.
        bool all_continuation = true;
        for (size_t k = 1; k < remaining; ++k) {
            if (!is_continuation(in[k])) { all_continuation = false; break; }
        }
        truncated = all_continuation;
        return 0;
    }

    for (size_t k = 1; k < length; ++k) {
        if (!is_continuation(in[k])) return 0;
        cp = (cp << 6) | (in[k] & 0x3Fu);
    }

    // Reject overlong forms, surrogates and out-of-range code points.
    if (length == 2 && cp < 0x80u) return 0;
    if (length == 3 && cp < 0x800u) return 0;
    if (length == 4 && cp < 0x10000u) return 0;
    if (cp > 0x10FFFFu) return 0;
    if (cp >= 0xD800u && cp <= 0xDFFFu) return 0;

    return length;
}

// Validates UTF-8 and replaces anything malformed (including embedded NUL) with
// U+FFFD. `pending` carries an incomplete trailing sequence between calls, so a
// character split across two tokens is neither dropped nor mangled.
std::string sanitize_utf8(const std::string & in, std::string & pending) {
    std::string combined;
    combined.reserve(pending.size() + in.size());
    combined.append(pending);
    combined.append(in);
    pending.clear();

    std::string out;
    out.reserve(combined.size());

    const auto * bytes = reinterpret_cast<const unsigned char *>(combined.data());
    size_t i = 0;
    while (i < combined.size()) {
        if (bytes[i] == 0x00u) {
            // NewStringUTF would stop here and truncate the whole reply.
            out.append(UTF8_REPLACEMENT);
            ++i;
            continue;
        }
        bool truncated = false;
        const size_t length = utf8_sequence_length(bytes + i, combined.size() - i, truncated);
        if (length == 0) {
            if (truncated) {
                // Hold the tail back; the next token completes it.
                pending.assign(combined, i, combined.size() - i);
                break;
            }
            out.append(UTF8_REPLACEMENT);
            ++i;
            continue;
        }
        out.append(combined, i, length);
        i += length;
    }
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & text) {
    // Sanitizing here means the whole text is validated in one pass, so a trailing
    // partial sequence is simply reported as malformed rather than held back: the
    // streaming path is what carries `pending` across tokens, and by the time a
    // reply is returned as one string there is no next token to wait for.
    std::string pending;
    return env->NewStringUTF(sanitize_utf8(text, pending).c_str());
}

// ---------------------------------------------------------------------------
// Kotlin function callbacks
//
// A Kotlin lambda `(Int, String) -> Unit` is a `kotlin.jvm.functions.Function2`
// and a `(String) -> Unit` lambda is a `Function1`. Since Kotlin 2.0 those are
// `invokedynamic` classes whose ONLY method is the erased SAM bridge:
//
//     Function2   invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
//     Function1   invoke(Ljava/lang/Object;)Ljava/lang/Object;
//
// The previous code looked for `(ILjava/lang/String;)V` and
// `(Ljava/lang/String;)V`. Those descriptors only exist on a class-shaped
// lambda, never on an indy one, so GetMethodID returned null and every progress
// and token callback silently never ran: the UI streamed nothing while the app
// looked perfectly healthy. The erased bridge is the descriptor guaranteed by
// `kotlin.jvm.functions.FunctionN`, so it is used here, with the Int argument
// boxed to java.lang.Integer and the returned kotlin.Unit released.
// ---------------------------------------------------------------------------

struct KotlinCallback {
    jmethodID invoke = nullptr;
    bool valid = false;
};

KotlinCallback resolve_function1(JNIEnv * env, jobject function, const char * what) {
    KotlinCallback callback;
    if (function == nullptr) {
        set_error(std::string("callback contract failure: ") + what + " was null");
        return callback;
    }
    jclass function_class = env->GetObjectClass(function);
    if (function_class == nullptr) {
        set_error(std::string("callback contract failure: cannot inspect ") + what);
        return callback;
    }
    callback.invoke = env->GetMethodID(
        function_class,
        "invoke",
        "(Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(function_class);
    if (callback.invoke == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        set_error(std::string("callback contract failure: ") + what +
                  " does not expose kotlin.jvm.functions.Function1.invoke");
        return callback;
    }
    callback.valid = true;
    return callback;
}

KotlinCallback resolve_function2(JNIEnv * env, jobject function, const char * what) {
    KotlinCallback callback;
    if (function == nullptr) {
        set_error(std::string("callback contract failure: ") + what + " was null");
        return callback;
    }
    jclass function_class = env->GetObjectClass(function);
    if (function_class == nullptr) {
        set_error(std::string("callback contract failure: cannot inspect ") + what);
        return callback;
    }
    callback.invoke = env->GetMethodID(
        function_class,
        "invoke",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(function_class);
    if (callback.invoke == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        set_error(std::string("callback contract failure: ") + what +
                  " does not expose kotlin.jvm.functions.Function2.invoke");
        return callback;
    }
    callback.valid = true;
    return callback;
}

// Throws a Java exception when one is pending. Used before any further JNI call,
// because almost every JNI function behaves undefinedly with a live exception.
bool throw_if_pending(JNIEnv * env, const char * context) {
    if (!env->ExceptionCheck()) return false;
    jthrowable pending = env->ExceptionOccurred();
    env->ExceptionClear();
    if (pending != nullptr) {
        std::string description = std::string(context) + " threw from Kotlin";
        jclass throwable_class = env->FindClass("java/lang/Throwable");
        if (throwable_class != nullptr) {
            jmethodID to_string = env->GetMethodID(throwable_class, "toString", "()Ljava/lang/String;");
            if (to_string != nullptr) {
                auto text = static_cast<jstring>(env->CallObjectMethod(pending, to_string));
                if (text != nullptr) {
                    const char * chars = env->GetStringUTFChars(text, nullptr);
                    if (chars != nullptr) {
                        description = std::string(chars);
                        env->ReleaseStringUTFChars(text, chars);
                    }
                    env->DeleteLocalRef(text);
                }
            }
            env->DeleteLocalRef(throwable_class);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(pending);
        set_error(description);
    }
    return true;
}

void release_model_locked() {
    if (g_model.ctx != nullptr) {
        llama_free(g_model.ctx);
        g_model.ctx = nullptr;
    }
    if (g_model.model != nullptr) {
        llama_model_free(g_model.model);
        g_model.model = nullptr;
    }
    g_model.vocab = nullptr;
    g_model.context_size = 0;
    g_model.metadata.clear();
}

// Every GGUF key/value the loader saw, plus the two derived figures the Kotlin
// side reads. The previous version emitted only "general.name", so
// `metadata["parameters"]` and `metadata["quantization"]` never existed and
// ModelInfo reported a parameter count of 0 for every model ever loaded.
std::vector<std::pair<std::string, std::string>> collect_metadata(const llama_model * model) {
    std::vector<std::pair<std::string, std::string>> out;

    const int count = llama_model_meta_count(model);
    for (int i = 0; i < count; ++i) {
        char key[256];
        const int key_written = llama_model_meta_key_by_index(model, i, key, sizeof(key));
        if (key_written <= 0) continue;

        char value[512];
        const int value_written = llama_model_meta_val_str_by_index(model, i, value, sizeof(value));
        if (value_written < 0) continue;

        out.emplace_back(std::string(key, static_cast<size_t>(key_written)),
                          value_written > 0 ? std::string(value, static_cast<size_t>(value_written))
                                            : std::string());
    }

    // Derived, from the real header rather than from the file name.
    out.emplace_back("parameters", std::to_string(llama_model_n_params(model)));
    out.emplace_back("size_bytes", std::to_string(llama_model_size(model)));
    out.emplace_back("context_train", std::to_string(llama_model_n_ctx_train(model)));

    // `llama_model_desc()` is NOT the quantization: it is "<arch> <size> <ftype name>",
    // and reporting it as the quantization put a whole sentence where "Q4_K_M" belongs.
    // The real file type is in the GGUF metadata itself (`general.file_type`, the same
    // `llama_ftype` enum the loader uses); it is emitted by the loop above, and the
    // Kotlin side maps it to a label. Here we only record the human description
    // under its own key.
    char description[512];
    const int description_written = llama_model_desc(model, description, sizeof(description));
    if (description_written > 0) {
        out.emplace_back("description",
                         std::string(description, static_cast<size_t>(description_written)));
    }

    if (out.empty()) {
        out.emplace_back("general.name", "unknown");
    }
    return out;
}

// ---------------------------------------------------------------------------
// Prompt evaluation, shared by generate() and benchmark()
//
// Both paths tokenize the same way and walk the prompt in the same 512-token chunks.
// Keeping one implementation is what makes a benchmark meaningful: a tok/s figure
// measured over a different prompt path than the one the app actually uses would be a
// number about code that never runs.
// ---------------------------------------------------------------------------

using Clock = std::chrono::steady_clock;

int64_t micros_since(const Clock::time_point & start) {
    const auto delta = Clock::now() - start;
    return std::chrono::duration_cast<std::chrono::microseconds>(delta).count();
}

// Tokenizes `prompt`, refusing if it would not leave room for `max_tokens`.
bool tokenize_prompt(
    const llama_vocab * vocab,
    int context_size,
    const std::string & prompt,
    int max_tokens,
    int reserve,
    std::vector<llama_token> & out) {
    if (vocab == nullptr) {
        set_error("no vocabulary is available for the loaded model");
        return false;
    }
    const int max_prompt_tokens = context_size - max_tokens - reserve;
    if (max_prompt_tokens <= 0) {
        set_error("context window too small for the requested maxTokens");
        return false;
    }

    out.resize(static_cast<size_t>(max_prompt_tokens));
    const int token_count = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        out.data(),
        max_prompt_tokens,
        /* add_special = */ true,
        /* parse_special = */ false);
    if (token_count < 0) {
        // llama.cpp returns the negated required size, so this is a prompt that
        // does not fit rather than an unparseable one. Saying so is the difference
        // between "shorten this" and "your model is broken".
        set_error(token_count == -max_prompt_tokens
                      ? "prompt is longer than the context window allows"
                      : "failed to tokenize prompt");
        out.clear();
        return false;
    }
    out.resize(static_cast<size_t>(token_count));
    return true;
}

// Feeds `tokens` to the context.
//
// Taken by *non*-const reference because `llama_batch_get_one` takes a mutable token pointer:
// it advances the pointer as it walks the batch, and llama.cpp's own examples declare the
// batch locally and hand over a mutable vector for exactly that reason.
//
// Starts at `start`, so a caller that already holds the key/value state for
// `tokens[0, start)` (prefix reuse) does not pay to evaluate it again.
bool eval_range(llama_context * ctx, std::vector<llama_token> & tokens, int start, std::string & error) {
    const int chunk = 512;
    for (size_t offset = static_cast<size_t>(start); offset < tokens.size(); offset += static_cast<size_t>(chunk)) {
        if (g_cancel.load()) {
            error = "cancelled";
            return false;
        }
        const int batch_n = static_cast<int>(std::min<size_t>(tokens.size() - offset, static_cast<size_t>(chunk)));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, batch_n);
        if (llama_decode(ctx, batch) != 0) {
            error = "llama_decode failed while processing the prompt";
            return false;
        }
    }
    return true;
}

// Full clear + full evaluation. Used by the benchmark, which measures one fixed prompt in
// isolation, and kept as the safe fallback when a cached prefix cannot be reused.
bool eval_prompt(llama_context * ctx, std::vector<llama_token> & tokens, std::string & error) {
    llama_memory_clear(llama_get_memory(ctx), true);
    return eval_range(ctx, tokens, 0, error);
}

// How many leading tokens two prompts share. This is the whole of prefix reuse: the shared
// run's key/value state is already in the cache, so only the tail has to be evaluated.
int common_prefix(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    const int n = static_cast<int>(std::min(a.size(), b.size()));
    int i = 0;
    while (i < n && a[i] == b[i]) {
        ++i;
    }
    return i;
}

// The sampler chain used by both paths, so a benchmark measures the same sampling work
// a story turn does. Null on allocation failure, which the callers check.
llama_sampler * build_sampler(
    int top_k,
    float top_p,
    float min_p,
    float temperature,
    float repeat_penalty,
    uint32_t seed) {
    auto * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) return nullptr;
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    // min-p BEFORE temperature: it prunes the long tail of near-zero probability tokens a
    // small model's Turkish output is full of, and it prunes by probability rather than by
    // rank, so what survives is the model's actual confidence. On the raw distribution, so
    // it must run before the temperature warp or the cutoff moves with the temperature.
    if (min_p > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(64, repeat_penalty, 1.0f, 1.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

// key/value pairs -> [key, value, key, value, ...] as a String[].
//
// The array is allocated with 2 * entries so the i * 2 / i * 2 + 1 writes land
// inside it. The old code sized it with entries.size() and then wrote at twice
// that index, which is an out-of-bounds write for any call with two or more keys.
jobjectArray string_pairs_to_java(JNIEnv * env, const std::vector<std::pair<std::string, std::string>> & entries) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(
        static_cast<jsize>(entries.size() * 2), string_class, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < entries.size(); ++i) {
        jstring key = env->NewStringUTF(entries[i].first.c_str());
        jstring value = env->NewStringUTF(entries[i].second.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i * 2), key);
        env->SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), value);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    return out;
}

jobjectArray empty_string_array(JNIEnv * env) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    return env->NewObjectArray(0, string_class, nullptr);
}

std::vector<std::string> read_stop_sequences(JNIEnv * env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize count = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (element == nullptr) continue;
        const char * chars = env->GetStringUTFChars(element, nullptr);
        if (chars != nullptr) {
            out.emplace_back(chars);
            env->ReleaseStringUTFChars(element, chars);
        }
        env->DeleteLocalRef(element);
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_charaly_app_inference_LlamaNative_nativeAvailable(JNIEnv *, jclass) {
    // Reaching this function at all means System.loadLibrary("charaly_llama")
    // succeeded, which is exactly what "the native engine is available" means:
    // the library is loaded and the JNI bridge into it is callable. It is NOT a
    // claim about a model being resident - ModelSelection.isResident is that, and
    // it comes from a different place.
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_dev_charaly_app_inference_LlamaNative_nativeVersion(JNIEnv * env, jclass) {
    const char * version = llama_print_system_info();
    return env->NewStringUTF(version != nullptr ? version : "unknown");
}

JNIEXPORT jlong JNICALL
Java_dev_charaly_app_inference_LlamaNative_loadModel(
    JNIEnv * env,
    jclass,
    jstring j_path,
    jint j_context_size,
    jint j_threads,
    jint j_threads_batch,
    jint j_gpu_layers,
    jobject j_progress) {
    // Refusing a re-entrant load is what keeps a Kotlin progress callback from
    // deadlocking against the session lock it is already running under.
    if (g_in_kotlin_callback) {
        set_error("loadModel cannot be called from inside a native callback");
        return 0;
    }

    if (j_path == nullptr) {
        set_error("invalid model path");
        return 0;
    }
    const char * path = env->GetStringUTFChars(j_path, nullptr);
    if (path == nullptr) {
        set_error("invalid model path");
        return 0;
    }
    const std::string model_path(path);
    env->ReleaseStringUTFChars(j_path, path);

    const KotlinCallback on_progress = resolve_function2(env, j_progress, "the load progress callback");
    if (!on_progress.valid) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }

    jclass integer_class = env->FindClass("java/lang/Integer");
    jmethodID integer_value_of =
        integer_class != nullptr
            ? env->GetStaticMethodID(integer_class, "valueOf", "(I)Ljava/lang/Integer;")
            : nullptr;
    if (integer_class == nullptr || integer_value_of == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        set_error("callback contract failure: java.lang.Integer.valueOf is unavailable");
        return 0;
    }

    // The callback is invoked while the session lock is held but the state lock
    // is not, so a progress handler may call contextSize() or lastError() safely.
    auto report = [&](int percent, const char * stage) {
        jstring jstage = to_jstring(env, std::string(stage));
        auto boxed = static_cast<jobject>(env->CallStaticObjectMethod(
            integer_class, integer_value_of, static_cast<jint>(percent)));
        jobject unit = nullptr;
        {
            g_in_kotlin_callback = true;
            unit = static_cast<jobject>(
                env->CallObjectMethod(j_progress, on_progress.invoke, boxed, jstage));
            g_in_kotlin_callback = false;
        }
        env->DeleteLocalRef(boxed);
        env->DeleteLocalRef(jstage);
        if (unit != nullptr) env->DeleteLocalRef(unit);
        // A throwing progress handler must not leave a live exception while we go
        // on to call llama.cpp.
        throw_if_pending(env, "the load progress callback");
    };

    std::unique_lock<std::mutex> session(g_session_mutex);
    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        release_model_locked();
    }
    // The weights are gone; the cached prefix describes them, so it goes too.
    g_cached_tokens.clear();
    clear_error();

    // Defensive gate at the boundary: never hand a path llama.cpp will reject
    // (or worse, a corrupt header llama.cpp may abort on) to the loader. Kotlin
    // has already checked; this is the last check that cannot be bypassed by a
    // caller that skipped it.
    {
        FILE * probe = std::fopen(model_path.c_str(), "rb");
        if (probe == nullptr) {
            set_error("cannot open model file");
            return 0;
        }
        char magic[4] = {0, 0, 0, 0};
        const size_t read = std::fread(magic, 1, sizeof(magic), probe);
        std::fclose(probe);
        if (read != sizeof(magic) || magic[0] != 'G' || magic[1] != 'G' ||
            magic[2] != 'U' || magic[3] != 'F') {
            set_error("not a GGUF file");
            return 0;
        }
    }

    std::call_once(g_backend_once, []() { llama_backend_init(); });

    auto params = llama_model_default_params();
    // Offload layers to an accelerator.
    //
    // Reachable from Kotlin (`EngineConfig.gpuLayers` -> this) and currently always 0,
    // because this build compiles CPU backends only - see the note at the top of
    // CMakeLists.txt. It is kept wired and kept honest: `LlamaNative.availableBackends()`
    // reports what ggml actually has, the app's model library renders only what that
    // returns, and a recorded benchmark stores the offload count it ran with. So the
    // comparison a future Vulkan or OpenCL build needs - CPU baseline against accelerated,
    // same prompt, same sampler - can be made without touching anything but this number.
    params.n_gpu_layers = j_gpu_layers;

    report(5, "opening");
    llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
    if (model == nullptr) {
        set_error("llama.cpp could not read this file as a model");
        report(0, "failed");
        return 0;
    }

    report(60, "creating context");
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(j_context_size > 0 ? j_context_size : 2048);
    // PHASE 10 — PROMPT EVALUATION THREADS WERE PINNED AT FOUR, REGARDLESS OF ANYTHING.
    //
    // llama.cpp runs two different thread pools: `n_threads` for token-by-token decoding
    // and `n_threads_batch` for evaluating a prompt, and `llama_context::graph_compute`
    // picks between them with `batched ? n_threads_batch : n_threads`. Both default to
    // `GGML_DEFAULT_N_THREADS`, which this checkout defines as a flat 4 (ggml/include/
    // ggml.h:228).
    //
    // So every Charaly turn evaluated its prompt on four threads. Measured on real
    // Qwen3-4B-Instruct-2507-Q4_K_M weights, 8-core ARM64 host, one 4096-token context:
    // prompt evaluation ran at ~3.3 tokens/second, and prompt evaluation is 85-95% of the
    // time to first token (Phase10TurkishLatencyTest, 4 turns: eval 437s / 186s / 240s /
    // 369s against generation of 52s / 91s / 90s / 106s). Nothing Charaly did could fix
    // that from Kotlin, because the only knob it had - `threads` - sets `n_threads`, and
    // `n_threads_batch` was not reachable at all.
    //
    // The two pools have genuinely different shapes and want different numbers:
    //   * decoding one token is memory-bandwidth bound, and flattens past ~4 threads;
    //   * evaluating a prompt is compute bound and scales nearly linearly.
    // So batch evaluation gets the device's core count (capped, below) while decoding
    // keeps llama.cpp's default unless the caller asks for something else.
    //
    // The cap exists because `hardware_concurrency()` on a phone reports every core on
    // every cluster, and using all of them for a long prompt evaluation costs battery for
    // throughput the player cannot see. Sixteen is comfortably past the point of return
    // on any current mobile SoC and keeps a desktop host honest too.
    if (j_threads > 0) ctx_params.n_threads = j_threads;
    if (j_threads_batch > 0) {
        ctx_params.n_threads_batch = j_threads_batch;
    } else {
        const unsigned int cores = std::thread::hardware_concurrency();
        if (cores > 0) {
            ctx_params.n_threads_batch = static_cast<int32_t>(cores > 16u ? 16u : cores);
        }
    }
    ctx_params.n_batch = 512;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        set_error("llama.cpp could not create a context for this model (not enough memory?)");
        report(0, "failed");
        return 0;
    }

    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        g_model.model = model;
        g_model.ctx = ctx;
        g_model.vocab = llama_model_get_vocab(model);
        g_model.context_size = static_cast<int>(llama_n_ctx(ctx));
        g_model.metadata = collect_metadata(model);
    }

    report(100, "ready");

    // Handle 1 is a valid, non-null sentinel for "a model is loaded".
    return 1;
}

JNIEXPORT void JNICALL
Java_dev_charaly_app_inference_LlamaNative_freeModel(JNIEnv *, jclass, jlong handle) {
    if (handle == 0) return;
    if (g_in_kotlin_callback) {
        set_error("freeModel cannot be called from inside a native callback");
        return;
    }
    // Blocks until any in-flight decode finishes, so the context cannot be freed
    // underneath a running generation.
    std::unique_lock<std::mutex> session(g_session_mutex);
    std::lock_guard<std::mutex> state(g_state_mutex);
    release_model_locked();
    g_cached_tokens.clear();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_charaly_app_inference_LlamaNative_modelMetadata(JNIEnv * env, jclass, jlong handle) {
    std::vector<std::pair<std::string, std::string>> snapshot;
    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        if (handle == 0 || g_model.metadata.empty()) {
            return empty_string_array(env);
        }
        snapshot = g_model.metadata;
    }
    return string_pairs_to_java(env, snapshot);
}

JNIEXPORT jint JNICALL
Java_dev_charaly_app_inference_LlamaNative_contextSize(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> state(g_state_mutex);
    if (handle == 0) return 0;
    return g_model.context_size;
}

JNIEXPORT jstring JNICALL
Java_dev_charaly_app_inference_LlamaNative_generate(
    JNIEnv * env,
    jclass,
    jlong handle,
    jstring j_prompt,
    jint j_max_tokens,
    jfloat j_temperature,
    jfloat j_top_p,
    jint j_top_k,
    jfloat j_min_p,
    jfloat j_repeat_penalty,
    jlong j_seed,
    jobjectArray j_stop_sequences,
    jobject j_token_callback) {
    if (handle == 0) {
        set_error("no model loaded");
        return to_jstring(env, std::string());
    }

    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int context_size = 0;
    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        if (g_model.ctx == nullptr) {
            set_error("no model loaded");
            return to_jstring(env, std::string());
        }
        ctx = g_model.ctx;
        vocab = g_model.vocab;
        context_size = g_model.context_size;
    }

    if (j_prompt == nullptr) {
        set_error("prompt was null");
        return to_jstring(env, std::string());
    }
    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (prompt_chars == nullptr) {
        set_error("prompt could not be read");
        return to_jstring(env, std::string());
    }
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    const std::vector<std::string> stop_sequences = read_stop_sequences(env, j_stop_sequences);

    const KotlinCallback on_token = resolve_function1(env, j_token_callback, "the token callback");
    if (!on_token.valid) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return to_jstring(env, std::string());
    }

    g_cancel.store(false);

    // Serialises this decode against loadModel/freeModel/benchmark. The state lock
    // is deliberately NOT held while decoding or while calling back into Kotlin, so
    // a token callback can query contextSize()/modelMetadata()/lastError() safely.
    std::unique_lock<std::mutex> session(g_session_mutex);

    // Re-read under the session lock: a free may have landed between the two.
    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        if (g_model.ctx == nullptr) {
            set_error("no model loaded");
            return to_jstring(env, std::string());
        }
        ctx = g_model.ctx;
        vocab = g_model.vocab;
        context_size = g_model.context_size;
    }

    clear_error();

    const int reserve = 8;
    const int requested = j_max_tokens > 0 ? j_max_tokens : 0;

    // 1-2. Tokenize and evaluate the prompt.
    const auto prompt_start = Clock::now();
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(vocab, context_size, prompt, requested, reserve, tokens)) {
        return to_jstring(env, std::string());
    }
    if (tokens.empty()) {
        set_error("prompt produced no tokens");
        return to_jstring(env, std::string());
    }
    const int token_count = static_cast<int>(tokens.size());
    // Tokenization and evaluation are separate costs with separate fixes; keeping them in
    // one number hides which one a slow turn is paying for.
    const auto eval_start = Clock::now();
    const int64_t tokenize_micros = micros_since(prompt_start);

    // Prefix reuse: evaluate only the tokens that are not already in the cache.
    //
    // Without this every turn re-ran the ENTIRE prompt - system instructions, world rules,
    // knowledge, memories and the whole recent transcript - before producing a single word.
    // On a phone that is the difference between the first token arriving in about a second
    // and it arriving twenty-plus seconds later. The token stream handed to the model is
    // identical either way; only the already-computed key/value state is kept.
    llama_memory_t memory = llama_get_memory(ctx);
    const int cache_tokens = static_cast<int>(g_cached_tokens.size());
    bool reuse_fallback = false;
    int reused = common_prefix(g_cached_tokens, tokens);
    // A fresh sample needs a fresh logit vector, so at least one token is always evaluated.
    if (reused >= token_count && reused > 0) reused = token_count - 1;
    const llama_pos present = llama_memory_seq_pos_max(memory, 0) + 1;
    if (reused < present) {
        if (!llama_memory_seq_rm(memory, 0, reused, -1)) {
            // A cache that cannot drop a partial sequence (recurrent, or some
            // sliding-window types) must not make correctness depend on prefix reuse:
            // fall back to a full clear and a full evaluation.
            llama_memory_clear(memory, true);
            reused = 0;
            reuse_fallback = true;
        }
    }
    // Record the prompt as the cache's contents before decoding, and extend it as tokens
    // are generated, so the next turn's cache length and this vector agree.
    g_cached_tokens.assign(tokens.begin(), tokens.end());

    std::string prompt_error;
    if (!eval_range(ctx, tokens, reused, prompt_error)) {
        g_cached_tokens.clear();
        set_error(prompt_error == "cancelled" ? "generation cancelled" : prompt_error);
        return to_jstring(env, std::string());
    }
    const int64_t prompt_micros = micros_since(prompt_start);

    // 3. Sampler: temperature / top-p / top-k / min-p plus the repetition penalty.
    llama_sampler * ctx_sampler = build_sampler(
        j_top_k, j_top_p, j_min_p, j_temperature, j_repeat_penalty,
        static_cast<uint32_t>(j_seed));
    if (ctx_sampler == nullptr) {
        set_error("could not build the sampler chain");
        return to_jstring(env, std::string());
    }

    // 4. Decode loop, emitting each token as it is produced.
    const int n_ctx = context_size;
    const int budget = std::min(requested, n_ctx - token_count - reserve);
    if (budget <= 0) {
        llama_sampler_free(ctx_sampler);
        set_error("no room left in the context to generate: the prompt is too long");
        return to_jstring(env, std::string());
    }

    std::string output;
    std::string pending_utf8;
    /**
     * How much of `output` has already been handed to the token callback.
     *
     * PHASE 9. Stop matching used to look only at the last `stop.size()` bytes of the
     * accumulated text, so the marker had to land exactly on the end of a reply. A model
     * that emits its own ChatML marker and then keeps writing never matched, and the
     * marker reached the reader. Measured on real weights with Qwen3-4B-Instruct-2507
     * Q4_K_M: all four Turkish replies ended with a literal `<|im_end|>`.
     *
     * Streaming makes a second, harder case. `<|im_end|>` can arrive split across several
     * token pieces - `<|im` then `_end|>` - and a matcher that only inspects what this
     * iteration added cannot see the whole marker in either iteration. Deleting the tail
     * eagerly instead has the opposite failure: a *partial* marker reaches the reader
     * before it is known to be one.
     *
     * So the tail is held back: at most `max_stop_len - 1` bytes are ever withheld, they
     * are re-examined with the next piece, and a byte is emitted only once it can no
     * longer begin a marker. Valid text is never deleted (a match truncates from the
     * match onward, never from before it), nothing after a stop is ever emitted, and the
     * withheld tail is flushed once generation ends.
     */
    size_t emitted = 0;
    size_t max_stop_len = 0;
    for (const auto & stop : stop_sequences) {
        if (!stop.empty()) max_stop_len = std::max(max_stop_len, stop.size());
    }
    const size_t hold_back = max_stop_len > 1 ? max_stop_len - 1 : 0;
    int position = token_count;
    int generated = 0;
    bool stopped = false;

    const auto decode_start = Clock::now();
    int64_t first_token_micros = -1;

    // llama_token_to_piece does not write a terminator, so the buffer is owned by a
    // vector sized to the token and never handed to NewStringUTF as raw stack memory.
    std::vector<char> piece;

    while (generated < budget && !g_cancel.load()) {
        llama_token id = llama_sampler_sample(ctx_sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }
        // TTFT is measured here: everything from the start of the call to the first
        // token the model actually produced. This is the number a reader feels.
        if (first_token_micros < 0) first_token_micros = micros_since(decode_start);

        piece.assign(8, '\0');
        int written = llama_token_to_piece(vocab, id, piece.data(),
                                           static_cast<int32_t>(piece.size()), 0, false);
        if (written < 0) {
            piece.assign(static_cast<size_t>(-written) + 1, '\0');
            written = llama_token_to_piece(vocab, id, piece.data(),
                                           static_cast<int32_t>(piece.size()), 0, false);
        }
        if (written > 0) {
            const size_t before = output.size();
            output.append(piece.data(), static_cast<size_t>(written));

            // Stop sequences end the reply AT the marker. The search runs over the part of
            // the text that has not been emitted yet, and that is what makes three
            // properties true at the same time:
            //
            //   - a marker split across pieces is seen, because the bytes that could still
            //     become one were never handed to the reader;
            //   - a marker already emitted in an earlier iteration is not re-found, and
            //     never retroactively deletes text the reader has seen;
            //   - the earliest match wins across all configured markers.
            //
            // A match truncates FROM the match onward, so ordinary text before it survives -
            // which is the property that makes this a stop sequence rather than a filter.
            size_t cut = std::string::npos;
            for (const auto & stop : stop_sequences) {
                if (stop.empty()) continue;
                if (output.size() < stop.size()) continue;
                const size_t found = output.find(stop, emitted);
                if (found == std::string::npos) continue;
                cut = std::min(cut, found);
            }

            std::string visible;
            if (cut != std::string::npos) {
                // `cut >= emitted` by construction: this cannot underflow, and it cannot
                // reach back into text the reader already has.
                visible = sanitize_utf8(output.substr(emitted, cut - emitted), pending_utf8);
                output.resize(cut);
                emitted = cut;
                stopped = true;
            } else {
                // Withhold only what could still turn out to be the head of a marker.
                const size_t safe_end = output.size() - emitted > hold_back
                    ? output.size() - hold_back
                    : emitted;
                if (safe_end > emitted) {
                    visible = sanitize_utf8(output.substr(emitted, safe_end - emitted), pending_utf8);
                    emitted = safe_end;
                }
            }

            if (!visible.empty()) {
                jstring jtoken = to_jstring(env, visible);
                if (jtoken != nullptr) {
                    jobject unit = nullptr;
                    {
                        g_in_kotlin_callback = true;
                        unit = static_cast<jobject>(
                            env->CallObjectMethod(j_token_callback, on_token.invoke, jtoken));
                        g_in_kotlin_callback = false;
                    }
                    env->DeleteLocalRef(jtoken);
                    if (unit != nullptr) env->DeleteLocalRef(unit);
                }
                if (throw_if_pending(env, "the token callback")) {
                    llama_sampler_free(ctx_sampler);
                    return to_jstring(env, output);
                }
            }

            if (stopped) break;
        }

        llama_sampler_accept(ctx_sampler, id);

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0) {
            // The cache is in an unknown state after a failed decode; drop the prefix so
            // the next turn cannot reuse a partial evaluation.
            g_cached_tokens.clear();
            llama_sampler_free(ctx_sampler);
            set_error("llama_decode failed during generation");
            return to_jstring(env, output);
        }
        // This token is now in the key/value cache, so the next turn may reuse it.
        g_cached_tokens.push_back(id);
        ++position;
        ++generated;
    }

    llama_sampler_free(ctx_sampler);

    const int64_t generation_micros = micros_since(decode_start);

    // PHASE 9: flush the held-back tail.
    //
    // Up to `max_stop_len - 1` bytes were withheld from the callback because they could
    // still have become the start of a marker. If generation ended WITHOUT a match - the
    // model ran out of budget, was cancelled, or emitted EOG - those bytes were ordinary
    // text after all, and the reader must receive them. Without this flush the last
    // characters of every reply would silently disappear.
    if (!stopped && emitted < output.size()) {
        const std::string tail = sanitize_utf8(output.substr(emitted), pending_utf8);
        emitted = output.size();
        if (!tail.empty()) {
            jstring jtoken = to_jstring(env, tail);
            if (jtoken != nullptr) {
                jobject unit = nullptr;
                {
                    g_in_kotlin_callback = true;
                    unit = static_cast<jobject>(
                        env->CallObjectMethod(j_token_callback, on_token.invoke, jtoken));
                    g_in_kotlin_callback = false;
                }
                env->DeleteLocalRef(jtoken);
                if (unit != nullptr) env->DeleteLocalRef(unit);
            }
            if (throw_if_pending(env, "the token callback")) {
                return to_jstring(env, output);
            }
        }
    }

    // Flush any UTF-8 sequence the last token left half-finished, rather than
    // dropping the character on the floor.
    if (!pending_utf8.empty()) {
        std::string carried;
        output.append(sanitize_utf8(pending_utf8, carried));
        pending_utf8.clear();
    }

    if (generated <= 0 && !g_cancel.load()) {
        set_error("generation produced no tokens");
    }

    {
        GenerateStats stats;
        stats.valid = true;
        stats.prompt_micros = prompt_micros;
        stats.tokenize_micros = tokenize_micros;
        // prompt_micros spans tokenize + eval; eval is what is left after tokenization.
        stats.eval_micros = prompt_micros - tokenize_micros;
        stats.generation_micros = generation_micros;
        stats.first_token_micros = first_token_micros;
        stats.prompt_tokens = token_count;
        stats.generated_tokens = generated;
        stats.reused_tokens = reused;
        stats.cache_tokens = cache_tokens;
        stats.reuse_fallback = reuse_fallback;
        stats.context_size = context_size;
        stats.stop_reason = stopped
                                ? "stop_sequence"
                                : (g_cancel.load() ? "cancelled"
                                                   : (generated >= budget ? "length" : "eog"));
        stats.budget = budget;
        stats.requested_max = requested;
        stats.threads = llama_n_threads(ctx);
        stats.threads_batch = llama_n_threads_batch(ctx);
        set_stats(stats);
    }

    return to_jstring(env, output);
}

JNIEXPORT void JNICALL
Java_dev_charaly_app_inference_LlamaNative_stopGeneration(JNIEnv *, jclass, jlong handle) {
    // Deliberately lock-free: this is the one native call that must always be
    // answerable, including from inside a token callback and from the UI thread
    // while a decode holds the session lock.
    g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_dev_charaly_app_inference_LlamaNative_lastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(current_error().c_str());
}

// The real generation path's own timings for the most recent turn, as flat
// [key, value, ...] pairs. An empty array means no turn has been timed yet, which the
// Kotlin side must render as "not measured" rather than as zero.
JNIEXPORT jobjectArray JNICALL
Java_dev_charaly_app_inference_LlamaNative_lastGenerateStats(JNIEnv * env, jclass) {
    const GenerateStats stats = copy_stats();
    std::vector<std::pair<std::string, std::string>> out;
    if (!stats.valid) {
        return string_pairs_to_java(env, out);
    }
    out.emplace_back("prompt_micros", std::to_string(stats.prompt_micros));
    out.emplace_back("tokenize_micros", std::to_string(stats.tokenize_micros));
    out.emplace_back("eval_micros", std::to_string(stats.eval_micros));
    out.emplace_back("generation_micros", std::to_string(stats.generation_micros));
    out.emplace_back("first_token_micros", std::to_string(stats.first_token_micros < 0 ? 0 : stats.first_token_micros));
    out.emplace_back("prompt_tokens", std::to_string(stats.prompt_tokens));
    out.emplace_back("generated_tokens", std::to_string(stats.generated_tokens));
    out.emplace_back("reused_tokens", std::to_string(stats.reused_tokens));
    out.emplace_back("cache_tokens", std::to_string(stats.cache_tokens));
    out.emplace_back("reuse_fallback", stats.reuse_fallback ? "1" : "0");
    out.emplace_back("stop_reason", stats.stop_reason);
    out.emplace_back("budget", std::to_string(stats.budget));
    out.emplace_back("requested_max", std::to_string(stats.requested_max));
    out.emplace_back("threads", std::to_string(stats.threads));
    out.emplace_back("threads_batch", std::to_string(stats.threads_batch));
    out.emplace_back("context_size", std::to_string(stats.context_size));
    return string_pairs_to_java(env, out);
}

// ---------------------------------------------------------------------------
// Benchmarking
//
// Every figure below is read from a steady clock around the real decode loop that
// generate() uses, with the same tokenization, the same chunking and the same sampler.
// Nothing is inferred from a model size or a device class: a run that produced no
// tokens returns an empty array, and the Kotlin side records no measurement rather
// than a zero. That distinction is the entire point of the feature.
// ---------------------------------------------------------------------------

JNIEXPORT jobjectArray JNICALL
Java_dev_charaly_app_inference_LlamaNative_benchmark(
    JNIEnv * env,
    jclass,
    jlong handle,
    jstring j_prompt,
    jint j_max_tokens) {
    if (handle == 0) {
        set_error("no model loaded");
        return empty_string_array(env);
    }

    std::unique_lock<std::mutex> session(g_session_mutex);

    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int context_size = 0;
    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        if (g_model.ctx == nullptr) {
            set_error("no model loaded");
            return empty_string_array(env);
        }
        ctx = g_model.ctx;
        vocab = g_model.vocab;
        context_size = g_model.context_size;
    }

    if (j_prompt == nullptr) {
        set_error("benchmark prompt was null");
        return empty_string_array(env);
    }
    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (prompt_chars == nullptr) {
        set_error("benchmark prompt could not be read");
        return empty_string_array(env);
    }
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    std::vector<std::pair<std::string, std::string>> out;
    g_cancel.store(false);
    clear_error();
    // The benchmark clears and refills the cache with its own fixed prompt, which the
    // cached token vector does not describe. Drop it so a story turn that follows cannot
    // reuse key/value state that belongs to a different prompt.
    g_cached_tokens.clear();

    const int requested = j_max_tokens > 0 ? j_max_tokens : 0;

    // A fixed sampler: greedy, so a run measures compute rather than sampling luck.
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(vocab, context_size, prompt, requested, /* reserve = */ 8, tokens)) {
        return string_pairs_to_java(env, out);
    }
    const int prompt_tokens = static_cast<int>(tokens.size());
    if (prompt_tokens <= 0) {
        set_error("benchmark prompt produced no tokens");
        return string_pairs_to_java(env, out);
    }

    const auto prompt_start = Clock::now();
    std::string prompt_error;
    if (!eval_prompt(ctx, tokens, prompt_error)) {
        set_error(prompt_error == "cancelled" ? "benchmark cancelled" : prompt_error);
        return string_pairs_to_java(env, out);
    }
    const int64_t prompt_micros = micros_since(prompt_start);

    llama_sampler * sampler = build_sampler(/* top_k = */ 1, /* top_p = */ 1.0f, /* min_p = */ 0.0f,
                                           /* temperature = */ 0.0f,
                                           /* repeat_penalty = */ 1.0f, /* seed = */ 1u);
    if (sampler == nullptr) {
        set_error("could not build the benchmark sampler chain");
        return string_pairs_to_java(env, out);
    }

    const int budget = std::min(requested, context_size - prompt_tokens - 8);
    int position = prompt_tokens;
    int generated = 0;
    int64_t first_token_micros = -1;
    const auto decode_start = Clock::now();

    if (budget <= 0) {
        llama_sampler_free(sampler);
        set_error("benchmark has no room to generate: the prompt is too long");
        return string_pairs_to_java(env, out);
    }

    while (generated < budget && !g_cancel.load()) {
        llama_token id = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        // First-token latency is recorded *before* this token is decoded, which is the
        // number a reader actually feels: everything from tapping send to seeing a word.
        if (first_token_micros < 0) first_token_micros = micros_since(decode_start);

        llama_sampler_accept(sampler, id);
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0) {
            llama_sampler_free(sampler);
            set_error("llama_decode failed during the benchmark");
            return string_pairs_to_java(env, out);
        }
        ++position;
        ++generated;
    }

    const int64_t decode_micros = micros_since(decode_start);
    llama_sampler_free(sampler);

    // No tokens means no measurement. An empty array, so the caller cannot store a
    // benchmark that would then be quoted as a real speed.
    if (generated <= 0) {
        if (current_error().empty()) set_error("the benchmark produced no tokens");
        return string_pairs_to_java(env, out);
    }

    out.emplace_back("tokens", std::to_string(generated));
    out.emplace_back("prompt_tokens", std::to_string(prompt_tokens));
    out.emplace_back("decode_micros", std::to_string(decode_micros));
    out.emplace_back("prompt_micros", std::to_string(prompt_micros));
    out.emplace_back(
        "first_token_micros",
        std::to_string(first_token_micros < 0 ? 0 : first_token_micros));
    out.emplace_back("total_micros", std::to_string(prompt_micros + decode_micros));
    out.emplace_back("context_size", std::to_string(context_size));
    return string_pairs_to_java(env, out);
}

// ---------------------------------------------------------------------------
// Backend reporting
//
// Read from ggml's device registry rather than hardcoded, so the answer cannot drift
// away from the CMake flags this build actually used. The UI is required to show only
// what comes back from here: a build with no GPU backend must not be able to claim one,
// and a registry that reported nothing must not be backfilled with a "CPU" that ggml
// never said was there. A CPU-only build reports CPU because ggml genuinely registers
// a CPU device - not because this function invented one.
// ---------------------------------------------------------------------------

JNIEXPORT jobjectArray JNICALL
Java_dev_charaly_app_inference_LlamaNative_availableBackends(JNIEnv * env, jclass) {
    std::call_once(g_backend_once, []() { llama_backend_init(); });

    std::vector<std::string> names;
    const size_t dev_count = ggml_backend_dev_count();
    names.reserve(dev_count);

    // Device types rather than registry names: "Vulkan" as a registry name can exist on a
    // build with no Vulkan *device*, which is precisely the claim that must not be made.
    //
    // The enum tag is required: ggml declares a *function* called `ggml_backend_dev_type`, so
    // the bare name resolves to the function rather than the type in this scope.
    const auto device_name = [](enum ggml_backend_dev_type type) -> const char * {
        switch (type) {
            case GGML_BACKEND_DEVICE_TYPE_GPU:    return "GPU";
            case GGML_BACKEND_DEVICE_TYPE_CPU:    return "CPU";
            case GGML_BACKEND_DEVICE_TYPE_ACCEL:  return "ACCELERATOR";
            default:                              return "OTHER";
        }
    };

    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        const char * name = ggml_backend_dev_name(dev);
        const char * type = device_name(ggml_backend_dev_type(dev));
        names.emplace_back(std::string(type) + ":" + (name != nullptr ? name : "unknown"));
    }

    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(names.size()), string_class, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < names.size(); ++i) {
        jstring entry = env->NewStringUTF(names[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), entry);
        env->DeleteLocalRef(entry);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Chat templates
//
// An instruct model only stops at its end-of-turn token when the prompt is in the
// format it was trained on. A plain "[user] ... [assistant]" block is a foreign format:
// the model rambles to the token cap and never emits EOG. Applying the model's own
// template is what makes "the model ended its turn" a real event rather than a guess.
//
// The messages arrive as a flat [role0, content0, role1, content1, ...] array, the same
// flat contract the rest of this boundary speaks. `add_assistant` is true: the prompt ends
// with the opening of the assistant's turn, ready for the model to continue.
// ---------------------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_dev_charaly_app_inference_LlamaNative_applyChatTemplate(
    JNIEnv * env,
    jclass,
    jlong handle,
    jobjectArray j_messages) {
    if (handle == 0) {
        set_error("no model loaded");
        return to_jstring(env, std::string());
    }
    llama_model * model = nullptr;
    {
        std::lock_guard<std::mutex> state(g_state_mutex);
        model = g_model.model;
    }
    if (model == nullptr) {
        set_error("no model loaded");
        return to_jstring(env, std::string());
    }

    const jsize count = j_messages != nullptr ? env->GetArrayLength(j_messages) : 0;
    if (count == 0 || count % 2 != 0) {
        set_error("chat messages must be a flat array of role/content pairs");
        return to_jstring(env, std::string());
    }
    const size_t n = static_cast<size_t>(count / 2);

    std::vector<llama_chat_message> msgs(n);
    std::vector<std::string> roles(n);
    std::vector<std::string> contents(n);
    size_t total_chars = 0;
    for (size_t i = 0; i < n; ++i) {
        auto j_role = static_cast<jstring>(env->GetObjectArrayElement(j_messages, static_cast<jsize>(i * 2)));
        auto j_content = static_cast<jstring>(env->GetObjectArrayElement(j_messages, static_cast<jsize>(i * 2 + 1)));
        if (j_role == nullptr || j_content == nullptr) {
            set_error("chat message has a null role or content");
            return to_jstring(env, std::string());
        }
        const char * role = env->GetStringUTFChars(j_role, nullptr);
        const char * content = env->GetStringUTFChars(j_content, nullptr);
        roles[i] = role != nullptr ? role : "";
        contents[i] = content != nullptr ? content : "";
        if (role != nullptr) env->ReleaseStringUTFChars(j_role, role);
        if (content != nullptr) env->ReleaseStringUTFChars(j_content, content);
        env->DeleteLocalRef(j_role);
        env->DeleteLocalRef(j_content);
        msgs[i].role = roles[i].c_str();
        msgs[i].content = contents[i].c_str();
        total_chars += contents[i].size() + roles[i].size();
    }

    // The recommended buffer is twice the message text; if the template needs more, grow
    // and retry rather than truncating a prompt the model will read as malformed.
    size_t cap = total_chars * 2 + 4096;
    for (int attempt = 0; attempt < 4; ++attempt) {
        std::vector<char> buf(cap);
        const int32_t written = llama_chat_apply_template(
            nullptr, msgs.data(), n, /* add_assistant = */ true,
            buf.data(), static_cast<int32_t>(buf.size()));
        if (written < 0) {
            set_error("this model has no chat template, or the template could not be applied");
            return to_jstring(env, std::string());
        }
        if (static_cast<size_t>(written) <= buf.size()) {
            return to_jstring(env, std::string(buf.data(), static_cast<size_t>(written)));
        }
        cap = static_cast<size_t>(written) * 2;
    }
    set_error("the chat template output did not fit");
    return to_jstring(env, std::string());
}

} // extern "C"