package cc.noharry.bothy.server.tools

import cc.noharry.agent.appserver.a2ui.isBoundPick
import cc.noharry.agent.appserver.a2ui.picksBoundCall
import cc.noharry.agent.appserver.plans.isPlanRun
import cc.noharry.agent.appserver.runtime.followUpParts
import cc.noharry.agent.appserver.tools.isTransaction
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse

/**
 * Placing an order or paying ([isTransaction]) waits for the user (a request like 「…的时候帮我点一杯咖啡」 once placed an
 * order at once, on the model's own wrong belief that the condition was already met): only when this run is the user's
 * yes to a question about it, a picked option bound to that call, or a request that says so outright
 * (「确认下单」「直接付款」). A fired plan's run (【计划到点】) carries what the user approved when the plan was made. A
 * subagent ([child]) never orders: its input is the task the main agent wrote, not the user's words. One yes allows one
 * order.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
internal class ActionGate(
    private val userInput: String,
    private val child: Boolean = false,
) : RuntimeHook {
    @Volatile private var ordered = false

    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        val name = call.toolName
        if (child) return if (isTransaction(name)) HookDecision.Deny(reason = CHILD_REFUSED) else HookDecision.Allow()
        if (userInput.isPlanRun()) return HookDecision.Allow()
        if (isTransaction(name)) {
            if (ordered || !confirmsTransaction(userInput, name)) return HookDecision.Deny(reason = TRANSACTION_REFUSED)
            ordered = true
        }
        return HookDecision.Allow()
    }
}

/**
 * The user said yes to this order: a short reply under a quoted question about ordering or paying, an on-screen pick
 * whose bound call is [toolName], or a request that asks to order or pay outright.
 */
internal fun confirmsTransaction(input: String, toolName: String): Boolean {
    input.followUpParts()?.let { (quoted, reply) ->
        // 「只要一次」 answers how often a rule runs, not whether to order now.
        val howOften = HOW_OFTEN.containsMatchIn(reply) && !ORDER_WORD.containsMatchIn(reply)
        return ORDER_TOPIC.containsMatchIn(quoted) && saysYes(reply) && !howOften
    }
    if (input.isBoundPick()) return input.picksBoundCall(toolName)
    // 「不要直接下单」「不用现在付」 hold the words of an outright order and mean the opposite.
    return OUTRIGHT.containsMatchIn(input) && !HOLD_OFF.containsMatchIn(input)
}

/**
 * A reply that is nothing but a yes: 「好的，下单吧」「这次直接下单吧」, not 「大杯是多少钱」「要几分钟」「对了换燕麦奶」, which
 * hold a yes-like character but ask or change something.
 */
internal fun saysYes(reply: String): Boolean {
    val words = reply.lowercase().filter(Char::isLetterOrDigit)
    return words.isNotEmpty() && YES_ONLY.matches(words)
}

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
