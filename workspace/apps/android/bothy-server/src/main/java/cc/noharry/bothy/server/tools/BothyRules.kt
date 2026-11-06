package cc.noharry.bothy.server.tools

/** The subagent type that checks online what a map or travel service returned (网上核实). */
internal const val WEB_VERIFY_KIND = "web-verify"

/**
 * When to send 网上核实 (owner 2026-10-19: 除了导航给出的数据，也派一个核实的 agent 去网页上搜一下，不一定在哪种场景下
 * 触发; 先答再补报; 不能拖慢正常导航，只在需要印证的查询里触发). A rule of the main agent's, not a skill matched by
 * words, so any scene can use it; on the bench the type's description alone left a nearby-food answer at the map's data,
 * and a spawn of its own cost the answer a model round (≈3.5 s), so it goes out with the card.
 */
internal const val WEB_VERIFY_RULE =
    "Verify online with web-verify only where the map's data is not enough to choose: places to eat, stay or visit the " +
        "user asked you to find or recommend (from a place search), or a flight or train the user named. Answer from the " +
        "service data and call spawn_agent with agent=web-verify in the same response as show_card, not as a step of its " +
        "own (task: the items as the service gave them with their addresses, the city, today's date, what to check). Say in " +
        "the reply that you are checking online; do not wait_agent (except for a flight or train whose arrival decides when " +
        "the user sets off: wait, then say when to leave): its result comes back as a report. When it does, put " +
        "what it found on the card (show_card) and speak at most two short sentences: only what changes the choice (a place " +
        "that is closed or not what it seemed, the best pick now); the details stay on the card. Do not read the list or " +
        "the options again. Never for navigating to a place the user named; fuel, charging, parking, toilets or service " +
        "areas; a route, distance or arrival time; device control; what you found by searching the web yourself (check " +
        "that in the same turn instead); or things the user did not ask about."

/**
 * Questions the web answers (owner 2026-10-19: 「这周末有什么活动」本来就该走 search，不用再派核实; 需要印证的同时发出去，
 * 最后综合判断一起发出来). On the bench the agent searched once, answered, then sent web-verify, which searched the
 * same events one per step for 35 s and came back as a second answer; parallel calls of one response run together.
 * Sightseeing routes are here too, not a skill of their own (owner 2026-10-19: 不要一个功能一个 skill): on the bench
 * 「八景都玩一遍」 was filled from memory and routed all eight for one afternoon although two closed before arrival.
 * Weather too (owner 2026-10-25: 「天气直接走deepseek的搜索就行了」): asked with the time, the agent looked for a tool and
 * listed files twice before it searched.
 */
internal const val WEB_SEARCH_RULE =
    "Weather (now, today, tomorrow, the coming days; rain, temperature, wind) is a web search: search in the first step, " +
        "not search_tools for a weather tool and not files, and answer with the place and the day. " +
        "Questions the web answers (events and shows, news, opening times, prices): search. When the results leave several " +
        "items the user would pick from unsure (dates, venue or tickets only from a listing or an old page), search those " +
        "all in one step, several search calls in the same response rather than one per step, then answer once with what " +
        "you checked on the card (show_card). Do not answer before those checks, and do not spawn web-verify for them. " +
        "A route through places with opening hours (sights, shops; for a named set such as 「经典景点」 search which they " +
        "are, not from memory): check today's hours in that same step, estimate each arrival from now with time to look " +
        "around, decide which fit today before you calculate, route today only those, and say what moves to tomorrow " +
        "with an offer to remind; what you say and the route you start must match."

/**
 * When to draw (owner 2026-11-05: 长段的东西生成式 UI 应该更积极): a rule of the run while the conversation is on
 * screen, since in the tool's description alone the model kept a 978-character analysis as text and offered a drawing
 * at its end.
 */
internal const val PAGES_RULE =
    "用户正看着对话页，可以画界面（show_html）：回答会超过三四百字，或有三个以上并列的要点、层次、步骤，或是比较、" +
        "数字、时间线、行程、计划、简报、分析时，先用一句话说结论，再把内容画成页面，finish 只补一两句；不要长篇只用文字，" +
        "也不要问要不要画。一两句能答的、闲聊、要复制的纯文本照常用文字。"

/** On every order or payment tool ([isTransaction][cc.noharry.agent.appserver.tools.isTransaction]): a model said 「先帮你下单了」 before [ActionGate] refused the call. */
internal const val TRANSACTION_HINT =
    "这会真的下单或付款：先用预览把门店、商品、价格告诉用户，用 offer_options 给「下单」（绑定这次调用）和「先不下」，等用户选；" +
        "调用成功之前不要说已经下单。"

/**
 * Where a trip or an outing is going: a reminder to set off, or the cloud assistant, for one days or hours away, and
 * directions only for one to go now; said at the end of plan_manage's description.
 */
internal const val PLAN_CLOSING =
    "推荐的去处、演出或活动离出发还早时（改天，或几个小时以后），回复结尾问要不要到点提醒你出发（连了云端助手的也可以问要不要发给它），" +
        "不要问现在导航过去；马上要去的才问导航。"

/**
 * 网上核实 adds to an answer already spoken: the details belong on the card, the voice keeps to what changed (on the
 * bench a 「汇总」 here outweighed the system rule and the report was read out for 35–45 s).
 */
internal const val VERIFY_REPORT_CLOSING =
    "这是对你刚才那个回答的网上核实。把核实到的细节更新到卡片上（show_card），嘴上最多说两句短话：只说会改变选择的" +
        "（哪家关了或对不上号、现在最推荐哪家），其余留在卡片上；不要再列选项，不要逐家复述，也不要用「好的」「核实回来」之类的话开头。"
