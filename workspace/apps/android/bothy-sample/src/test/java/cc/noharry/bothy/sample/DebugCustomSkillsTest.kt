package cc.noharry.bothy.sample

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.vfs.VfsReadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugCustomSkillsTest {
    private lateinit var context: Context

    @Before
    fun clean() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_runtime_skills", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom-skills").deleteRecursively()
    }

    @Test
    fun theTemplateAndAFullHeaderPass() {
        val template = checkCustomSkill(CUSTOM_SKILL_TEMPLATE, emptySet())
        assertEquals(emptyList<String>(), template.errors)
        assertEquals("my-skill", template.name)

        val full = checkCustomSkill(
            "---\r\nname: \"trip-notes\"\r\ndescription: \"记下行程里的要点，用户说「记一下」时用。\"\r\nlicense: MIT\r\n" +
                "metadata:\r\n  author: me\r\n  version: \"1.0\"\r\nscenes: [\"asr\", \"debug-console\"]\r\n---\r\n\r\n# 记要点\r\n先复述再记。\r\n",
            emptySet(),
        )
        assertEquals(emptyList<String>(), full.errors)
        assertEquals("trip-notes", full.name)
        assertEquals("记下行程里的要点，用户说「记一下」时用。", full.description)
        assertFalse("saved with LF line ends", '\r' in full.normalized)
    }

    @Test
    fun eachBrokenRuleIsNamed() {
        fun errors(text: String, taken: Set<String> = emptySet()) = checkCustomSkill(text, taken).errors.joinToString("\n")
        fun skill(header: String, body: String = "# 做法\n照做。") = "---\n$header\n---\n$body\n"

        assertTrue(errors("").contains("空的"))
        assertTrue(errors("# 只有正文").contains("第一行要是 ---"))
        assertTrue(errors("---\nname: a\ndescription: 做一件事的时候用\n# 正文").contains("没有用一行 --- 结束"))
        assertTrue(errors(skill("description: 做一件事的时候用")).contains("缺少 name"))
        listOf("My-Skill", "my_skill", "-skill", "skill-", "my--skill", "a".repeat(65)).forEach { name ->
            assertTrue(name, errors(skill("name: $name\ndescription: 做一件事的时候用")).contains("只能用小写英文字母"))
        }
        assertTrue(errors(skill("name: android-use\ndescription: 做一件事的时候用"), builtinRuntimeSkillIds()).contains("已经有叫「android-use」"))
        assertTrue(errors(skill("name: a")).contains("缺少 description"))
        assertTrue(errors(skill("name: a\ndescription: >\n  做一件事的时候用")).contains("同一行"))
        assertTrue(errors(skill("name: a\ndescription: 用 <tool> 的时候用")).contains("尖括号"))
        assertTrue(errors(skill("name: a\ndescription: '做一件事的时候用'")).contains("单引号"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用\nversion: 1")).contains("不认识的字段「version」"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用\nname: b")).contains("写了两遍"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用\nmetadata: x")).contains("metadata 下面"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用\nscenes: asr")).contains("scenes 要写成"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用\nnope")).contains("不是「字段: 值」"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用", body = "\n")).contains("正文是空的"))
        assertTrue(errors(skill("name: a\ndescription: 做一件事的时候用", body = "x\n".repeat(70_000))).contains("64 KB"))
        val sections = skill("name: a\ndescription: 做一件事的时候用", body = "<!-- a2a -->\n发给助手\n<!-- /a2a -->\n<!-- /a2a -->")
        assertTrue(errors(sections).contains("没有成对出现"))
        // An opening never closed is an ordinary comment.
        assertEquals("", errors(skill("name: a\ndescription: 做一件事的时候用", body = "<!-- todo -->\n照做。")))
    }

    @Test
    fun warningsDoNotKeepASkillFromBeingSaved() {
        val check = checkCustomSkill("---\nname: a\ndescription: 短\n---\n" + "照做。\n".repeat(600), emptySet())
        assertEquals(emptyList<String>(), check.errors)
        assertEquals(2, check.warnings.size)
    }

    @Test
    fun aSavedSkillLoadsOnReachesTheModelAndCanBeRenamedSwitchedAndDeleted() = runBlocking {
        val text = "---\nname: trip-notes\ndescription: 记下行程要点，用户说「记一下」时用。\n---\n\n# 记要点\n先复述再记。\n"
        assertNull(DebugCustomSkills.save(context, text, editing = null))
        val added = loadDebugRuntimeSkills(context).single { it.id == "trip-notes" }
        assertTrue("on when added", added.enabled)
        assertEquals("记下行程要点，用户说「记一下」时用。", added.description)
        assertFalse("read when needed, not preloaded", added.preload)
        val vfs = debugWorkspaceVfs(context, File(context.cacheDir, "custom-skills-vfs"))
        val found = vfs.read("/.agents/skills/trip-notes/SKILL.md", 0, 20_000) as VfsReadResult.Found
        assertTrue(found.text.contains("先复述再记。"))

        assertTrue(DebugCustomSkills.save(context, text, editing = null)!!.contains("已经有叫「trip-notes」"))
        saveDebugRuntimeSkill(context, "trip-notes", false)
        assertNull(DebugCustomSkills.save(context, text.replace("trip-notes", "trip-memo"), editing = "trip-notes"))
        val ids = loadDebugRuntimeSkills(context).map { it.id }
        assertFalse("trip-notes" in ids)
        assertFalse("the switch goes with the rename", loadDebugRuntimeSkills(context).single { it.id == "trip-memo" }.enabled)

        assertTrue(DebugCustomSkills.delete(context, "trip-memo"))
        assertFalse(loadDebugRuntimeSkills(context).any { it.file != null })
        assertFalse("built-in skills are not deleted here", DebugCustomSkills.delete(context, "android-use"))
    }
}
