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
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

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

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_charaly_app_inference_LlamaNative_nativeAvailable(JNIEnv *, jclass) {
    return llama_backend_is_initialized() || true ? JNI_TRUE : JNI_FALSE;
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

        // 1. Tokenize the prompt.
        const int max_prompt_tokens = g_model.context_size - j_max_tokens - 8;
        if (max_prompt_tokens <= 0) {
            set_error("context window too small for maxTokens");
            return env->NewStringUTF("");
        }

        std::vector<llama_token> tokens(static_cast<size_t>(max_prompt_tokens));
        const int token_count = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            max_prompt_tokens,
            /* add_special = */ true,
            /* parse_special = */ false);
        if (token_count < 0) {
            set_error("failed to tokenize prompt");
            return env->NewStringUTF("");
        }
        tokens.resize(static_cast<size_t>(token_count));

        // 2. Clear the KV cache and evaluate the prompt.
        llama_memory_clear(llama_get_memory(ctx), true);

        const int prompt_chunk = static_cast<int>(std::min<size_t>(tokens.size(), 512));
        for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(prompt_chunk)) {
            const int batch_n = static_cast<int>(std::min<size_t>(prompt_chunk, tokens.size() - offset));
            llama_batch batch = llama_batch_get_one(tokens.data() + offset, batch_n);
            if (llama_decode(ctx, batch) != 0) {
                set_error("llama_decode failed while processing the prompt");
                return env->NewStringUTF("");
            }
            if (g_cancel.load()) {
                return env->NewStringUTF(output.c_str());
            }
        }

        // 3. Sampler: temperature / top-p / top-k plus the repetition penalty.
        auto * ctx_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(ctx_sampler, llama_sampler_init_top_k(j_top_k));
        llama_sampler_chain_add(ctx_sampler, llama_sampler_init_top_p(j_top_p, 1));
        if (j_temperature > 0.0f) {
            llama_sampler_chain_add(ctx_sampler, llama_sampler_init_temp(j_temperature));
        }
        llama_sampler_chain_add(ctx_sampler, llama_sampler_init_penalty(64, j_repeat_penalty, j_repeat_penalty));
        llama_sampler_chain_add(ctx_sampler, llama_sampler_init_dist(j_seed));

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

} // extern "C"
