// Минимальная JNI-обёртка над llama.cpp: загрузка GGUF и жадная/температурная генерация по готовому промпту.
// Kotlin-сторона: com.lapcevichme.meowscribe.data.llm.LlamaNative
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "MeowLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

std::once_flag g_backend_once;

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string to_std(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java-строки приходят как UTF-16 → берём байты через String.getBytes("UTF-8"), чтобы не ломать 4-байтовые символы.
std::string to_utf8(JNIEnv *env, jstring s) {
    jclass cls = env->GetObjectClass(s);
    jmethodID mid = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, mid, enc);
    jsize n = env->GetArrayLength(bytes);
    std::string out(n, '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lapcevichme_meowscribe_data_llm_LlamaNative_nativeLoad(
        JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
    });
    std::string path = to_std(env, jpath);

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("failed to load model %s", path.c_str());
        return 0;
    }
    auto cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }
    auto *h = new Handle{model, ctx, llama_model_get_vocab(model)};
    LOGI("loaded %s, n_ctx=%u, threads=%d", path.c_str(), llama_n_ctx(ctx), n_threads);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lapcevichme_meowscribe_data_llm_LlamaNative_nativeCountTokens(
        JNIEnv *env, jobject, jlong ptr, jstring jtext) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    std::string text = to_utf8(env, jtext);
    int n = -llama_tokenize(h->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    return n;
}

/**
 * Генерирует продолжение промпта (промпт уже размечен чат-шаблоном, спецтокены парсятся).
 * callback.onToken(generated: Int): Boolean — вызывается после каждого токена; false = отмена.
 * Возвращает UTF-8 байты ответа или null при ошибке.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lapcevichme_meowscribe_data_llm_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject, jlong ptr, jstring jprompt, jint max_tokens, jfloat temperature,
        jobject callback) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    std::string prompt = to_utf8(env, jprompt);

    jmethodID on_token = nullptr;
    if (callback) {
        jclass cb_cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cb_cls, "onToken", "(I)Z");
        env->DeleteLocalRef(cb_cls);
    }

    int n_prompt = -llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        LOGE("tokenize failed");
        return nullptr;
    }
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if (n_prompt + max_tokens > n_ctx) {
        LOGE("prompt too long: %d + %d > %d", n_prompt, max_tokens, n_ctx);
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(h->ctx), true);

    const int n_batch = (int) llama_n_batch(h->ctx);
    for (int i = 0; i < n_prompt; i += n_batch) {
        int n = std::min(n_batch, n_prompt - i);
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            LOGE("decode prompt failed at %d", i);
            return nullptr;
        }
        if (on_token && !env->CallBooleanMethod(callback, on_token, (jint) 0)) return nullptr;
    }

    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(h->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    }

    std::string out;
    char buf[256];
    bool ok = true;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int n = llama_token_to_piece(h->vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        if (on_token && !env->CallBooleanMethod(callback, on_token, (jint) (i + 1))) {
            ok = false;
            break;
        }
        if (llama_decode(h->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGE("decode failed at token %d", i);
            break;
        }
    }
    llama_sampler_free(smpl);
    if (!ok) return nullptr;

    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lapcevichme_meowscribe_data_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (!h) return;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
