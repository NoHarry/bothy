package cc.noharry.bothy.app.skills
import android.content.Context
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.a2a.A2aModuleConfig
import cc.noharry.bothy.app.androiduse.androidUseReady

internal data class RuntimeSkill(
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
    /** A skill added on the device ([CustomSkills]): its SKILL.md, in place of [assetPath]. */
    val file: java.io.File? = null,
)

/** The built-in skills, then those added on the device, each switched as last saved. */
internal fun loadRuntimeSkills(context: Context): List<RuntimeSkill> {
    val preferences = context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE)
    return (builtinRuntimeSkills(context) + CustomSkills.load(context))
        .map { it.copy(enabled = preferences.getBoolean(it.id, it.enabled)) }
}

internal fun saveRuntimeSkill(context: Context, id: String, enabled: Boolean) {
    val known = id in builtinRuntimeSkillIds(context) || CustomSkills.load(context).any { it.id == id }
    require(known) { "Unknown runtime skill." }
    context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, enabled).apply()
}

internal fun builtinRuntimeSkillIds(context: Context): Set<String> = builtinRuntimeSkills(context).map { it.id }.toSet()

/** A custom skill renamed keeps its switch. */
internal fun renameRuntimeSkillChoice(context: Context, from: String, to: String) {
    val preferences = context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE)
    val editor = preferences.edit().remove(from)
    if (preferences.contains(from)) editor.putBoolean(to, preferences.getBoolean(from, true))
    editor.apply()
}

internal fun forgetRuntimeSkillChoice(context: Context, id: String) {
    context.getSharedPreferences(SKILL_PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
}

/** Line endings are normalized so a Windows (CRLF) checkout builds the same prompt bytes as the repo. */
internal fun runtimeSkillText(context: Context, skill: RuntimeSkill): String =
    (skill.file?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
        ?: context.assets.open(skill.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
        .replace("\r\n", "\n")
        .withSections(skillSections(context))

/** What a skill's conditional sections may depend on: `a2a` while an A2A profile is enabled, `agent:<id>` per subagent type. */
internal fun skillSections(context: Context): Set<String> =
    (if (A2aModuleConfig.hasEnabledProfiles(context)) setOf(A2A_SECTION_KEY) else emptySet()) + subagentKindSections(context)

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

internal fun selectedSkillFiles(context: Context, skills: List<RuntimeSkill>): Map<String, String> =
    skills.filter { it.enabled }.associate { "${it.id}/SKILL.md" to runtimeSkillText(context, it) }

internal fun appSkillSource(context: Context, skills: List<RuntimeSkill>): VfsMemorySource =
    VfsMemorySource(selectedSkillFiles(context, skills))

internal fun preloadedSkillPrompt(context: Context, skills: List<RuntimeSkill>, input: String = ""): String {
    return preloadedSkillBodies(context, skills, input).joinToString("\n\n") { (id, body) ->
        "Active runtime skill: $id\n$body"
    }
}

internal fun preloadedSkillBodies(context: Context, skills: List<RuntimeSkill>, input: String): List<Pair<String, String>> {
    val matched = selectedWorkflowSkills(context, skills, input).map { it.skill.id }.toSet()
    val androidUse = androidUseReady(context)
    val delegate = A2aModuleConfig.hasEnabledProfiles(context)
    return skills.filter {
        it.enabled && it.preload && (it.manifestPath == null || it.id in matched) &&
            (androidUse || !it.requiresAndroidUse) && (delegate || !it.requiresA2aDelegate)
    }.map {
        it.id to runtimeSkillText(context, it)
    }
}

private const val SKILL_PREFS = "debug_runtime_skills"

/**
 * Workflow skills default on: a matched skill pre-activates its tools before the first model request, which
 * removes pure discovery rounds and keeps the tool list stable for the prompt cache. Saved choices still win.
 * Their names and summaries, shown in Settings, are in [context]'s language.
 */
private fun builtinRuntimeSkills(context: Context): List<RuntimeSkill> = listOf(
    RuntimeSkill(
        "debug_voice_progress", context.getString(R.string.skills_voice_progress),
        context.getString(R.string.skills_voice_progress_about),
        "runtime-workspace/skills/debug_voice_progress/SKILL.md", enabled = true, preload = true,
    ),
    RuntimeSkill(
        "mcp-task-orchestration", context.getString(R.string.skills_task_orchestration),
        context.getString(R.string.skills_task_orchestration_about),
        "runtime-workspace/skills/mcp-task-orchestration/SKILL.md", enabled = false, preload = true,
    ),
    RuntimeSkill(
        "travel-planning", context.getString(R.string.skills_travel_planning),
        context.getString(R.string.skills_travel_planning_about),
        "runtime-workspace/skills/travel-planning/SKILL.md", enabled = true, preload = true,
        manifestPath = "runtime-workspace/skills/travel-planning/runtime.json", mainAgentOnly = true,
    ),
    RuntimeSkill(
        "personal-agent-delegation", context.getString(R.string.skills_delegation),
        context.getString(R.string.skills_delegation_about),
        "runtime-workspace/skills/personal-agent-delegation/SKILL.md", enabled = true, preload = true, requiresA2aDelegate = true,
        mainAgentOnly = true,
    ),
    RuntimeSkill(
        "android-use", context.getString(R.string.skills_android_use),
        context.getString(R.string.skills_android_use_about),
        "runtime-workspace/skills/android-use/SKILL.md", enabled = true, preload = true, requiresAndroidUse = true,
    ),
    // In the catalog, read when the model needs it: playground_open's own description covers the common asks; the
    // skill adds what to do when a system dialog keeps it from the front or the Playground is not installed.
    RuntimeSkill(
        "playground", context.getString(R.string.skills_playground),
        context.getString(R.string.skills_playground_about),
        "runtime-workspace/skills/playground/SKILL.md", enabled = true,
    ),
)
