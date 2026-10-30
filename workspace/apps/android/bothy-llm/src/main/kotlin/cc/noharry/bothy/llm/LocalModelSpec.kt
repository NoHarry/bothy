package cc.noharry.bothy.llm

/**
 * A GGUF the app downloads and serves: where to fetch it (tried in order), what the finished file must be ([sizeBytes]
 * and [sha256]), and how llama-server runs it. [contextTokens] is the server's window, which the agent compacts within.
 * [publisher], [quantization], [license] and [note] are what the settings page shows about it.
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
    val publisher: String = "",
    val quantization: String = "",
    val license: String = "",
    val note: String = "",
)

/**
 * Gemma 4 E4B, Google's quantization-aware-trained Q4_0 GGUF. Of the small models tried on an 8 Elite Gen 5 tablet's
 * NPU with the agent's own requests, it never claimed a car command it had not called (it asks instead), at about
 * 1300 tokens/s prefill and 20 tokens/s decode. Thinking is off: the agent's turns are short and spoken.
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
    publisher = "Google",
    quantization = "QAT Q4_0",
    license = "Apache-2.0",
    note = "实测最稳：没调用工具时没说过已经办好，拿不准就先问。",
)

/**
 * Qwen3 4B Instruct 2507 (Alibaba, unsloth's Q4_0 GGUF), a non-thinking model: the quickest to act on a car command,
 * about as fast as Gemma 4 E4B at half its size, but now and then it says a command is done without having called it.
 */
val QWEN3_4B_2507 = LocalModelSpec(
    id = "qwen3-4b-instruct-2507",
    label = "Qwen3 4B 2507",
    fileName = "Qwen3-4B-Instruct-2507-Q4_0.gguf",
    sizeBytes = 2_375_773_280L,
    sha256 = "e0ba675d86ab277c61701c6793659b2ae801d95e3be791464c321e6fbf613be2",
    urls = listOf(
        "https://www.modelscope.cn/models/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/master/Qwen3-4B-Instruct-2507-Q4_0.gguf",
        "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_0.gguf",
    ),
    contextTokens = 32_768,
    publisher = "阿里通义千问",
    quantization = "Q4_0",
    license = "Apache-2.0",
    note = "实测最干脆：动手快，但偶尔说办好了却没真的调用工具。",
)

/**
 * MiniCPM5 2B (ModelBest), its official Q8_0 GGUF (there is no Q4_0; the NPU runs Q8_0 natively). Thinking stays on:
 * without it, it acted on answers to its own questions. `min_p` 0 as its model card recommends against repetition.
 */
val MINICPM5_2B = LocalModelSpec(
    id = "minicpm5-2b",
    label = "MiniCPM5 2B",
    fileName = "MiniCPM5-2B-Q8_0.gguf",
    sizeBytes = 2_679_710_688L,
    sha256 = "c5415f8989bf88a8288f1b55a3cc371af53c07b0faa220a63bd7a990cfaba078",
    urls = listOf(
        "https://www.modelscope.cn/models/OpenBMB/MiniCPM5-2B-GGUF/resolve/master/MiniCPM5-2B-Q8_0.gguf",
        "https://huggingface.co/openbmb/MiniCPM5-2B-GGUF/resolve/main/MiniCPM5-2B-Q8_0.gguf",
    ),
    contextTokens = 32_768,
    serverArgs = listOf("--chat-template-kwargs", """{"enable_thinking":true}""", "--min-p", "0"),
    publisher = "面壁智能",
    quantization = "Q8_0",
    license = "Apache-2.0",
    note = "实测会自己找工具，没说过假的办好；每步先思考，慢一些。",
)

/** The models the app offers, the default first. */
val LOCAL_MODELS: List<LocalModelSpec> = listOf(GEMMA_4_E4B, QWEN3_4B_2507, MINICPM5_2B)

/** The offered model with [id], or null. */
fun localModel(id: String?): LocalModelSpec? = LOCAL_MODELS.firstOrNull { it.id == id?.trim() }
