package cc.noharry.agent.appserver.memory

import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.MAX_MEMORIES
import cc.noharry.agent.appserver.protocol.MAX_MEMORY_CHARS
import cc.noharry.agent.appserver.protocol.MAX_PER_CATEGORY
import cc.noharry.agent.appserver.protocol.MemoryCategory
import cc.noharry.agent.appserver.protocol.finding
import cc.noharry.agent.appserver.protocol.text


/** A refused write, for the page to word; [MemoryWrite.Refused.reason] is the model's. */
internal fun refusal(refused: MemoryWrite.Refused): Finding = when (val why = refused.why) {
    MemoryRefusal.Empty -> finding(FindingCode.MEMORY_REFUSED_EMPTY)
    MemoryRefusal.TooLong -> finding(FindingCode.MEMORY_REFUSED_TOO_LONG, MAX_MEMORY_CHARS)
    MemoryRefusal.Secret -> finding(FindingCode.MEMORY_REFUSED_SECRET)
    MemoryRefusal.LongNumber -> finding(FindingCode.MEMORY_REFUSED_LONG_NUMBER)
    MemoryRefusal.Full -> finding(FindingCode.MEMORY_REFUSED_FULL, MAX_MEMORIES)
    is MemoryRefusal.CategoryFull ->
        Finding(FindingCode.MEMORY_REFUSED_CATEGORY_FULL, more = listOf(finding(categoryTitle(why.category)), text(MAX_PER_CATEGORY)))
    is MemoryRefusal.Missing -> finding(FindingCode.MEMORY_REFUSED_MISSING, why.id)
    MemoryRefusal.NotSaved -> finding(FindingCode.MEMORY_REFUSED_NOT_SAVED)
    MemoryRefusal.Arguments, MemoryRefusal.Off -> text(refused.reason)
}

private fun categoryTitle(category: MemoryCategory): FindingCode = when (category) {
    MemoryCategory.IDENTITY -> FindingCode.MEMORY_CATEGORY_IDENTITY
    MemoryCategory.PLACES -> FindingCode.MEMORY_CATEGORY_PLACES
    MemoryCategory.HOME -> FindingCode.MEMORY_CATEGORY_HOME
    MemoryCategory.MEDIA -> FindingCode.MEMORY_CATEGORY_MEDIA
    MemoryCategory.TRAVEL -> FindingCode.MEMORY_CATEGORY_TRAVEL
    MemoryCategory.PEOPLE -> FindingCode.MEMORY_CATEGORY_PEOPLE
    MemoryCategory.OTHER -> FindingCode.MEMORY_CATEGORY_OTHER
}
