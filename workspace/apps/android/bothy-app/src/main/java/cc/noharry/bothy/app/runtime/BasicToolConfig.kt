package cc.noharry.bothy.app.runtime
import android.content.Context

internal data class BasicTool(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val required: Boolean = false,
)

internal fun loadBasicTools(context: Context): List<BasicTool> {
    val preferences = context.getSharedPreferences(BASIC_TOOL_PREFS, Context.MODE_PRIVATE)
    return BASIC_TOOLS.map { it.copy(enabled = it.required || preferences.getBoolean(it.name, it.enabled)) }
}

internal fun saveBasicTool(context: Context, name: String, enabled: Boolean) {
    val tool = BASIC_TOOLS.singleOrNull { it.name == name } ?: error("Unknown basic tool.")
    require(!tool.required || enabled) { "finish is required by the runtime protocol." }
    context.getSharedPreferences(BASIC_TOOL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(name, enabled).apply()
}

internal fun disabledBasicTools(tools: List<BasicTool>): Set<String> =
    tools.filter { !it.enabled && !it.required }.mapTo(linkedSetOf()) { it.name }

internal fun basicToolPrompt(tools: List<BasicTool>): String {
    // exec_js is off by default and absent from the tool list; naming it would only invite script plans and
    // spend prompt budget on every request.
    val disabled = disabledBasicTools(tools) - EXEC_TOOL_NAME
    if (disabled.isEmpty()) return ""
    return "Host tool configuration for this request: disabled native tools: ${disabled.joinToString()}. " +
        "Use only advertised tools. Skill recipes apply only to available tools. " +
        "If update_todo is unavailable, track progress in reasoning without calling it. " +
        "If discovery or another capability is unavailable, use available alternatives or report the limitation."
}

private const val EXEC_TOOL_NAME = "exec_js"

private const val BASIC_TOOL_PREFS = "debug_basic_tools"
private val BASIC_TOOLS = listOf(
    BasicTool("exec_js", "执行短脚本并按依赖调用工具；独立开关，skill 不会自动开启。", enabled = false),
    BasicTool("search_tools", "按语义发现 MCP 工具并加载参数定义。"),
    BasicTool("list_dir", "列出工作区、skill 和 MCP 目录。"),
    BasicTool("read_file", "读取工作区内容、skill 和工具定义。"),
    BasicTool("grep", "搜索工作区文件内容。"),
    BasicTool("write_file", "写入工作区文件。"),
    BasicTool("edit_file", "修改工作区文件。"),
    BasicTool("update_todo", "维护任务步骤与进度。"),
    BasicTool("ask_async", "提出问题后继续执行；回答进入后续请求，不回复也不等待。"),
    BasicTool("ask", "向用户提出澄清问题并等待回复。"),
    BasicTool("web_fetch", "获取网页内容；模型服务自带可见搜索工具时会隐藏，开了「网页打不开时用 DeepSeek Fetch」除外。"),
    BasicTool("web_search", "联网搜索，结果模型和日志都看得到；连接配置选用 DeepSeek Search 接口时才出现。"),
    BasicTool("spawn_agent", "把可以并行的耗时任务交给后台子助手；由主助手自己决定要不要用。"),
    BasicTool("wait_agent", "等待后台子助手，返回各自的进度或结果。"),
    BasicTool("cancel_agent", "停掉不再需要或卡住的后台子助手。"),
    BasicTool("finish", "结束任务并返回答复，运行协议必需。", required = true),
)
