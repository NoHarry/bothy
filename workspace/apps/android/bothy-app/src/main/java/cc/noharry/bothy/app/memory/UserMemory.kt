package cc.noharry.bothy.app.memory
import android.content.Context
import android.util.Log
import cc.noharry.bothy.app.console.saveFileAtomically
import cc.noharry.bothy.app.console.setAsideUnreadable
import cc.noharry.bothy.app.console.singleLine
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The kinds of things 小博 remembers about the user, in the order 设置 → 记忆 shows them. */
internal enum class MemoryCategory(val id: String, val label: String, val hint: String) {
    IDENTITY("identity", "称呼与身份", "怎么称呼你、职业、生日"),
    PLACES("places", "常去地点", "家、公司、常去的店"),
    HOME("home", "设备与家居偏好", "温度、灯光、音量、亮度"),
    MEDIA("media", "娱乐", "喜欢的歌手、电台、节目"),
    TRAVEL("travel", "出行习惯", "通勤时间、路线偏好、充电加油"),
    PEOPLE("people", "家人朋友", "家人、朋友和他们的事"),
    OTHER("other", "其他", "别的长期信息"),
    ;

    companion object {
        fun byId(id: String?): MemoryCategory? = entries.firstOrNull { it.id == id }
    }
}

/** Who wrote a memory: 小博, from the user's own words, or someone typing on 设置 → 记忆. */
internal enum class MemorySource(val id: String, val label: String) {
    SAID("said", "你说的"),
    TYPED("typed", "手动添加"),
    ;

    companion object {
        fun byId(id: String?): MemorySource = entries.firstOrNull { it.id == id } ?: SAID
    }
}

/** One thing 小博 remembers about the user; [said] keeps the words it was told, when 小博 wrote it. */
internal data class UserMemory(
    val id: String,
    val category: MemoryCategory,
    val content: String,
    val source: MemorySource,
    val said: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** How a write went: the memory as stored, or why nothing changed, in words for the user. */
internal sealed interface MemoryWrite {
    data class Saved(val memory: UserMemory, val created: Boolean) : MemoryWrite
    data class Removed(val memory: UserMemory) : MemoryWrite
    data class Refused(val reason: String) : MemoryWrite
}

/**
 * The memories in one file, written whole on every change. Only what the user said about themself or typed here is
 * kept: no guesses, no passwords or codes, no card or ID numbers ([memoryContentError]). [MAX_MEMORIES] in all and
 * [MAX_PER_CATEGORY] per kind, so every one of them fits the runtime's context facts.
 */
internal class UserMemoryBook(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var memories: List<UserMemory> = load()

    @Synchronized fun all(): List<UserMemory> = memories

    @Synchronized
    fun remember(category: MemoryCategory, content: String, source: MemorySource, said: String? = null): MemoryWrite {
        val text = singleLine(content)
        memoryContentError(text)?.let { return MemoryWrite.Refused(it) }
        memories.firstOrNull { it.category == category && sameWords(it.content, text) }?.let { return MemoryWrite.Saved(it, created = false) }
        if (memories.size >= MAX_MEMORIES) return MemoryWrite.Refused("已经记了 $MAX_MEMORIES 件事，记满了：先在 设置 → 记忆 删掉一些")
        if (memories.count { it.category == category } >= MAX_PER_CATEGORY) {
            return MemoryWrite.Refused("「${category.label}」已经记了 $MAX_PER_CATEGORY 件事：先改掉或删掉一条旧的")
        }
        val stamp = now()
        val memory = UserMemory(newId(), category, text, source, keptWords(said), stamp, stamp)
        return save(memories + memory) ?: MemoryWrite.Saved(memory, created = true)
    }

    /** New words (and, if given, a new kind) for the memory [id]; the source becomes whoever changed it. */
    @Synchronized
    fun update(id: String, content: String, category: MemoryCategory?, source: MemorySource, said: String? = null): MemoryWrite {
        val old = memories.firstOrNull { it.id == id } ?: return MemoryWrite.Refused("没有这条记忆（$id）")
        val text = singleLine(content)
        memoryContentError(text)?.let { return MemoryWrite.Refused(it) }
        val kind = category ?: old.category
        if (kind != old.category && memories.count { it.category == kind } >= MAX_PER_CATEGORY) {
            return MemoryWrite.Refused("「${kind.label}」已经记了 $MAX_PER_CATEGORY 件事：先改掉或删掉一条旧的")
        }
        val memory = old.copy(
            category = kind,
            content = text,
            source = source,
            said = if (source == MemorySource.SAID) keptWords(said) ?: old.said else old.said,
            updatedAtMillis = now(),
        )
        return save(memories.map { if (it.id == id) memory else it }) ?: MemoryWrite.Saved(memory, created = false)
    }

    @Synchronized
    fun forget(id: String): MemoryWrite {
        val old = memories.firstOrNull { it.id == id } ?: return MemoryWrite.Refused("没有这条记忆（$id）")
        return save(memories.filterNot { it.id == id }) ?: MemoryWrite.Removed(old)
    }

    /** False when the disk took nothing (the memories stay). */
    @Synchronized fun clear(): Boolean = save(emptyList()) == null

    /** The user's words as said, unless they hold what is never kept: 「门禁密码是3721」 must not stay as the 原话. */
    private fun keptWords(said: String?): String? =
        said?.let(::singleLine)?.takeIf { sensitiveTextError(it) == null }?.take(MAX_SAID_CHARS)?.ifEmpty { null }

    /** Null once saved; a refusal when the disk takes nothing (full), so neither 小博 nor the page crashes the app. */
    private fun save(next: List<UserMemory>): MemoryWrite.Refused? = try {
        saveFileAtomically(file, encodeMemories(next))
        memories = next
        null
    } catch (e: IOException) {
        Log.w(TAG, "memories not saved: ${e.javaClass.simpleName}")
        MemoryWrite.Refused("没存上：设备存储出错了")
    }

    /** A file that cannot be read is set aside rather than written over by the next save. */
    private fun load(): List<UserMemory> =
        runCatching { if (file.isFile) decodeMemories(file.readText()) else emptyList() }
            .onFailure {
                Log.w(TAG, "memories unreadable, set aside: ${it.javaClass.simpleName}")
                setAsideUnreadable(file)
            }
            .getOrDefault(emptyList())

    private fun newId(): String {
        while (true) {
            val id = "m" + UUID.randomUUID().toString().replace("-", "").take(ID_HEX_CHARS)
            if (memories.none { it.id == id }) return id
        }
    }
}

/**
 * 设置 → 记忆 and 小博's `user_memory` tool share one book under `files/user-memory/`, which Clear runtime data does not
 * touch (the page's 全部清除 does). Contents never go to the log: only ids and kinds.
 */
internal object UserMemoryStore {
    private val mutable = MutableStateFlow<List<UserMemory>>(emptyList())
    val memories: StateFlow<List<UserMemory>> = mutable

    @Volatile private var book: UserMemoryBook? = null

    /** Reads the file off the main thread at start, so the page and the first run find it loaded. */
    fun init(context: Context) {
        val app = context.applicationContext
        Thread({ book(app) }, "user-memory").start()
    }

    fun book(context: Context): UserMemoryBook = book ?: synchronized(this) {
        book ?: UserMemoryBook(File(context.applicationContext.filesDir, MEMORY_FILE)).also {
            book = it
            mutable.value = it.all()
        }
    }

    /**
     * A change through [book]'s writes, published to the page under the book's lock, so of two writes at once (小博
     * remembering two things in parallel) the page ends on the later; logged without the words.
     */
    fun write(context: Context, change: (UserMemoryBook) -> MemoryWrite): MemoryWrite {
        val target = book(context)
        val result = synchronized(target) { change(target).also { mutable.value = target.all() } }
        when (result) {
            is MemoryWrite.Saved -> Log.i(TAG, "${if (result.created) "remembered" else "kept"} ${result.memory.id} (${result.memory.category.id}, ${result.memory.source.id})")
            is MemoryWrite.Removed -> Log.i(TAG, "forgot ${result.memory.id} (${result.memory.category.id})")
            is MemoryWrite.Refused -> Log.i(TAG, "refused a write")
        }
        return result
    }

    /** False when the disk took nothing. */
    fun clear(context: Context): Boolean {
        val target = book(context)
        val cleared = synchronized(target) { target.clear().also { mutable.value = target.all() } }
        Log.i(TAG, if (cleared) "cleared every memory" else "memories not cleared")
        return cleared
    }

    /**
     * What 小博 uses now: nothing while 设置 → 记忆's switch is off, and at most the [MAX_MEMORIES] last changed, so they
     * fit the context facts even from a book written when more were allowed.
     */
    fun inUse(context: Context): List<UserMemory> = if (userMemoryEnabled(context)) memoriesInUse(book(context).all()) else emptyList()
}

/** [all] in their order, but only the [MAX_MEMORIES] last changed. */
internal fun memoriesInUse(all: List<UserMemory>): List<UserMemory> {
    if (all.size <= MAX_MEMORIES) return all
    val kept = all.sortedByDescending { it.updatedAtMillis }.take(MAX_MEMORIES).mapTo(HashSet()) { it.id }
    return all.filter { it.id in kept }
}

/** Why [content] cannot be a memory, or null when it can. */
internal fun memoryContentError(content: String): String? = when {
    content.isBlank() -> "内容是空的"
    content.length > MAX_MEMORY_CHARS -> "太长了：一条记忆不超过 $MAX_MEMORY_CHARS 个字"
    else -> sensitiveTextError(content)
}

/** Why [text] holds what is never kept (a password or code, a card or ID number), or null. 计划 keeps the 原话 out too. */
internal fun sensitiveTextError(text: String): String? = when {
    SECRET_WORDS.any { it in text } -> "不记密码、验证码这类信息"
    LONG_NUMBER.containsMatchIn(text) -> "不记身份证号、银行卡号这类长号码"
    else -> null
}

/** The memories as a few lines for a chat prompt (豆包's persona, 陪聊), or "" when there are none. */
internal fun memoryProfileText(memories: List<UserMemory>): String {
    if (memories.isEmpty()) return ""
    val lines = memories.groupBy { it.category }.toSortedMap().map { (category, items) ->
        "- ${category.label}：" + items.joinToString("；") { it.content }
    }
    return "关于用户（用户亲口告诉小博的，自然地用上，不要逐条念出来，也不要说是从记录里看到的）：\n" + lines.joinToString("\n")
}

internal fun userMemoryEnabled(context: Context): Boolean =
    context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MEMORY_ENABLED, true)

internal fun setUserMemoryEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
}

private fun sameWords(a: String, b: String): Boolean = a.filterNot(::isSpaceOrPunctuation) == b.filterNot(::isSpaceOrPunctuation)

private fun isSpaceOrPunctuation(char: Char): Boolean = char.isWhitespace() || char in "，。、；：！？,.;:!?\"'“”‘’"

internal fun encodeMemories(memories: List<UserMemory>): String = buildJsonObject {
    put("version", 1)
    put(
        "memories",
        buildJsonArray {
            memories.forEach { memory ->
                add(
                    buildJsonObject {
                        put("id", memory.id)
                        put("category", memory.category.id)
                        put("content", memory.content)
                        put("source", memory.source.id)
                        memory.said?.let { put("said", it) }
                        put("createdAt", memory.createdAtMillis)
                        put("updatedAt", memory.updatedAtMillis)
                    },
                )
            }
        },
    )
}.toString()

/** Every well-formed memory in [text]; one with an unknown kind or no words is dropped, the rest stay. */
internal fun decodeMemories(text: String): List<UserMemory> {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
    return (root["memories"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        fun string(name: String) = (item[name] as? JsonPrimitive)?.contentOrNull
        fun long(name: String) = (item[name] as? JsonPrimitive)?.longOrNull ?: 0L
        val id = string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val category = MemoryCategory.byId(string("category")) ?: return@mapNotNull null
        val content = string("content")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        UserMemory(id, category, content, MemorySource.byId(string("source")), string("said"), long("createdAt"), long("updatedAt"))
    }
}

/** With [MAX_PLAN_FACTS] for 计划, every memory fits the runtime's 32 context facts. */
internal const val MAX_MEMORIES = 24
internal const val MAX_PER_CATEGORY = 10
internal const val MAX_MEMORY_CHARS = 100
private const val MAX_SAID_CHARS = 200
private const val ID_HEX_CHARS = 8
private const val MEMORY_FILE = "user-memory/memories.json"
private const val MEMORY_PREFS = "debug_user_memory"
private const val KEY_MEMORY_ENABLED = "enabled"
private const val TAG = "DebugUserMemory"
private val SECRET_WORDS = listOf("密码", "验证码", "口令", "支付码", "CVV", "cvv")
/** 15 digits or more, however they are grouped: 「6222 0202 …」「6222-0202-…」「6222/0202/…」. */
private val LONG_NUMBER = Regex("""\d(?:[\s\-/.·_]{0,3}\d){14,}""")
