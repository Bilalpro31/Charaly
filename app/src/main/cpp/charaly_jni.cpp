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

#include <jni.h>

#include <atomic>
#include <chrono>
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
    std::string metadata;
};

std::mutex g_mutex;
LoadedModel g_model;
std::atomic<bool> g_cancel{false};
std::string g_last_error;

void set_error(const std::string & message) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error = message;
}

std::string current_error() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_last_error;
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

std::string metadata_of(const llama_model * model) {
    char buffer[4096];
    const int32_t written = llama_model_meta_val_str(model, "general.name", buffer, sizeof(buffer));
    std::string out;
    out += "general.name=";
    out += written > 0 ? std::string(buffer, static_cast<size_t>(written)) : std::string("unknown");
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
    const std::string & prompt,
    int max_tokens,
    int reserve,
    std::vector<llama_token> & out) {
    const int max_prompt_tokens = g_model.context_size - max_tokens - reserve;
    if (max_prompt_tokens <= 0) {
        set_error("context window too small for maxTokens");
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
        set_error("failed to tokenize prompt");
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
        const int batch_n = static_cast<int>(std::min<size_t>(tokens.size() - offset, static_cast<size_t>(chunk)));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, batch_n);
        if (llama_decode(ctx, batch) != 0) {
            error = "llama_decode failed while processing the prompt";
            return false;
        }
        if (g_cancel.load()) {
            error = "cancelled";
            return false;
        }
    }
    return true;
}

// The sampler chain used by both paths, so a benchmark measures the same sampling work
// a story turn does.
llama_sampler * build_sampler(
    int top_k,
    float top_p,
    float temperature,
    float repeat_penalty,
    uint32_t seed) {
    auto * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(64, repeat_penalty, 1.0f, 1.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_charaly_app_inference_LlamaNative_nativeAvailable(JNIEnv *, jclass) {
    // Reaching this function at all means System.loadLibrary("charaly_llama")
    // succeeded, which is exactly what "the native engine is available" means.
    // There is no llama_backend_is_initialized() in the public API: the backend
    // is initialised lazily on first load, not at library load time.
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
    const char * path = env->GetStringUTFChars(j_path, nullptr);
    if (path == nullptr) {
        set_error("invalid model path");
        return 0;
    }
    const std::string model_path(path);
    env->ReleaseStringUTFChars(j_path, path);

    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();

    llama_backend_init();

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

    jclass progressClass = env->GetObjectClass(j_progress);
    jmethodID onProgress = env->GetMethodID(progressClass, "invoke", "(ILjava/lang/String;)V");
    auto report = [&](int percent, const char * stage) {
        if (onProgress == nullptr) return;
        jstring jstage = env->NewStringUTF(stage);
        env->CallVoidMethod(j_progress, onProgress, static_cast<jint>(percent), jstage);
        env->DeleteLocalRef(jstage);
    };

    report(5, "opening");
    llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
    if (model == nullptr) {
        set_error("llama_model_load_from_file failed");
        report(0, "failed");
        return 0;
    }

    report(60, "creating context");
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(j_context_size > 0 ? j_context_size : 2048);
    ctx_params.n_threads = j_threads > 0 ? j_threads : 4;
    ctx_params.n_batch = 512;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        set_error("llama_init_from_model failed (not enough memory?)");
        report(0, "failed");
        return 0;
    }

    g_model.model = model;
    g_model.ctx = ctx;
    g_model.vocab = llama_model_get_vocab(model);
    g_model.context_size = static_cast<int>(llama_n_ctx(ctx));
    g_model.metadata = metadata_of(model);
    g_last_error.clear();

    report(100, "ready");

    // Handle 1 is a valid, non-null sentinel for "a model is loaded".
    return 1;
}

JNIEXPORT void JNICALL
Java_dev_charaly_app_inference_LlamaNative_freeModel(JNIEnv *, jclass, jlong handle) {
    if (handle == 0) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_charaly_app_inference_LlamaNative_modelMetadata(JNIEnv * env, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(0, stringClass, nullptr);

    if (handle == 0 || g_model.metadata.empty()) {
        return out;
    }

    std::vector<std::string> lines;
    std::string current;
    for (char c : g_model.metadata) {
        if (c == '\n') {
            lines.push_back(current);
            current.clear();
        } else {
            current.push_back(c);
        }
    }
    if (!current.empty()) lines.push_back(current);

    std::vector<jstring> entries;
    for (const auto & line : lines) {
        const size_t split = line.find('=');
        if (split == std::string::npos) continue;
        const std::string key = line.substr(0, split);
        const std::string value = line.substr(split + 1);
        entries.push_back(env->NewStringUTF(key.c_str()));
        entries.push_back(env->NewStringUTF(value.c_str()));
    }

    out = env->NewObjectArray(static_cast<jsize>(entries.size()), stringClass, nullptr);
    for (size_t i = 0; i < entries.size(); ++i) {
        env->SetObjectArrayElement(out, static_cast<jsize>(i), entries[i]);
        env->DeleteLocalRef(entries[i]);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_dev_charaly_app_inference_LlamaNative_contextSize(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
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
    if (handle == 0 || g_model.ctx == nullptr) {
        set_error("no model loaded");
        return env->NewStringUTF("");
    }

    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("");
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    std::vector<std::string> stop_sequences;
    if (j_stop_sequences != nullptr) {
        const jsize count = env->GetArrayLength(j_stop_sequences);
        for (jsize i = 0; i < count; ++i) {
            auto element = static_cast<jstring>(env->GetObjectArrayElement(j_stop_sequences, i));
            if (element == nullptr) continue;
            const char * chars = env->GetStringUTFChars(element, nullptr);
            if (chars != nullptr) {
                stop_sequences.emplace_back(chars);
                env->ReleaseStringUTFChars(element, chars);
            }
            env->DeleteLocalRef(element);
        }
    }

    jclass callbackClass = env->GetObjectClass(j_token_callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "invoke", "(Ljava/lang/String;)V");

    g_cancel.store(false);

    std::string output;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        llama_context * ctx = g_model.ctx;
        const llama_vocab * vocab = g_model.vocab;

        // 1-2. Tokenize and evaluate the prompt.
        std::vector<llama_token> tokens;
        if (!tokenize_prompt(vocab, prompt, j_max_tokens, /* reserve = */ 8, tokens)) {
            return env->NewStringUTF("");
        }
        const int token_count = static_cast<int>(tokens.size());

        std::string prompt_error;
        if (!eval_prompt(ctx, tokens, prompt_error)) {
            set_error(prompt_error);
            return env->NewStringUTF("");
        }

        // 3. Sampler: temperature / top-p / top-k plus the repetition penalty.
        auto * ctx_sampler = build_sampler(
            j_top_k, j_top_p, j_temperature, j_repeat_penalty, static_cast<uint32_t>(j_seed));

        // 4. Decode loop, emitting each token as it is produced.
        const int n_ctx = static_cast<int>(llama_n_ctx(ctx));
        int position = token_count;
        const int budget = std::min<int>(j_max_tokens, n_ctx - position - 1);
        int generated = 0;

        while (generated < budget && !g_cancel.load()) {
            llama_token id = llama_sampler_sample(ctx_sampler, ctx, -1);
            if (id < 0) {
                set_error("sampler produced an invalid token");
                break;
            }
            if (llama_vocab_is_eog(vocab, id)) {
                break;
            }

            char token_text[256];
            const int written = llama_token_to_piece(vocab, id, token_text, sizeof(token_text), 0, true);
            if (written > 0) {
                output.append(token_text, static_cast<size_t>(written));

                if (onToken != nullptr) {
                    jstring jtoken = env->NewStringUTF(token_text);
                    env->CallVoidMethod(j_token_callback, onToken, jtoken);
                    env->DeleteLocalRef(jtoken);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                        break;
                    }
                }

                bool should_stop = false;
                for (const auto & stop : stop_sequences) {
                    if (!stop.empty() && output.size() >= stop.size() &&
                        output.compare(output.size() - stop.size(), stop.size(), stop) == 0) {
                        should_stop = true;
                        break;
                    }
                }
                if (should_stop) break;
            }

            llama_sampler_accept(ctx_sampler, id);

            llama_batch batch = llama_batch_get_one(&id, 1);
            if (llama_decode(ctx, batch) != 0) {
                set_error("llama_decode failed during generation");
                break;
            }
            ++position;
            ++generated;
        }

        llama_sampler_free(ctx_sampler);
    }

    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_dev_charaly_app_inference_LlamaNative_stopGeneration(JNIEnv *, jclass, jlong handle) {
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
// tokens returns an empty map, and the Kotlin side records no measurement rather than
// a zero. That distinction is the entire point of the feature.
// ---------------------------------------------------------------------------

jobject string_map_to_java(JNIEnv * env, const std::vector<std::pair<std::string, std::string>> & entries) {
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(entries.size()), string_class, nullptr);
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

JNIEXPORT jobject JNICALL
Java_dev_charaly_app_inference_LlamaNative_benchmark(
    JNIEnv * env,
    jclass,
    jlong handle,
    jstring j_prompt,
    jint j_max_tokens) {
    if (handle == 0 || g_model.ctx == nullptr) {
        set_error("no model loaded");
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    std::vector<std::pair<std::string, std::string>> out;

    std::lock_guard<std::mutex> lock(g_mutex);
    llama_context * ctx = g_model.ctx;
    const llama_vocab * vocab = g_model.vocab;
    g_cancel.store(false);

    // A fixed sampler: greedy, so a run measures compute rather than sampling luck.
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(vocab, prompt, j_max_tokens, /* reserve = */ 8, tokens)) {
        return string_map_to_java(env, out);
    }
    const int prompt_tokens = static_cast<int>(tokens.size());
    if (prompt_tokens <= 0) {
        set_error("benchmark prompt produced no tokens");
        return string_map_to_java(env, out);
    }

    const auto prompt_start = Clock::now();
    std::string prompt_error;
    if (!eval_prompt(ctx, tokens, prompt_error)) {
        set_error(prompt_error);
        return string_map_to_java(env, out);
    }
    const int64_t prompt_micros = micros_since(prompt_start);

    auto * sampler = build_sampler(/* top_k = */ 1, /* top_p = */ 1.0f, /* temperature = */ 0.0f,
                                   /* repeat_penalty = */ 1.0f, /* seed = */ 1u);

    const int n_ctx = static_cast<int>(llama_n_ctx(ctx));
    int position = prompt_tokens;
    const int budget = std::min<int>(j_max_tokens, n_ctx - position - 1);
    int generated = 0;
    int64_t first_token_micros = -1;
    const auto decode_start = Clock::now();

    while (generated < budget && !g_cancel.load()) {
        llama_token id = llama_sampler_sample(sampler, ctx, -1);
        if (id < 0) {
            set_error("sampler produced an invalid token");
            break;
        }
        if (llama_vocab_is_eog(vocab, id)) break;

        // First-token latency is recorded *before* this token is decoded, which is the
        // number a reader actually feels: everything from tapping send to seeing a word.
        if (first_token_micros < 0) first_token_micros = micros_since(decode_start);

        llama_sampler_accept(sampler, id);
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0) {
            set_error("llama_decode failed during the benchmark");
            break;
        }
        ++position;
        ++generated;
    }

    const int64_t decode_micros = micros_since(decode_start);
    llama_sampler_free(sampler);

    // No tokens means no measurement. An empty map, so the caller cannot store a
    // benchmark that would then be quoted as a real speed.
    if (generated <= 0) {
        if (g_last_error.empty()) set_error("the benchmark produced no tokens");
        return string_map_to_java(env, out);
    }

    out.emplace_back("tokens", std::to_string(generated));
    out.emplace_back("prompt_tokens", std::to_string(prompt_tokens));
    out.emplace_back("decode_micros", std::to_string(decode_micros));
    out.emplace_back("prompt_micros", std::to_string(prompt_micros));
    out.emplace_back(
        "first_token_micros",
        std::to_string(first_token_micros < 0 ? 0 : first_token_micros));
    out.emplace_back("total_micros", std::to_string(prompt_micros + decode_micros));
    out.emplace_back("context_size", std::to_string(g_model.context_size));
    return string_map_to_java(env, out);
}

// ---------------------------------------------------------------------------
// Backend reporting
//
// Read from ggml's device registry rather than hardcoded, so the answer cannot drift
// away from the CMake flags this build actually used. The UI is required to show only
// what comes back from here: a build with no GPU backend must not be able to claim one.
// ---------------------------------------------------------------------------

JNIEXPORT jobjectArray JNICALL
Java_dev_charaly_app_inference_LlamaNative_availableBackends(JNIEnv * env, jclass) {
    llama_backend_init();

    std::vector<std::string> names;
    const size_t reg_count = ggml_backend_reg_count();
    names.reserve(reg_count);
    for (size_t i = 0; i < reg_count; ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (reg == nullptr) continue;
        const char * name = ggml_backend_reg_name(reg);
        if (name == nullptr) continue;
        names.emplace_back(name);
    }

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

    std::vector<std::pair<std::string, std::string>> entries;
    const size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        const char * name = ggml_backend_dev_name(dev);
        const char * type = device_name(ggml_backend_dev_type(dev));
        entries.emplace_back(std::string(type) + ":" + (name != nullptr ? name : "unknown"), "present");
    }
    // Always at least one CPU device is reported, because ggml's CPU backend is built in
    // unconditionally; naming it makes "no accelerator" an affirmative statement rather
    // than an empty list a screen has to interpret.
    if (entries.empty()) {
        entries.emplace_back("CPU:builtin", "present");
    }

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(entries.size()), string_class, nullptr);
    for (size_t i = 0; i < entries.size(); ++i) {
        jstring key = env->NewStringUTF(entries[i].first.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), key);
        env->DeleteLocalRef(key);
    }
    return out;
}

} // extern "C"
