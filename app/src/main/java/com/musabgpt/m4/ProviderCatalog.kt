package com.musabgpt.m4

/** These are open-source gateway *families*, not four falsely advertised hosted APIs.
 * FreeLLMPool catalog routes are used directly via HTTPS. Other gateways require
 * a running loopback server and never get privileged access to the phone.
 */
data class GatewayRoute(
    val name: String,
    val baseUrl: String,
    val model: String,
    val localService: Boolean = false
)
data class Provider(val id: String, val name: String, val routes: List<GatewayRoute>)

object ProviderCatalog {
    val all: List<Provider> = listOf(
        Provider("freellmpool", "FreeLLMPool", listOf(
            GatewayRoute("Kilo Auto Free", "https://api.kilo.ai/api/gateway", "kilo-auto/free"),
            GatewayRoute("LLM7 Free", "https://api.llm7.io/v1", "default"),
            GatewayRoute("Pollinations Free", "https://text.pollinations.ai/openai", "openai-fast"),
            GatewayRoute("OVHcloud Free", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1", "Qwen3-Coder-30B-A3B-Instruct")
        )),
        Provider("gpt4free", "GPT4Free", listOf(
            GatewayRoute("GPT4Free on-device", "http://127.0.0.1:1337/v1", "auto", true)
        )),
        Provider("free-llm-gateway", "Free LLM Gateway", listOf(
            GatewayRoute("MrFadiAi gateway on-device", "http://127.0.0.1:8080/v1", "auto", true)
        )),
        Provider("free-llm-api", "FreeLLMAPI", listOf(
            GatewayRoute("PryxIntel gateway on-device", "http://127.0.0.1:3001/v1", "auto", true)
        ))
    )

    /** If a selected gateway is not started, fall back to vetted anonymous routes. */
    fun routingOrder(selected: Provider): List<GatewayRoute> =
        (selected.routes + all[0].routes).distinctBy { it.baseUrl + "|" + it.model }
}
