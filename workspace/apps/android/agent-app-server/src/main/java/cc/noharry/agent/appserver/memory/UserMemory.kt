package cc.noharry.agent.appserver.memory
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.InternalAppServerApi
import cc.noharry.agent.appserver.console.saveFileAtomically
import cc.noharry.agent.appserver.console.setAsideUnreadable
import cc.noharry.agent.appserver.console.singleLine
import cc.noharry.agent.appserver.protocol.MAX_MEMORIES
import cc.noharry.agent.appserver.protocol.MAX_MEMORY_CHARS
import cc.noharry.agent.appserver.protocol.MAX_PER_CATEGORY
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.MemorySource
import cc.noharry.agent.appserver.protocol.UserMemory
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.modelText
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

/**
 * How a write went: the memory as stored, or why nothing changed, in words for the model's tool result
 * ([Refused.reason]) and as [MemoryRefusal] for the page to word in its own language.
 */
@InternalAppServerApi
sealed interface MemoryWrite {
    data class Saved(val memory: UserMemory, val created: Boolean) : MemoryWrite
    data class Removed(val memory: UserMemory) : MemoryWrite
    data class Refused(val reason: String, val why: MemoryRefusal) : MemoryWrite
}

/** Why a write changed nothing, as data for the memory page. */
@InternalAppServerApi
sealed interface MemoryRefusal {
    data object Empty : MemoryRefusal
    data object TooLong : MemoryRefusal
    data object Secret : MemoryRefusal
    data object LongNumber : MemoryRefusal
    data object Full : MemoryRefusal
    data class CategoryFull(val category: MemoryCategory) : MemoryRefusal
    data class Missing(val id: String) : MemoryRefusal
    data object NotSaved : MemoryRefusal
    /** The tool was called wrong; only the model sees it. */
    data object Arguments : MemoryRefusal
}

/**
 * The memories in one file, written whole on every change. Only what the user said about themself or typed here is
 * kept: no guesses, no passwords or codes, no card or ID numbers ([memoryContentError]). [MAX_MEMORIES] in all and
 * [MAX_PER_CATEGORY] per kind, so every one of them fits the runtime's context facts.
 */
@InternalAppServerApi
class UserMemoryBook(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var memories: List<UserMemory> = load()

    @Synchronized fun all(): List<UserMemory> = memories

    @Synchronized
    fun remember(category: MemoryCategory, content: String, source: MemorySource, said: String? = null): MemoryWrite {
        val text = singleLine(content)
        memoryContentRefusal(text)?.let { return it }
        memories.firstOrNull { it.category == category && sameWords(it.content, text) }?.let { return MemoryWrite.Saved(it, created = false) }
        if (memories.size >= MAX_MEMORIES) {
            return MemoryWrite.Refused(memoryFullReason(AppHost.places.memoryPage), MemoryRefusal.Full)
        }
        if (memories.count { it.category == category } >= MAX_PER_CATEGORY) {
            return categoryFull(category)
        }
        val stamp = now()
        val memory = UserMemory(newId(), category, text, source, keptWords(said), stamp, stamp)
        return save(memories + memory) ?: MemoryWrite.Saved(memory, created = true)
    }

    /** New words (and, if given, a new kind) for the memory [id]; the source becomes whoever changed it. */
    @Synchronized
    fun update(id: String, content: String, category: MemoryCategory?, source: MemorySource, said: String? = null): MemoryWrite {
        val old = memories.firstOrNull { it.id == id } ?: return missing(id)
        val text = singleLine(content)
        memoryContentRefusal(text)?.let { return it }
        val kind = category ?: old.category
        if (kind != old.category && memories.count { it.category == kind } >= MAX_PER_CATEGORY) {
            return categoryFull(kind)
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
        val old = memories.firstOrNull { it.id == id } ?: return missing(id)
        return save(memories.filterNot { it.id == id }) ?: MemoryWrite.Removed(old)
    }

    private fun missing(id: String) = MemoryWrite.Refused(modelText("没有这条记忆（$id）", "No memory $id"), MemoryRefusal.Missing(id))

    private fun categoryFull(category: MemoryCategory) = MemoryWrite.Refused(
        modelText(
            "「${category.forModel}」已经记了 $MAX_PER_CATEGORY 件事：先改掉或删掉一条旧的",
            "\"${category.forModel}\" already holds $MAX_PER_CATEGORY memories: change or delete an old one first",
        ),
        MemoryRefusal.CategoryFull(category),
    )

    /** False when the disk took nothing (the memories stay). */
    @Synchronized fun clear(): Boolean = save(emptyList()) == null

    /** The user's words as said, unless they hold what is never kept: 「门禁密码是3721」 must not stay as the 原话. */
    private fun keptWords(said: String?): String? =
        said?.let(::singleLine)?.takeIf { sensitiveTextError(it) == null }?.take(MAX_SAID_CHARS)?.ifEmpty { null }

    /**
     * Null once saved; a refusal when the disk takes nothing (full), so neither the model nor the page crashes the app.
     */
    private fun save(next: List<UserMemory>): MemoryWrite.Refused? = try {
        saveFileAtomically(file, encodeMemories(next))
        memories = next
        null
    } catch (e: IOException) {
        Log.w(TAG, "memories not saved: ${e.javaClass.simpleName}")
        MemoryWrite.Refused(modelText("没存上：设备存储出错了", "Not saved: the device storage failed"), MemoryRefusal.NotSaved)
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
 * The memory page and the model's `user_memory` tool share one book under `files/user-memory/`, which Clear runtime
 * data does not touch (the page's clear-all does). Contents never go to the log: only ids and kinds.
 */
object UserMemoryStore {
    private val mutable = MutableStateFlow<List<UserMemory>>(emptyList())
    val memories: StateFlow<List<UserMemory>> = mutable

    private val switch = MutableStateFlow(true)

    /** The memory switch, as last read or set. */
    val enabled: StateFlow<Boolean> = switch

    @Volatile private var book: UserMemoryBook? = null

    /** Reads the file off the main thread at start, so the page and the first run find it loaded. */
    fun init(context: Context) {
        val app = context.applicationContext
        Thread({ switch.value = userMemoryEnabled(app); book(app) }, "user-memory").start()
    }

    fun switched(enabled: Boolean) {
        switch.value = enabled
    }

    @InternalAppServerApi
    fun book(context: Context): UserMemoryBook = book ?: synchronized(this) {
        book ?: UserMemoryBook(File(context.applicationContext.filesDir, MEMORY_FILE)).also {
            book = it
            mutable.value = it.all()
        }
    }

    /**
     * A change through [book]'s writes, published to the page under the book's lock, so of two writes at once (the
     * model remembering two things in parallel) the page ends on the later; logged without the words.
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
     * What the model uses now: nothing while the memory switch is off, and at most the [MAX_MEMORIES] last changed, so
     * they fit the context facts even from a book written when more were allowed.
     */
    fun inUse(context: Context): List<UserMemory> = if (userMemoryEnabled(context)) memoriesInUse(book(context).all()) else emptyList()
}

/** [all] in their order, but only the [MAX_MEMORIES] last changed. */
@InternalAppServerApi
fun memoriesInUse(all: List<UserMemory>): List<UserMemory> {
    if (all.size <= MAX_MEMORIES) return all
    val kept = all.sortedByDescending { it.updatedAtMillis }.take(MAX_MEMORIES).mapTo(HashSet()) { it.id }
    return all.filter { it.id in kept }
}

/** Why [content] cannot be a memory, or null when it can. */
@InternalAppServerApi
fun memoryContentError(content: String): String? = memoryContentRefusal(content)?.reason

/** [memoryContentError] as a refused write. */
internal fun memoryContentRefusal(content: String): MemoryWrite.Refused? = when {
    content.isBlank() -> MemoryWrite.Refused(modelText("内容是空的", "The content is empty"), MemoryRefusal.Empty)
    content.length > MAX_MEMORY_CHARS -> MemoryWrite.Refused(
        modelText("太长了：一条记忆不超过 $MAX_MEMORY_CHARS 个字", "Too long: a memory is at most $MAX_MEMORY_CHARS characters"),
        MemoryRefusal.TooLong,
    )
    else -> sensitiveRefusal(content)
}

/**
 * Why a remember was refused once [MAX_MEMORIES] are kept, for the model to pass on: delete some on the host's
 * [memoryPage], or, without one, ask the user which to forget.
 */
private fun memoryFullReason(memoryPage: String?): String = modelText(
    "已经记了 $MAX_MEMORIES 件事，记满了：" + (memoryPage?.let { "先在 $it 删掉一些" } ?: "先问用户要忘掉哪几条"),
    "$MAX_MEMORIES memories are kept, the most there can be: " +
        (memoryPage?.let { "delete some on $it first" } ?: "ask the user which to forget first"),
)

/** Why [text] holds what is never kept (a password or code, a card or ID number), or null. 计划 keeps the 原话 out too. */
internal fun sensitiveTextError(text: String): String? = sensitiveRefusal(text)?.reason

private fun sensitiveRefusal(text: String): MemoryWrite.Refused? = when {
    SECRET_WORDS.any { it in text } || SECRET_WORDS_EN.containsMatchIn(text) ->
        MemoryWrite.Refused(modelText("不记密码、验证码这类信息", "Passwords, codes and the like are not kept"), MemoryRefusal.Secret)
    LONG_NUMBER.containsMatchIn(text) ->
        MemoryWrite.Refused(modelText("不记身份证号、银行卡号这类长号码", "ID, card and other long numbers are not kept"), MemoryRefusal.LongNumber)
    else -> null
}

/**
 * The memories as a few lines for a chat prompt (a voice or companion service's persona), or "" when there are none.
 */
fun memoryProfileText(memories: List<UserMemory>): String {
    if (memories.isEmpty()) return ""
    val lines = memories.groupBy { it.category }.toSortedMap().map { (category, items) ->
        "- ${category.forModel}" + modelText("：", ": ") + items.joinToString(modelText("；", "; ")) { it.content }
    }
    return modelText(
        "关于用户（用户亲口告诉你的，自然地用上，不要逐条念出来，也不要说是从记录里看到的）：",
        "About the user (what the user told you themself: use it naturally, don't read it out item by item, and don't say it came from a record):",
    ) + "\n" + lines.joinToString("\n")
}

fun userMemoryEnabled(context: Context): Boolean =
    context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MEMORY_ENABLED, true)

internal fun setUserMemoryEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
    UserMemoryStore.switched(enabled)
}

private fun sameWords(a: String, b: String): Boolean = a.filterNot(::isSpaceOrPunctuation) == b.filterNot(::isSpaceOrPunctuation)

private fun isSpaceOrPunctuation(char: Char): Boolean = char.isWhitespace() || char in "，。、；：！？,.;:!?\"'“”‘’"

@InternalAppServerApi
fun encodeMemories(memories: List<UserMemory>): String = buildJsonObject {
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
@InternalAppServerApi
fun decodeMemories(text: String): List<UserMemory> {
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

private const val MAX_SAID_CHARS = 200
private const val ID_HEX_CHARS = 8
private const val MEMORY_FILE = "user-memory/memories.json"
private const val MEMORY_PREFS = "debug_user_memory"
private const val KEY_MEMORY_ENABLED = "enabled"
private const val TAG = "DebugUserMemory"
private val SECRET_WORDS = listOf("密码", "验证码", "口令", "支付码", "PIN码", "pin码", "CVV", "cvv")
/**
 * The same in English, with no letter either side (Chinese may stand right next to one: 「银行OTP是908172」): a PIN in
 * capitals or followed by what it is (「我的pin是1234」, "pin 4321", "my pin is 4321"), so "enamel pins" and "Pin the card" are kept.
 */
private val SECRET_WORDS_EN = Regex(
    """(?<![A-Za-z])(?:(?i:passwords?|passcodes?|passphrases?|pin ?(?:codes?|numbers?)|verification codes?|security codes?|""" +
        """one[- ]time (?:codes?|passwords?)|otp|cvc|cvv)|PINs?|(?i:pin)(?=\s*(?:is\s|是|码|号|[:：=]|\d)))(?![A-Za-z])""",
)
/** 15 digits or more, however they are grouped: 「6222 0202 …」「6222-0202-…」「6222/0202/…」. */
private val LONG_NUMBER = Regex("""\d(?:[\s\-/.·_]{0,3}\d){14,}""")

/** A kind as the model reads it: in the facts, the chat prompts and a refusal's reason. */
internal val MemoryCategory.forModel: String
    get() = when (this) {
        MemoryCategory.IDENTITY -> modelText("称呼与身份", "name and identity")
        MemoryCategory.PLACES -> modelText("常去地点", "frequent places")
        MemoryCategory.HOME -> modelText("设备与家居偏好", "device and home preferences")
        MemoryCategory.MEDIA -> modelText("娱乐", "entertainment")
        MemoryCategory.TRAVEL -> modelText("出行习惯", "travel habits")
        MemoryCategory.PEOPLE -> modelText("家人朋友", "family and friends")
        MemoryCategory.OTHER -> modelText("其他", "other")
    }
