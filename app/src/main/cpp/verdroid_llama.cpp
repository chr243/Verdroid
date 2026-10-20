// Verdroid on-device LLM JNI wrapper around llama.cpp (CPU only).
// One loaded model at a time per handle. Chat requests are OpenAI-style JSON (messages + tools);
// the reply is JSON: {"content","reasoning","tool_calls":[{"id","name","arguments"}],"finish","prompt_tokens","completion_tokens"}
// or {"error": "..."}. Uses common_chat templates (Jinja from the GGUF) + their tool-call grammar and parser.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "json.h"
#ifndef VERDROID_HAS_VULKAN
#define VERDROID_HAS_VULKAN 0
#endif
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include <chrono>
#include <cmath>
#include <cstdlib>
#if VERDROID_HAS_VULKAN
#include "ggml-vulkan.h"
#endif

#define TAG "VerdroidLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_chat_templates_ptr tmpls;
    llama_tokens cached;      // tokens currently in the KV cache (seq 0)
    int gpu_layers = 0;       // requested offload (0 = CPU)
    std::string backend = "CPU";
    int n_batch = 512;
    std::atomic<bool> cancel{false};
    std::mutex mu;
};

std::string g_last_error;
std::once_flag g_backend_once;
bool g_vk_enabled = false;  // Vulkan registered in this process (decided once, before the first backend init)

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

// Must run before anything touches the ggml backend registry. CPU-only processes never create a Vulkan instance.
void init_backend(bool want_vulkan) {
    std::call_once(g_backend_once, [want_vulkan] {
        llama_log_set(log_cb, nullptr);
#if VERDROID_HAS_VULKAN
        if (!want_vulkan) setenv("GGML_DISABLE_VULKAN", "1", 1);
        g_vk_enabled = want_vulkan;
#else
        (void) want_vulkan;
#endif
        llama_backend_init();
    });
}

// First non-CPU device (the Vulkan GPU), or nullptr.
ggml_backend_dev_t gpu_device() {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * d = ggml_backend_dev_get(i);
        auto t = ggml_backend_dev_type(d);
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) return d;
    }
    return nullptr;
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_j(JNIEnv * env, const std::string & s) { return env->NewStringUTF(s.c_str()); }

std::string error_json(const std::string & msg) {
    common_json j = common_json::object();
    j["error"] = msg;
    return j.dump_safe();
}


// Tokenize a rendered prompt: template text may contain special tokens, user input parts never do.
llama_tokens tokenize_prompt(const llama_vocab * vocab, const common_chat_prompt & prompt, bool add_bos) {
    llama_tokens out;
    bool first = true;
    for (const auto & part : prompt.parts) {
        if (part.text.empty()) continue;
        auto t = common_tokenize(vocab, part.text, first && add_bos, !part.is_input);
        out.insert(out.end(), t.begin(), t.end());
        first = false;
    }
    return out;
}

bool decode_range(Engine * e, const llama_tokens & toks, size_t from) {
    common_batch batch(e->ctx);
    for (size_t i = from; i < toks.size(); i += e->n_batch) {
        size_t n = std::min(toks.size() - i, (size_t) e->n_batch);
        batch.clear();
        for (size_t j = 0; j < n; j++) {
            bool last = (i + j == toks.size() - 1);
            batch.add(toks[i + j], (llama_pos) (i + j), 0, last);
        }
        if (e->cancel.load()) return false;
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) return false;
    }
    return true;
}

}  // namespace

Engine * load_impl(const std::string & path, int n_ctx, int n_threads, int gpu_layers) {
    init_backend(gpu_layers != 0);
    Engine * e = nullptr;
    ggml_backend_dev_t gpu = gpu_layers != 0 ? gpu_device() : nullptr;
    if (gpu_layers != 0 && !gpu) { g_last_error = g_vk_enabled ? "No usable Vulkan GPU found" : "GPU is off in this process (restart Verdroid after turning on Use GPU)"; return nullptr; }
    static ggml_backend_dev_t no_devices[1] = { nullptr };
    static ggml_backend_dev_t gpu_devices[2] = { nullptr, nullptr };
    try {
        e = new Engine();
        llama_model_params mp = llama_model_default_params();
        if (gpu) {
            gpu_devices[0] = gpu;
            mp.devices = gpu_devices;
            mp.n_gpu_layers = gpu_layers < 0 ? -1 : gpu_layers;
            mp.split_mode = LLAMA_SPLIT_MODE_NONE;
            e->gpu_layers = gpu_layers;
            e->backend = std::string("Vulkan: ") + ggml_backend_dev_description(gpu);
        } else {
            mp.devices = no_devices;
            mp.n_gpu_layers = 0;
        }
        e->model = llama_model_load_from_file(path.c_str(), mp);
        if (!e->model) { g_last_error = "Could not load model (not a valid GGUF or not enough memory)"; delete e; return nullptr; }
        llama_context_params cp = llama_context_default_params();
        int train = llama_model_n_ctx_train(e->model);
        int nc = n_ctx > 0 ? n_ctx : 4096;
        if (train > 0 && nc > train) nc = train;
        cp.n_ctx = nc;
        cp.n_batch = e->n_batch;
        cp.n_ubatch = e->n_batch;
        cp.n_threads = n_threads > 0 ? n_threads : 4;
        cp.n_threads_batch = cp.n_threads;
        e->ctx = llama_init_from_model(e->model, cp);
        if (!e->ctx) { g_last_error = "Could not create context (not enough memory? try a smaller context size)"; llama_model_free(e->model); delete e; return nullptr; }
        e->tmpls = common_chat_templates_init(e->model, "");
        return e;
    } catch (const std::bad_alloc &) {
        g_last_error = "Out of memory while loading the model";
    } catch (const std::exception & ex) {
        g_last_error = std::string("Load failed: ") + ex.what();
    }
    if (e) { if (e->ctx) llama_free(e->ctx); if (e->model) llama_model_free(e->model); delete e; }
    return nullptr;
}


std::string chat_impl(Engine * e, const std::string & messages_s, const std::string & tools_s, int max_tokens, float temp, bool thinking) {
    if (!e) return error_json("Model not loaded");
    std::lock_guard<std::mutex> lock(e->mu);
    e->cancel = false;
    common_sampler * smpl = nullptr;
    try {
        const llama_vocab * vocab = llama_model_get_vocab(e->model);
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(messages_s));
        if (!tools_s.empty()) {
            auto tj = common_json::parse(tools_s);
            if (tj.is_array() && !tj.empty()) inputs.tools = common_chat_tools_parse_oaicompat(tj);
        }
        inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
        inputs.parallel_tool_calls = false;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        inputs.enable_thinking = thinking;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;

        common_chat_session session(e->tmpls.get(), vocab, inputs);
        bool add_bos = llama_vocab_get_add_bos(vocab);
        llama_tokens prompt = tokenize_prompt(vocab, session.prompt(), add_bos);
        const int n_ctx = (int) llama_n_ctx(e->ctx);
        if ((int) prompt.size() >= n_ctx - 16) {
            e->cached.clear();
            llama_memory_clear(llama_get_memory(e->ctx), true);
            return error_json("Prompt too long for context: " + std::to_string(prompt.size()) + " tokens, context " + std::to_string(n_ctx));
        }

        // Reuse the KV cache for the common prefix (the agent re-sends the whole history every turn).
        size_t n_common = 0;
        while (n_common < e->cached.size() && n_common < prompt.size() && e->cached[n_common] == prompt[n_common]) n_common++;
        if (n_common >= prompt.size()) n_common = prompt.size() - 1;  // always decode at least the last token for logits
        auto * mem = llama_get_memory(e->ctx);
        if (n_common == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) n_common, -1)) {
            llama_memory_clear(mem, true);
            n_common = 0;
        }
        e->cached.assign(prompt.begin(), prompt.begin() + (long) n_common);
        if (!decode_range(e, prompt, n_common)) {
            e->cached.clear();
            llama_memory_clear(mem, true);
            return error_json(e->cancel.load() ? "cancelled" : "Prompt decode failed (out of memory?)");
        }
        e->cached = prompt;

        common_params_sampling sp;
        sp.temp = temp;
        sp.top_k = 40;
        sp.top_p = 0.95f;
        sp.min_p = 0.05f;
        session.apply_sampling(sp);
        smpl = common_sampler_init(e->model, sp);
        if (!smpl) return error_json("Sampler init failed (bad tool grammar?)");

        const auto & stops = session.additional_stops();
        std::string text;
        common_chat_input gen;
        int n_gen = 0;
        std::string finish = "length";
        int limit = max_tokens > 0 ? max_tokens : 1024;
        common_batch batch(e->ctx);
        llama_pos pos = (llama_pos) prompt.size();
        auto t_gen0 = std::chrono::steady_clock::now();
        while (n_gen < limit) {
            if (e->cancel.load()) { finish = "cancelled"; break; }
            if ((int) pos >= n_ctx - 1) { finish = "length"; break; }
            llama_token id = common_sampler_sample(smpl, e->ctx, -1);
            common_sampler_accept(smpl, id, true);
            if (llama_vocab_is_eog(vocab, id)) { finish = "stop"; break; }
            std::string piece = common_token_to_piece(vocab, id, true);
            gen.append(piece, id);
            text += piece;
            n_gen++;
            bool hit = false;
            for (const auto & s : stops) {
                if (!s.empty() && text.size() >= s.size() && text.compare(text.size() - s.size(), s.size(), s) == 0) { hit = true; break; }
            }
            batch.clear();
            batch.add(id, pos, 0, true);
            if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { finish = "error"; break; }
            e->cached.push_back(id);
            pos++;
            if (hit) { finish = "stop"; break; }
        }
        common_sampler_free(smpl);
        smpl = nullptr;
        int64_t gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t_gen0).count();

        common_chat_msg msg = session.finish(gen);
        common_json out = common_json::object();
        out["content"] = msg.content.empty() ? msg.render_content() : msg.content;
        out["reasoning"] = msg.reasoning_content;
        common_json calls = common_json::array();
        int k = 0;
        for (const auto & tc : msg.tool_calls) {
            common_json c = common_json::object();
            c["id"] = tc.id.empty() ? ("call_local_" + std::to_string(n_gen) + "_" + std::to_string(k)) : tc.id;
            c["name"] = tc.name;
            c["arguments"] = tc.arguments.empty() ? std::string("{}") : tc.arguments;
            calls.push_back(c);
            k++;
        }
        out["tool_calls"] = calls;
        out["finish"] = msg.tool_calls.empty() ? finish : std::string("tool_calls");
        out["prompt_tokens"] = (int64_t) prompt.size();
        out["completion_tokens"] = (int64_t) n_gen;
        out["gen_ms"] = gen_ms;
        out["backend"] = e->backend;
        out["raw"] = msg.tool_calls.empty() && msg.content.empty() ? text : std::string();
        return out.dump_safe();
    } catch (const std::bad_alloc &) {
        if (smpl) common_sampler_free(smpl);
        e->cached.clear();
        return error_json("Out of memory");
    } catch (const std::exception & ex) {
        if (smpl) common_sampler_free(smpl);
        return error_json(std::string("Local model error: ") + ex.what());
    }
}



// GPU probe: run in a throwaway process (a driver crash there can't take the app down). Lists Vulkan devices and
// checks a small f32 matmul on the GPU against the CPU result.
std::string gpu_probe_impl() {
    common_json out = common_json::object();
#if !VERDROID_HAS_VULKAN
    out["ok"] = false; out["error"] = std::string("This build has no Vulkan backend");
    return out.dump_safe();
#else
    init_backend(true);
    if (!g_vk_enabled) { out["ok"] = false; out["error"] = std::string("Vulkan was not enabled in this process"); return out.dump_safe(); }
    int n = ggml_backend_vk_get_device_count();
    common_json devs = common_json::array();
    for (int i = 0; i < n; i++) {
        char d[256] = {0};
        ggml_backend_vk_get_device_description(i, d, sizeof(d));
        size_t fr = 0, tot = 0;
        ggml_backend_vk_get_device_memory(i, &fr, &tot);
        common_json o = common_json::object();
        o["name"] = std::string(d); o["mem_total"] = (int64_t) tot; o["mem_free"] = (int64_t) fr;
        devs.push_back(o);
    }
    out["devices"] = devs;
    auto * dev = gpu_device();
    if (!dev) { out["ok"] = false; out["error"] = std::string(n == 0 ? "No Vulkan GPU found" : "Vulkan GPU not supported by llama.cpp"); return out.dump_safe(); }
    out["device"] = std::string(ggml_backend_dev_description(dev));
    auto t0 = std::chrono::steady_clock::now();
    ggml_backend_t be = ggml_backend_dev_init(dev, nullptr);
    if (!be) { out["ok"] = false; out["error"] = std::string("Vulkan device init failed"); return out.dump_safe(); }
    const int K = 64, M = 32, N = 16;
    ggml_init_params ip = { ggml_tensor_overhead() * 8 + ggml_graph_overhead(), nullptr, true };
    ggml_context * ctx = ggml_init(ip);
    ggml_tensor * a = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, K, M);
    ggml_tensor * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, K, N);
    ggml_tensor * c = ggml_mul_mat(ctx, a, b);
    ggml_cgraph * gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, c);
    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, be);
    std::vector<float> av(K * M), bv(K * N), cv(M * N);
    for (int i = 0; i < K * M; i++) av[i] = std::sin(0.1f * i);
    for (int i = 0; i < K * N; i++) bv[i] = std::cos(0.07f * i);
    ggml_backend_tensor_set(a, av.data(), 0, av.size() * 4);
    ggml_backend_tensor_set(b, bv.data(), 0, bv.size() * 4);
    bool ok = buf && ggml_backend_graph_compute(be, gf) == GGML_STATUS_SUCCESS;
    float max_err = 0;
    if (ok) {
        ggml_backend_tensor_get(c, cv.data(), 0, cv.size() * 4);
        for (int j = 0; j < N; j++) for (int i = 0; i < M; i++) {
            float ref = 0; for (int k = 0; k < K; k++) ref += av[i * K + k] * bv[j * K + k];
            max_err = std::max(max_err, std::fabs(ref - cv[j * M + i]));
        }
        ok = std::isfinite(max_err) && max_err < 0.05f;
    }
    if (buf) ggml_backend_buffer_free(buf);
    ggml_free(ctx);
    ggml_backend_free(be);
    out["ok"] = ok;
    out["max_err"] = (double) max_err;
    out["ms"] = (int64_t) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    if (!ok) out["error"] = std::string("GPU matmul gave wrong results or failed");
    return out.dump_safe();
#endif
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint gpu_layers) {
    return reinterpret_cast<jlong>(load_impl(jstr(env, jpath), n_ctx, n_threads, gpu_layers));
}

JNIEXPORT jstring JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeLastError(JNIEnv * env, jobject) { return to_j(env, g_last_error); }

JNIEXPORT void JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    e->cancel = true;
    std::lock_guard<std::mutex> lock(e->mu);
    e->tmpls.reset();
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (e) e->cancel = true;
}

JNIEXPORT jstring JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeInfo(JNIEnv * env, jobject, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return to_j(env, "{}");
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    common_json j = common_json::object();
    j["desc"] = std::string(desc);
    j["n_ctx"] = (int64_t) llama_n_ctx(e->ctx);
    j["n_ctx_train"] = (int64_t) llama_model_n_ctx_train(e->model);
    j["size_bytes"] = (int64_t) llama_model_size(e->model);
    j["n_params"] = (int64_t) llama_model_n_params(e->model);
    j["has_template"] = common_chat_templates_was_explicit(e->tmpls.get());
    j["system"] = std::string(llama_print_system_info());
    j["backend"] = e->backend;
    j["gpu_layers"] = (int64_t) e->gpu_layers;
    return to_j(env, j.dump_safe());
}

JNIEXPORT jstring JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeChat(JNIEnv * env, jobject, jlong h, jstring jmessages, jstring jtools,
                                                             jint max_tokens, jfloat temp, jboolean thinking) {
    return to_j(env, chat_impl(reinterpret_cast<Engine *>(h), jstr(env, jmessages), jstr(env, jtools), max_tokens, temp, thinking));
}

JNIEXPORT jstring JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeGpuProbe(JNIEnv * env, jobject) {
    try { return to_j(env, gpu_probe_impl()); } catch (const std::exception & ex) { return to_j(env, error_json(std::string("GPU probe: ") + ex.what())); }
}

// Decides once per process whether Vulkan is registered. Returns true if this call (or an earlier one) enabled it.
JNIEXPORT jboolean JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeInitBackend(JNIEnv *, jobject, jboolean vulkan) {
    init_backend(vulkan);
    return g_vk_enabled;
}

JNIEXPORT jboolean JNICALL
Java_com_verdroid_app_data_localllm_LlamaNative_nativeHasVulkan(JNIEnv *, jobject) { return VERDROID_HAS_VULKAN ? JNI_TRUE : JNI_FALSE; }

}  // extern "C"
