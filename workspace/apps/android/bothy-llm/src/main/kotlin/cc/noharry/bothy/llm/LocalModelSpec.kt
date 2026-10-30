package cc.noharry.bothy.llm

/**
 * A GGUF the app downloads and serves: where to fetch it (tried in order), what the finished file must be ([sizeBytes]
 * and [sha256]), and how llama-server runs it. [contextTokens] is the server's window, which the agent compacts within.
 */
data class LocalModelSpec(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>,
    val contextTokens: Int,
    /** Extra llama-server arguments for this model, e.g. a chat-template switch. */
    val serverArgs: List<String> = emptyList(),
)

/**
 * Gemma 4 E4B, Google's quantization-aware-trained Q4_0 GGUF (Apache-2.0). Of the small models tried on an 8 Elite
 * Gen 5 tablet's NPU with the agent's own requests, it never claimed a car command it had not called (it asks instead),
 * at about 1300 tokens/s prefill and 20 tokens/s decode. Thinking is off: the agent's turns are short and spoken.
 */
val GEMMA_4_E4B = LocalModelSpec(
    id = "gemma-4-e4b",
    label = "Gemma 4 E4B",
    fileName = "gemma-4-E4B_q4_0-it.gguf",
    sizeBytes = 5_154_941_280L,
    sha256 = "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
    urls = listOf(
        "https://www.modelscope.cn/models/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/master/gemma-4-E4B_q4_0-it.gguf",
        "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B_q4_0-it.gguf",
    ),
    contextTokens = 32_768,
    serverArgs = listOf("--chat-template-kwargs", """{"enable_thinking":false}"""),
)
