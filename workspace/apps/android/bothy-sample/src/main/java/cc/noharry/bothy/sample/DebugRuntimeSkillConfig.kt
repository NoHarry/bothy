package cc.noharry.bothy.sample

import android.content.Context
import cc.noharry.bothy.vfs.VfsMemorySource

internal data class DebugRuntimeSkill(
    val id: String,
    val label: String,
    val description: String,
    val assetPath: String,
    val enabled: Boolean,
    val preload: Boolean = false,
    val manifestPath: String? = null,
    /** Loaded only while the 操作屏幕 tools are mounted, so it never describes tools the model cannot see. */
    val requiresAndroidUse: Boolean = false,
    /** Loaded only while an A2A profile is enabled, i.e. while some `a2a_message_send` tool is mounted. */
    val requiresA2aDelegate: Boolean = false,
    /** Never in a subagent's runtime: the workflow is the main agent's (it spawns, shows cards, plans or delegates). */
    val mainAgentOnly: Boolean = false,
    /** A skill added on the device ([DebugCustomSkills]): its SKILL.md, in place of [assetPath]. */
    val file: java.io.File? = null,
)

/** The built-in skills, then those added on the device, each switched as last saved. */
internal fun loadDebugRuntimeSkills(context: Context): List<DebugRuntimeSkill> {
    val preferences = context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE)
    return (RUNTIME_SKILLS + DebugCustomSkills.load(context)).map { it.copy(enabled = preferences.getBoolean(it.id, it.enabled)) }
}

internal fun saveDebugRuntimeSkill(context: Context, id: String, enabled: Boolean) {
    require(RUNTIME_SKILLS.any { it.id == id } || DebugCustomSkills.load(context).any { it.id == id }) { "Unknown runtime skill." }
    context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, enabled).apply()
}

internal fun builtinRuntimeSkillIds(): Set<String> = RUNTIME_SKILLS.map { it.id }.toSet()

/** A custom skill renamed keeps its switch. */
internal fun renameDebugRuntimeSkillChoice(context: Context, from: String, to: String) {
    val preferences = context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE)
    val editor = preferences.edit().remove(from)
    if (preferences.contains(from)) editor.putBoolean(to, preferences.getBoolean(from, true))
    editor.apply()
}

internal fun forgetDebugRuntimeSkillChoice(context: Context, id: String) {
    context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
}

/** Line endings are normalized so a Windows (CRLF) checkout builds the same prompt bytes as the repo. */
internal fun debugRuntimeSkillText(context: Context, skill: DebugRuntimeSkill): String =
    (skill.file?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
        ?: context.assets.open(skill.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
        .replace("\r\n", "\n")
        .withSections(debugSkillSections(context))

/** What a skill's conditional sections may depend on: `a2a` while an A2A profile is enabled, `agent:<id>` per subagent type. */
internal fun debugSkillSections(context: Context): Set<String> =
    (if (DebugA2aModuleConfig.hasEnabledProfiles(context)) setOf(A2A_SECTION_KEY) else emptySet()) + debugSubagentKindSections(context)

/**
 * A skill's `<!-- a2a -->` … `<!-- /a2a -->` lines are kept only while the user's own agent is connected (an enabled
 * A2A profile; owner 2026-10-17: 接入了类似 grok 这种个人助手的时候，才问要不要发给个人助手), its `<!-- no-a2a -->` lines
 * only while not. A step of another skill reaches the model where it acts: in a different skill, and then as an extra
 * paragraph under the step, the trip's backup offer was skipped; a step written whole for each case is followed.
 */
internal fun String.withA2aSections(connected: Boolean): String = withSections(if (connected) setOf(A2A_SECTION_KEY) else emptySet())

/**
 * A skill's `<!-- key -->` … `<!-- /key -->` lines are kept while [on] holds the key, its `<!-- no-key -->` lines while
 * not: `a2a` (above) and `agent:<id>`, so a step can name a subagent type while it is switched on and spell the whole
 * task out while it is off.
 */
internal fun String.withSections(on: Set<String>): String =
    SECTION.replace(this) { section ->
        val (negated, key, body) = section.destructured
        if ((key in on) != (negated == "no-")) body else ""
    }

private const val A2A_SECTION_KEY = "a2a"
private val SECTION = Regex("""<!-- (no-|)([a-z0-9:-]+) -->\n?(.*?)<!-- /\1\2 -->\n?""", RegexOption.DOT_MATCHES_ALL)

internal fun selectedDebugSkillFiles(context: Context, skills: List<DebugRuntimeSkill>): Map<String, String> =
    skills.filter { it.enabled }.associate { "${it.id}/SKILL.md" to debugRuntimeSkillText(context, it) }

internal fun debugSkillSource(context: Context, skills: List<DebugRuntimeSkill>): VfsMemorySource =
    VfsMemorySource(selectedDebugSkillFiles(context, skills))

internal fun debugPreloadedSkillPrompt(context: Context, skills: List<DebugRuntimeSkill>, input: String = ""): String {
    return debugPreloadedSkillBodies(context, skills, input).joinToString("\n\n") { (id, body) ->
        "Active runtime skill: $id\n$body"
    }
}

internal fun debugPreloadedSkillBodies(context: Context, skills: List<DebugRuntimeSkill>, input: String): List<Pair<String, String>> {
    val matched = selectedWorkflowSkills(context, skills, input).map { it.skill.id }.toSet()
    val androidUse = debugAndroidUseReady(context)
    val delegate = DebugA2aModuleConfig.hasEnabledProfiles(context)
    return skills.filter {
        it.enabled && it.preload && (it.manifestPath == null || it.id in matched) &&
            (androidUse || !it.requiresAndroidUse) && (delegate || !it.requiresA2aDelegate)
    }.map {
        it.id to debugRuntimeSkillText(context, it)
    }
}

private const val SKILL_PREFS = "debug_runtime_skills"

/**
 * Workflow skills default on: a matched skill pre-activates its tools before the first model request, which
 * removes pure discovery rounds and keeps the tool list stable for the prompt cache. Saved choices still win.
 */
private val RUNTIME_SKILLS = listOf(
    DebugRuntimeSkill(
        "debug_voice_progress", "Voice progress", "UI、ASR、A2A 共用的中文播报进度；启用后随请求加载。",
        "runtime-workspace/skills/debug_voice_progress/SKILL.md", enabled = true, preload = true,
    ),
    DebugRuntimeSkill(
        "mcp-task-orchestration", "MCP task orchestration", "根据当前工具能力发现、编排依赖并核对用户约束，适用于不同业务领域。",
        "runtime-workspace/skills/mcp-task-orchestration/SKILL.md", enabled = false, preload = true,
    ),
    DebugRuntimeSkill(
        "travel-planning", "Travel planning", "要规划几天的旅行时加载：三路子 agent 查交通、住宿和每天的去处，汇总成按天分组、带照片的行程卡片，再问要不要设提醒。",
        "runtime-workspace/skills/travel-planning/SKILL.md", enabled = true, preload = true,
        manifestPath = "runtime-workspace/skills/travel-planning/runtime.json", mainAgentOnly = true,
    ),
    DebugRuntimeSkill(
        "personal-agent-delegation", "Personal agent delegation", "启用了 A2A 委托目标时随请求加载：哪些事交给用户自己的 agent、做了收不回的事先确认、转告后台的【委托回报】。",
        "runtime-workspace/skills/personal-agent-delegation/SKILL.md", enabled = true, preload = true, requiresA2aDelegate = true,
        mainAgentOnly = true,
    ),
    DebugRuntimeSkill(
        "android-use", "Android Use", "操作屏幕已连接时随请求加载：领域工具优先、一步一个动作、核对结果、不可撤销操作先确认。",
        "runtime-workspace/skills/android-use/SKILL.md", enabled = true, preload = true, requiresAndroidUse = true,
    ),
)
