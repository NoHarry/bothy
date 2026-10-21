package cc.noharry.bothy.kernel.core

/** Optional prompt-time skill catalog seam. */
interface SkillCatalogPort {
    suspend fun catalog(scene: String?): SkillsCatalog
}
