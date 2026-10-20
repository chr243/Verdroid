// Developer smoke test (not packaged): build with -DVERDROID_NATIVE_TEST=ON for x86_64/arm64 and run
//   native_smoke model.gguf   → plain reply + a tool-call turn through the same code the JNI uses.
#include "verdroid_llama.cpp"
#include <cstdio>

int main(int argc, char ** argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s model.gguf\n", argv[0]); return 2; }
    Engine * e = load_impl(argv[1], 2048, 4);
    if (!e) { fprintf(stderr, "load failed: %s\n", g_last_error.c_str()); return 1; }
    std::string tools = R"([{"type":"function","function":{"name":"web_search","description":"Search the web.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}])";
    printf("PLAIN: %s\n", chat_impl(e, R"([{"role":"system","content":"You are helpful."},{"role":"user","content":"What is 2+2? One short sentence."}])", "", 64, 0.0f, false).c_str());
    printf("TOOL: %s\n", chat_impl(e, R"([{"role":"system","content":"You are helpful. Use tools when needed."},{"role":"user","content":"Search the web for the weather in Paris today."}])", tools, 128, 0.0f, false).c_str());
    printf("AFTER_TOOL: %s\n", chat_impl(e, R"([{"role":"system","content":"You are helpful. Use tools when needed."},{"role":"user","content":"Search the web for the weather in Paris today."},{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"weather Paris today\"}"}}]},{"role":"tool","tool_call_id":"c1","content":"Paris: 14°C, light rain."}])", tools, 96, 0.0f, false).c_str());
    return 0;
}
