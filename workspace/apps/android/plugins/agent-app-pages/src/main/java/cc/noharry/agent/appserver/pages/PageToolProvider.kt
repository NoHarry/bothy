@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.pages

import android.content.Context
import cc.noharry.agent.appserver.protocol.view.SHOW_HTML_TOOL
import cc.noharry.agent.appserver.protocol.view.parseJsonObject
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolCallTrace
import cc.noharry.agent.spi.ToolCatalogCacheIdentity
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolPrefetchProvider
import cc.noharry.agent.spi.ToolPrefetchRequest
import cc.noharry.agent.spi.ToolPrefetchTrace
import cc.noharry.agent.spi.mcp.McpClientProvider
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `show_html`: the model draws a page in the conversation, as HTML with its own styles and script, where a card's fixed
 * parts (`show_card`, the `offer_*` controls) cannot show it: a comparison, a chart, a calculator, a checklist to tick.
 * The page is saved under the run's session ([save]) and the call returns its reference; the screen reads it from there
 * (`pages/read`) and runs it offline, in its own colours. In every run with a service's model ([PagesPlugin]), so a
 * page tool a session took up in the app still answers out of it; offered from the first round only to a run that
 * started with the conversation on screen ([onScreen]). One that did not is told not to draw, and a page it draws anyway
 * is refused, as nobody would see it. While [cards] are offered too, a page does not stand in for them.
 */
internal class PageToolProvider(
    private val onScreen: Boolean,
    private val cards: () -> Boolean = { false },
    private val save: (sessionId: String, html: String) -> String,
) : McpClientProvider, ToolPrefetchProvider, ToolCatalogCacheIdentity {
    constructor(context: Context, onScreen: Boolean, cards: () -> Boolean) :
        this(onScreen, cards, { sessionId, html -> saveDrawnPage(context.applicationContext, sessionId, html) })

    override val serverId: String = PAGE_TOOL_SERVER_ID
    override val displayName: String get() = modelText("画界面", "Pages")
    override val catalogCacheIdentity: String get() = "app-server:$PAGE_TOOL_SERVER_ID:v4:${modelText("zh", "en")}${if (cards()) ":cards" else ""}"
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.threadSafe()
    override val remotePrefetchEnabled: Boolean get() = false

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(
            ToolDescriptor(
                SHOW_HTML_TOOL, title = modelText("画界面", "Draw a page"),
                description = modelText(SHOW_HTML_DESCRIPTION, SHOW_HTML_DESCRIPTION_EN) + if (cards()) cardsNote() else "",
                inputSchemaJson = showHtmlSchema(),
            ),
        )

    /**
     * Offered from the first round while the conversation is on screen, as the cards are: whether to draw is decided
     * before the answer is written. Off screen nothing is offered (its description would be words for nothing).
     */
    override fun localToolNames(request: ToolPrefetchRequest): List<String> = if (onScreen) listOf(SHOW_HTML_TOOL) else emptyList()

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName != SHOW_HTML_TOOL) return failure("UNKNOWN_TOOL", "Unknown tool: $toolName")
        if (!onScreen) return failure("PAGE_NOT_ON_SCREEN", "The conversation is not on screen, so nobody would see the page.")
        val args = parseJsonObject(argumentsJson) ?: return failure("ARGUMENT_INVALID", "Arguments must be an object.")
        val title = args["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val html = args["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pageProblem(title, html)?.let { return failure("PAGE_INVALID", it) }
        // A page that could not be kept leaves the model its fallback, words, rather than failing the run.
        val reference = try {
            withContext(Dispatchers.IO) { save(context.sessionId, html) }
        } catch (unsaved: IOException) {
            return failure("PAGE_NOT_SAVED", "The page could not be saved (${unsaved.message}).")
        }
        return ToolCallResult(
            success = true,
            resultJson = buildJsonObject {
                put("ok", true)
                put("page", reference)
                put("title", title)
                put(
                    "message",
                    modelText(
                        "已显示在对话里。finish 用一两句话说要点或怎么用，不要把页面里的内容再写一遍。",
                        "Shown in the conversation. In finish, give the main point or how to use it in a sentence or two; do not write the page out again.",
                    ),
                )
            }.toString(),
            trace = ToolCallTrace(validationOk = true, validationCode = "OK"),
        )
    }

    private fun failure(code: String, message: String): ToolCallResult = ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", "$message " + modelText("没有显示；改正后再调用一次，或直接在 finish 里用文字回答。", "Not shown: fix it and call again, or answer in words in finish."))
        }.toString(),
        trace = ToolCallTrace(validationOk = false, validationCode = code, validationMessage = message),
    )
}

/** What keeps a page from showing, in words the model can fix it by; null when it may show. */
internal fun pageProblem(title: String, html: String): String? {
    when {
        title.isBlank() -> return "title is required."
        html.isBlank() -> return "html is required."
        // Before the scan below, which a long page makes slow.
        html.length > MAX_PAGE_CHARS -> return "The page has ${html.length} characters; keep it under $MAX_PAGE_CHARS."
    }
    val external = EXTERNAL_LOAD.find(html)?.value ?: return null
    return "The page loads from the network ($external…), but the screen is offline: write scripts and styles into the page " +
        "and draw pictures as inline SVG."
}

/**
 * A script, stylesheet, picture or frame fetched from elsewhere; links the person may tap are fine (they stay put). A
 * tag's attributes are looked through for a few hundred characters only, so a page of unclosed tags stays quick.
 */
private val EXTERNAL_LOAD = Regex("""<(?:script|link|img|iframe)\b[^>]{0,512}?\b(?:src|href)\s*=\s*["']?(?:https?:)?//""", RegexOption.IGNORE_CASE)

internal const val MAX_PAGE_CHARS = 200_000
internal const val PAGE_TOOL_SERVER_ID = "pages"
private val SHOW_HTML_DESCRIPTION = """
    画界面：把内容画成页面，放进手机或平板上的对话页。运行上下文说用户正看着对话页时，下面这些直接画成页面，
    不要先问要不要画：回答会超过三四百字，
    或有三个以上并列的要点、层次、步骤；比较和取舍；数字和图表；时间线、行程、计划；新闻简报、分析和报告；
    计算器、换算、能勾选的清单、小测验这类要动手试的。一两句能答的、闲聊和追问、要复制的纯文本（地址、命令、代码）、
    用户说用文字的，直接用文字。顺序：先用一句话说结论（马上就显示给用户），再调用本工具，finish 只补一两句，
    不要把页面内容再写一遍。title 写页面标题；html 写完整页面，样式和脚本都写在页面里。屏幕断网：不能引用外部脚本、
    样式、字体和图片，图形用内联 SVG 或 canvas 画。页面整个摊在对话里、自己不上下滚动：宽度跟着屏幕（手机约
    360–420px），不要写死宽度，表格列多就改成每项一张小卡片；高度随内容，不要用 vh，不要放固定高度、能上下滚动的区域；
    尽量一两屏内放下，内容多就用标签页或折叠。让人选择用按钮或单选框，不用 select 下拉框和 date、time 这类
    弹系统选择框的输入框（页面也会显示在悬浮的面板里，那里弹不出来）。宿主已备好样式，直接用类名、少写 CSS：b-stack（竖排留白）、
    b-grid（自动分栏）、b-row（横排）、b-card（区块）、b-h（小节标题）、b-sub（次要说明）、
    b-stat（大数字：<div class="b-stat"><b>63%</b><span>说明</span></div>）、b-tag（标签，加 warn 或 bad 变色）、
    b-steps（ol 步骤）、b-timeline（ul 时间线）、b-bar（进度条：<div class="b-bar"><i style="width:60%"></i></div>）、
    b-btn（按钮，加 ghost 为次要）。配色用宿主的 CSS 变量，深浅色自动跟着变：--page-bg（页面底色）、
    --page-surface（区块底色）、--page-text、--page-muted（次要文字）、--page-accent（强调色）、
    --page-on-accent（强调色上的文字）、--page-border、--page-warn、--page-danger。要把用户的选择带回对话，
    在点击处理里调用 assistant.send('要说的话')，它会作为用户的一句话发出。
""".trimIndent().replace("\n", "")
private val SHOW_HTML_DESCRIPTION_EN = """
    Draw a page: lays content out as a page in the conversation on the phone or tablet. While the run context says the user is
    looking at the conversation, draw these as a page without asking first: an answer that would run past 300–400 words, or has
    three or more parallel points, levels or steps; comparisons and trade-offs; numbers and charts; timelines, itineraries and
    plans; news briefings, analyses and reports; calculators, converters, checklists, quizzes and other things to try out.
    Answer in words what a sentence or two answers, small talk and follow-ups, plain text to copy (addresses, commands, code),
    and whatever the user wants in words. Order: first say the conclusion in one sentence (the user sees it at once), then call
    this tool; finish adds a sentence or two and does not write the page out again. title is the page's title; html is the
    whole page, its styles and scripts inside. The screen is offline: no external scripts, styles, fonts or images; draw
    graphics with inline SVG or canvas. The page lies open in the conversation and does not scroll itself: its width follows
    the screen (about 360–420px on a phone), so fix no width, and make a table with many columns a small card per item; its
    height follows the content, so no vh and no fixed-height scrolling areas; fit it in a screen or two, with tabs or folds
    when there is a lot. Let people choose with buttons or radio buttons, not select drop-downs or date and time inputs that
    open a system picker (the page also shows in a floating panel, where those cannot open). The host has styles ready, so use
    the class names and write little CSS: b-stack (vertical spacing), b-grid (automatic columns), b-row (a row), b-card (a
    block), b-h (section heading), b-sub (secondary note), b-stat (a big number: <div class="b-stat"><b>63%</b><span>note</span></div>),
    b-tag (a tag; add warn or bad to colour it), b-steps (ol steps), b-timeline (ul timeline), b-bar (a progress bar:
    <div class="b-bar"><i style="width:60%"></i></div>), b-btn (a button; add ghost for a secondary one). Colour with the
    host's CSS variables, which follow light and dark: --page-bg (page background), --page-surface (block background),
    --page-text, --page-muted (secondary text), --page-accent (accent), --page-on-accent (text on the accent), --page-border,
    --page-warn, --page-danger. To bring the user's choice back to the conversation, call assistant.send('what to say') in the
    click handler; it is sent as something the user said.
""".trimIndent().replace("\n", " ")

/** While the card and option tools are offered (a plugin offers them), a page does not stand in for them. */
private fun cardsNote(): String = modelText(
    "不要用它代替 show_card 和 offer_*：要直接执行的操作（车控、设值、选项）仍用它们。",
    " Do not use it in place of show_card and offer_*: actions to carry out right away (device controls, settings, options) still go through them.",
)
private fun showHtmlSchema(): String =
    """{"type":"object","additionalProperties":false,"required":["title","html"],"properties":{""" +
        """"title":{"type":"string","description":"${modelText("页面标题，几个字", "the page's title, a few words")}"},""" +
        """"html":{"type":"string","description":"${modelText(
            "完整的 HTML 页面，样式和脚本都写在里面，不引用任何外部资源",
            "the whole HTML page, its styles and scripts inside, loading nothing from elsewhere",
        )}"}}}"""
