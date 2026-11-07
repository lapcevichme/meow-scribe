// Тонкая JNI-обёртка над llama.cpp: загрузка модели, chat-шаблон, жадная генерация.
// Строки ходят через byte[] в UTF-8: NewStringUTF ждёт «modified UTF-8» и ломается на 4-байтных символах.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "MeowLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_batch = 512;
};

std::once_flag backend_once;

void log_callback(ggml_log_level level, const char *text, void *) {
    // Info от llama.cpp очень многословный — оставляем предупреждения и ошибки.
    if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
}

void throw_java(JNIEnv *env, const char *cls, const std::string &msg) {
    jclass ex = env->FindClass(cls);
    if (ex) env->ThrowNew(ex, msg.c_str());
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

std::vector<llama_token> tokenize(const Engine *e, const std::string &text, bool add_special) {
    int n = -llama_tokenize(e->vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) {
        llama_tokenize(e->vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), n, add_special, true);
    }
    return tokens;
}

std::string piece(const Engine *e, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(e->vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(e->vocab, token, big.data(), -n, 0, false);
        return big.substr(0, static_cast<size_t>(std::max(n, 0)));
    }
    return std::string(buf, static_cast<size_t>(n));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint n_ctx,
                                                            jint n_threads) {
    std::call_once(backend_once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;  // веса не копируются в RAM процесса, страницы подгружаются по требованию
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        throw_java(env, "java/lang/IllegalStateException", "Не удалось загрузить GGUF-модель");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        throw_java(env, "java/lang/IllegalStateException", "Не удалось создать контекст llama.cpp");
        return 0;
    }

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    // Жадный выбор: детерминированно и без «творчества» — LLM не должна менять слова транскрипта.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->sampler = sampler;
    e->vocab = llama_model_get_vocab(model);
    e->n_batch = static_cast<int>(cparams.n_batch);
    LOGI("model loaded: ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeFree(JNIEnv *, jclass, jlong ptr) {
    auto *e = reinterpret_cast<Engine *>(ptr);
    if (!e) return;
    llama_sampler_free(e->sampler);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT jint JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeContextSize(JNIEnv *, jclass, jlong ptr) {
    return static_cast<jint>(llama_n_ctx(reinterpret_cast<Engine *>(ptr)->ctx));
}

JNIEXPORT jint JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeCountTokens(JNIEnv *env, jclass, jlong ptr, jbyteArray text) {
    return static_cast<jint>(tokenize(reinterpret_cast<Engine *>(ptr), to_string(env, text), false).size());
}

JNIEXPORT jbyteArray JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeApplyChatTemplate(JNIEnv *env, jclass, jlong ptr,
                                                                         jobjectArray roles, jobjectArray contents,
                                                                         jboolean add_assistant) {
    auto *e = reinterpret_cast<Engine *>(ptr);
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> role_s, content_s;
    role_s.reserve(n);
    content_s.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        role_s.push_back(to_string(env, static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i))));
        content_s.push_back(to_string(env, static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i))));
    }
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) msgs[i] = {role_s[i].c_str(), content_s[i].c_str()};

    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (!tmpl) tmpl = "chatml";
    size_t total = 0;
    for (auto &c : content_s) total += c.size();
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(),
                                            static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(len));
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), len);
    }
    if (len < 0) {
        throw_java(env, "java/lang/IllegalStateException", "Шаблон чата модели не поддерживается");
        return nullptr;
    }
    return to_bytes(env, std::string(buf.data(), static_cast<size_t>(len)));
}

/**
 * Генерация с нуля (KV-кэш очищается). cancel — объект с методом boolean isCancelled(), опрашивается на каждом токене.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeGenerate(JNIEnv *env, jclass, jlong ptr, jbyteArray jprompt,
                                                                jint max_tokens, jobjectArray jstops, jobject cancel) {
    auto *e = reinterpret_cast<Engine *>(ptr);
    const std::string prompt = to_string(env, jprompt);

    std::vector<std::string> stops;
    if (jstops) {
        const jsize ns = env->GetArrayLength(jstops);
        for (jsize i = 0; i < ns; ++i) {
            stops.push_back(to_string(env, static_cast<jbyteArray>(env->GetObjectArrayElement(jstops, i))));
        }
    }
    jmethodID is_cancelled = nullptr;
    if (cancel) is_cancelled = env->GetMethodID(env->GetObjectClass(cancel), "isCancelled", "()Z");

    llama_memory_clear(llama_get_memory(e->ctx), true);
    llama_sampler_reset(e->sampler);

    std::vector<llama_token> tokens = tokenize(e, prompt, true);
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));
    if (static_cast<int>(tokens.size()) + max_tokens > n_ctx) {
        throw_java(env, "java/lang/IllegalArgumentException",
                   "Промпт (" + std::to_string(tokens.size()) + " ток.) + ответ не влезают в контекст " +
                       std::to_string(n_ctx));
        return nullptr;
    }

    for (size_t i = 0; i < tokens.size(); i += static_cast<size_t>(e->n_batch)) {
        const int32_t n = static_cast<int32_t>(std::min(tokens.size() - i, static_cast<size_t>(e->n_batch)));
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            throw_java(env, "java/lang/IllegalStateException", "llama_decode: ошибка на промпте");
            return nullptr;
        }
    }

    std::string out;
    for (int i = 0; i < max_tokens; ++i) {
        if (is_cancelled && env->CallBooleanMethod(cancel, is_cancelled)) break;

        llama_token tok = llama_sampler_sample(e->sampler, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        out += piece(e, tok);

        bool stopped = false;
        for (const auto &s : stops) {
            if (!s.empty() && out.size() >= s.size() && out.compare(out.size() - s.size(), s.size(), s) == 0) {
                out.resize(out.size() - s.size());
                stopped = true;
                break;
            }
        }
        if (stopped) break;

        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGW("llama_decode failed at token %d", i);
            break;
        }
    }
    return to_bytes(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_lapcevichme_meowscribe_llama_LlamaModel_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

}  // extern "C"
