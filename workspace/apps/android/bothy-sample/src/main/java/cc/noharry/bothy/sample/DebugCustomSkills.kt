package cc.noharry.bothy.sample

import android.content.Context
import java.io.File

/**
 * Skills added on the device (owner 2026-10-20: 「skill那也加一个可以手动添加的吧，可以输入skill内容，但是要检查格式是否合规」):
 * each is a SKILL.md under files/custom-skills/<name>/, checked by [checkCustomSkill] before it is saved. They load like
 * a built-in skill that is not preloaded: name and description in the skill catalog of the system prompt, the whole
 * file read when the model needs it (/.agents/skills/<name>/SKILL.md). On when added; Settings switches them as any other.
 */
internal object DebugCustomSkills {
    fun load(context: Context): List<DebugRuntimeSkill> = root(context).listFiles().orEmpty()
        .mapNotNull { dir -> File(dir, SKILL_FILE).takeIf(File::isFile) }
        .sortedBy { it.parentFile!!.name }
        .map { file ->
            val name = file.parentFile!!.name
            val description = checkCustomSkill(file.readText(Charsets.UTF_8), emptySet()).description.orEmpty()
            DebugRuntimeSkill(name, name, description, assetPath = "", enabled = true, file = file)
        }

    fun text(context: Context, name: String): String? =
        File(root(context), "$name/$SKILL_FILE").takeIf(File::isFile)?.readText(Charsets.UTF_8)

    /** Names a new skill (or one renamed from [editing]) may not take: the built-in ones and the other custom ones. */
    fun takenNames(context: Context, editing: String?): Set<String> =
        builtinRuntimeSkillIds() + load(context).map { it.id }.filter { it != editing }

    /** Saves [text] as a new skill, or in place of [editing] (renamed if its name changed); null when saved, else why not. */
    fun save(context: Context, text: String, editing: String?): String? {
        val check = checkCustomSkill(text, takenNames(context, editing))
        if (check.errors.isNotEmpty()) return check.errors.joinToString("\n")
        val name = checkNotNull(check.name)
        val dir = File(root(context), name)
        val saved = runCatching {
            dir.mkdirs()
            File(dir, SKILL_FILE).writeText(check.normalized, Charsets.UTF_8)
        }
        if (saved.isFailure) return "无法保存：${saved.exceptionOrNull()?.message}"
        if (editing != null && editing != name) {
            renameDebugRuntimeSkillChoice(context, editing, name)
            File(root(context), editing).deleteRecursively()
        }
        return null
    }

    fun delete(context: Context, name: String): Boolean {
        if (name !in load(context).map { it.id }) return false
        forgetDebugRuntimeSkillChoice(context, name)
        return File(root(context), name).deleteRecursively()
    }

    private fun root(context: Context) = File(context.filesDir, CUSTOM_SKILLS_DIR)
}

/** What [checkCustomSkill] found: [errors] keep a skill from being saved, [warnings] do not. */
internal data class DebugSkillCheck(
    val name: String?,
    val description: String?,
    val errors: List<String>,
    val warnings: List<String>,
    /** The text as saved: LF line ends, no byte-order mark, one trailing newline. */
    val normalized: String,
)

/**
 * Whether [text] is a SKILL.md the runtime reads as meant: the Agent Skills layout (a YAML header between `---` lines
 * with `name` and `description`, then the instructions), held to what this runtime's line-by-line header reader takes
 * (SkillRegistry: one `key: value` per line, double quotes at most), and named apart from [taken].
 */
internal fun checkCustomSkill(text: String, taken: Set<String>): DebugSkillCheck {
    val normalized = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trimEnd() + "\n"
    if (text.isBlank()) return DebugSkillCheck(null, null, listOf("内容是空的。"), emptyList(), normalized)
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    if (normalized.toByteArray(Charsets.UTF_8).size > MAX_SKILL_BYTES) errors += "超过 64 KB：运行时只读得到前 64 KB。"
    val lines = normalized.lines()
    if (lines.first().trimEnd() != FENCE) {
        errors += "第一行要是 ---：开头是 YAML 头，写 name 和 description，再用一行 --- 结束。"
        return DebugSkillCheck(null, null, errors, warnings, normalized)
    }
    val close = lines.drop(1).indexOfFirst { it.trimEnd() == FENCE }.takeIf { it >= 0 }?.plus(1)
    if (close == null) {
        errors += "YAML 头没有用一行 --- 结束。"
        return DebugSkillCheck(null, null, errors, warnings, normalized)
    }
    val fields = readHeader(lines.subList(1, close), errors)
    val name = fields["name"]?.let { unquoted(it, "name", errors) }
    val validName = name?.let { checkName(it, taken, errors) }
    if (name == null) errors += "缺少 name。"
    val description = fields["description"]?.let { unquoted(it, "description", errors) }
    checkDescription(description, errors, warnings)
    fields["metadata"]?.takeIf(String::isNotBlank)?.let { errors += "metadata 下面要换行、缩进写「键: 值」。" }
    fields["scenes"]?.takeUnless { SCENES.matches(it) }?.let { errors += "scenes 要写成 [\"场景一\", \"场景二\"]。" }
    val body = lines.drop(close + 1)
    if (body.all(String::isBlank)) errors += "--- 下面的正文是空的：写上要模型怎么做。"
    if (body.size > LONG_BODY_LINES) warnings += "正文有 ${body.size} 行，超过 $LONG_BODY_LINES 行，建议精简或拆成几个技能。"
    checkSections(body.joinToString("\n"), errors)
    return DebugSkillCheck(validName, description, errors, warnings, normalized)
}

/** The header's top-level `key: value` lines; indented lines only under a key with no value (metadata's entries). */
private fun readHeader(header: List<String>, errors: MutableList<String>): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    var nested = false
    header.forEachIndexed { index, line ->
        val number = index + 2
        when {
            line.isBlank() || line.trimStart().startsWith("#") -> Unit
            line.first().isWhitespace() -> if (!nested) errors += "第 $number 行：缩进的内容只能写在 metadata 这样空着值的字段下面。"
            else -> {
                val match = FIELD.matchEntire(line)
                if (match == null) {
                    errors += "第 $number 行不是「字段: 值」的格式。"
                    nested = false
                    return@forEachIndexed
                }
                val (key, value) = match.destructured
                if (key in fields) errors += "字段 $key 写了两遍。"
                if (key !in HEADER_FIELDS) errors += "第 $number 行：不认识的字段「$key」，只能用 ${HEADER_FIELDS.joinToString("、")}。"
                fields[key] = value.trim()
                nested = value.isBlank()
            }
        }
    }
    return fields
}

/** Double quotes may wrap a value; single ones stay in it for this runtime's header reader. */
private fun unquoted(value: String, key: String, errors: MutableList<String>): String {
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) return value.substring(1, value.length - 1).trim()
    if (value.startsWith('\'')) errors += "$key 用双引号或不加引号：运行时不认单引号。"
    return value
}

private fun checkName(name: String, taken: Set<String>, errors: MutableList<String>): String? {
    when {
        name.isEmpty() -> errors += "name 是空的。"
        name.length > MAX_NAME_CHARS || !NAME.matches(name) ->
            errors += "name「$name」只能用小写英文字母、数字和 -，不能以 - 开头或结尾，不能连着写 --，最长 $MAX_NAME_CHARS 个字符。"
        name in taken -> errors += "已经有叫「$name」的技能，换个名字（改自己加的技能请在列表里点编辑）。"
        else -> return name
    }
    return null
}

private fun checkDescription(description: String?, errors: MutableList<String>, warnings: MutableList<String>) {
    when {
        description == null -> errors += "缺少 description：一句话写清做什么、什么时候用，模型靠它决定要不要读这个技能。"
        description.isEmpty() || description in BLOCK_SCALARS -> errors += "description 要写在「description:」同一行：运行时按行读。"
        description.length > MAX_DESCRIPTION_CHARS -> errors += "description 超过 $MAX_DESCRIPTION_CHARS 个字符。"
        '<' in description || '>' in description -> errors += "description 里不能有尖括号 < >。"
        description.length < SHORT_DESCRIPTION_CHARS -> warnings += "description 太短：写清做什么、什么时候用，模型才知道什么时候读它。"
    }
}

/**
 * `<!-- key -->` … `<!-- /key -->` sections ([withSections]) come in pairs: an extra opening or closing stays in the text
 * as is, and its condition does not apply. An opening never closed is an ordinary comment.
 */
private fun checkSections(body: String, errors: MutableList<String>) {
    val opened = SECTION_OPEN.findAll(body).map { it.groupValues[1] }.groupingBy { it }.eachCount()
    val closed = SECTION_CLOSE.findAll(body).map { it.groupValues[1] }.groupingBy { it }.eachCount()
    closed.keys.filter { opened[it] != closed[it] }.forEach { key ->
        errors += "「<!-- $key -->」和「<!-- /$key -->」没有成对出现。"
    }
}

/** A new skill's starting text. */
internal const val CUSTOM_SKILL_TEMPLATE = """---
name: my-skill
description: 一句话写清这个技能做什么、什么时候用（模型靠它决定要不要读）。
---

# 技能标题

写给小博的做法：先做什么、再做什么，什么时候要先问用户。
"""

private const val CUSTOM_SKILLS_DIR = "custom-skills"
private const val SKILL_FILE = "SKILL.md"
private const val FENCE = "---"
/** SkillRegistry's MAX_SKILL_MD_BYTES. */
private const val MAX_SKILL_BYTES = 64 * 1024
private const val MAX_NAME_CHARS = 64
private const val MAX_DESCRIPTION_CHARS = 1024
private const val SHORT_DESCRIPTION_CHARS = 8
private const val LONG_BODY_LINES = 500
private val HEADER_FIELDS = listOf("name", "description", "license", "compatibility", "metadata", "allowed-tools", "scenes")
private val BLOCK_SCALARS = setOf(">", "|", ">-", "|-", ">+", "|+")
private val FIELD = Regex("""([A-Za-z][A-Za-z0-9_-]*):(?:\s+(.*)|\s*)""")
private val NAME = Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
private val SCENES = Regex("""\[\s*("[^"]+"\s*(,\s*"[^"]+"\s*)*)?]""")
private val SECTION_OPEN = Regex("""<!-- ((?:no-)?[a-z0-9:-]+) -->""")
private val SECTION_CLOSE = Regex("""<!-- /((?:no-)?[a-z0-9:-]+) -->""")
