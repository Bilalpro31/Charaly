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

LoadedModel g_model;
std::atomic<bool> g_cancel{false};
std::string g_last_error;

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
bool eval_prompt(llama_context * ctx, std::vector<llama_token> & tokens, std::string & error) {
    llama_memory_clear(llama_get_memory(ctx), true);
    const int chunk = 512;
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(chunk)) {
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

// The sampler chain used by both paths, so a benchmark measures the same sampling work
// a story turn does. Null on allocation failure, which the callers check.
llama_sampler * build_sampler(
    int top_k,
    float top_p,
    float temperature,
    float repeat_penalty,
    uint32_t seed) {
    auto * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (chain == nullptr) return nullptr;
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
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
    // The Kotlin contract (LocalLlamaInferenceEngine): threads == 0 means "the
    // engine picks". The default from llama_context_default_params() IS that
    // choice - the old `j_threads > 0 ? j_threads : 4` silently overrode it
    // with a hard-coded 4, disagreeing with the documented contract and with
    // whatever llama.cpp's default becomes in future checkouts.
    if (j_threads > 0) ctx_params.n_threads = j_threads;
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
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(vocab, context_size, prompt, requested, reserve, tokens)) {
        return to_jstring(env, std::string());
    }
    if (tokens.empty()) {
        set_error("prompt produced no tokens");
        return to_jstring(env, std::string());
    }
    const int token_count = static_cast<int>(tokens.size());

    std::string prompt_error;
    if (!eval_prompt(ctx, tokens, prompt_error)) {
        set_error(prompt_error == "cancelled" ? "generation cancelled" : prompt_error);
        return to_jstring(env, std::string());
    }

    // 3. Sampler: temperature / top-p / top-k plus the repetition penalty.
    llama_sampler * ctx_sampler = build_sampler(
        j_top_k, j_top_p, j_temperature, j_repeat_penalty, static_cast<uint32_t>(j_seed));
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
    int position = token_count;
    int generated = 0;
    bool stopped = false;

    // llama_token_to_piece does not write a terminator, so the buffer is owned by a
    // vector sized to the token and never handed to NewStringUTF as raw stack memory.
    std::vector<char> piece;

    while (generated < budget && !g_cancel.load()) {
        llama_token id = llama_sampler_sample(ctx_sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

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

            // Stop sequences are matched against the accumulated text, and the
            // matched run is removed: a reply that ends with the user's own stop
            // string is not the same reply as one that does not.
            size_t cut = std::string::npos;
            for (const auto & stop : stop_sequences) {
                if (stop.empty() || output.size() < stop.size()) continue;
                if (output.compare(output.size() - stop.size(), stop.size(), stop) == 0) {
                    cut = std::min(cut, output.size() - stop.size());
                }
            }

            std::string visible;
            if (cut != std::string::npos) {
                visible = sanitize_utf8(output.substr(before, cut - before), pending_utf8);
                output.resize(cut);
                stopped = true;
            } else {
                visible = sanitize_utf8(piece.data(), pending_utf8);
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
            llama_sampler_free(ctx_sampler);
            set_error("llama_decode failed during generation");
            return to_jstring(env, output);
        }
        ++position;
        ++generated;
    }

    llama_sampler_free(ctx_sampler);

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

    llama_sampler * sampler = build_sampler(/* top_k = */ 1, /* top_p = */ 1.0f, /* temperature = */ 0.0f,
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

} // extern "C"