package cc.noharry.bothy.sample

import cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.bothy.kernel.api.HookContext
import cc.noharry.bothy.kernel.api.HookDecision
import cc.noharry.bothy.kernel.api.RuntimeHook
import cc.noharry.bothy.kernel.api.ToolUse

/**
 * Placing an order or paying ([isTransaction]) waits for the user (a request like 「…的时候帮我点一杯咖啡」 once placed an
 * order at once, on the model's own wrong belief that the condition was already met): only when this run is the user's
 * yes to a question about it, a picked option bound to that call, or a request that says so outright
 * (「确认下单」「直接付款」). A fired plan's run (【计划到点】) carries what the user approved when the plan was made. A
 * subagent ([child]) never orders: its input is the task the main agent wrote, not the user's words. One yes allows one
 * order.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class DebugActionGate(
    private val userInput: String,
    private val child: Boolean = false,
) : RuntimeHook {
    @Volatile private var ordered = false

    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        val name = call.toolName
        if (child) return if (isTransaction(name)) HookDecision.Deny(reason = CHILD_REFUSED) else HookDecision.Allow()
        if (userInput.startsWith(PLAN_RUN_MARK)) return HookDecision.Allow()
        if (isTransaction(name)) {
            if (ordered || !debugConfirmsTransaction(userInput, name)) return HookDecision.Deny(reason = TRANSACTION_REFUSED)
            ordered = true
        }
        return HookDecision.Allow()
    }
}

/** A tool that places an order or pays: createOrder, submit_order, payOrder, checkout… (previewOrder and queries pass). */
internal fun isTransaction(toolName: String): Boolean {
    val words = toolName.substringAfterLast("__").toWords()
    return ("order" in words && words.any { it in ORDER_VERBS }) || words.any { it in PAY_WORDS }
}

/**
 * The user said yes to this order: a short reply under a quoted question about ordering or paying, an on-screen pick
 * whose bound call is [toolName], or a request that asks to order or pay outright.
 */
internal fun debugConfirmsTransaction(input: String, toolName: String): Boolean {
    if (input.startsWith(FOLLOW_UP_FRAME_HEADER)) {
        val quoted = input.substringBefore(USER_REPLY_MARK)
        val reply = input.substringAfter(USER_REPLY_MARK, "")
        // 「只要一次」 answers how often a rule runs, not whether to order now.
        val howOften = HOW_OFTEN.containsMatchIn(reply) && !ORDER_WORD.containsMatchIn(reply)
        return ORDER_TOPIC.containsMatchIn(quoted) && debugSaysYes(reply) && !howOften
    }
    if (BOUND_PICK_MARK in input) return "调用 $toolName" in input
    // 「不要直接下单」「不用现在付」 hold the words of an outright order and mean the opposite.
    return OUTRIGHT.containsMatchIn(input) && !HOLD_OFF.containsMatchIn(input)
}

/**
 * A reply that is nothing but a yes: 「好的，下单吧」「这次直接下单吧」, not 「大杯是多少钱」「要几分钟」「对了换燕麦奶」, which
 * hold a yes-like character but ask or change something.
 */
internal fun debugSaysYes(reply: String): Boolean {
    val words = reply.lowercase().filter(Char::isLetterOrDigit)
    return words.isNotEmpty() && YES_ONLY.matches(words)
}

/**
 * The user's own words in [input]: the reply under a quoted message (the quote is the assistant's, and 「已帮你建一条规则…」
 * there must not make 「好」 a rule request), nothing for an on-screen pick, else the input.
 */
internal fun debugUserWords(input: String): String = when {
    input.startsWith(FOLLOW_UP_FRAME_HEADER) -> input.substringAfter(USER_REPLY_MARK, "").trim()
    BOUND_PICK_MARK in input -> ""
    else -> input
}

/** 「createOrder」「submit_order」「android_observe」 as lower-case words. */
private fun String.toWords(): List<String> = split('_').flatMap { it.split(CAMEL_BOUNDARY) }.map(String::lowercase)

private const val PLAN_RUN_MARK = "【计划到点】"
private const val USER_REPLY_MARK = "User reply:"
private const val BOUND_PICK_MARK = "绑定的操作已由宿主按工具定义核对"
private val ORDER_VERBS = setOf("create", "submit", "place", "confirm")
private val PAY_WORDS = setOf("pay", "payment", "checkout", "purchase")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
private val ORDER_TOPIC = Regex("下单|付款|支付|订单|买单|结账")
// Only these words, in any order (after dropping punctuation): a reply with anything else in it is not a plain yes.
private val YES_ONLY = Regex("(?:好的?|好吧|行|可以|确认|确定|下单|付款|支付|付|买|嗯+|是的?|对的?|要的?|没问题|ok|okay|就|这次|直接|现在|马上|帮我|吧|啊|呀|了|呗)+")
private val HOW_OFTEN = Regex("每次|一次|这次|以后")
private val ORDER_WORD = Regex("下单|付|买")
private val HOLD_OFF = Regex("不要|不用|不需要|别|先不|暂时不|不急|留着|等等|再说")
private val OUTRIGHT = Regex("确认下单|直接下单|下单吧|马上下单|现在下单|直接付|现在付|付款吧|帮我付|下单并(付|支付)")

private const val TRANSACTION_REFUSED =
    "没有下单：下单和付款要用户先看过订单（门店、商品、价格）并说好。先用预览接口把订单告诉用户，问要不要下单，" +
        "用户确认后再调用。用户说的是「…的时候」才下单的话，用 plan_manage 建规则，触发时再下。"
private const val CHILD_REFUSED =
    "子 agent 不能下单或付款：把预览到的订单（门店、商品、价格）写进结果交回，由主 agent 问用户。"
