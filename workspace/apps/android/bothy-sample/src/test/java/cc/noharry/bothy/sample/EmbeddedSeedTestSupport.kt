package cc.noharry.bothy.sample

import android.content.Context

/**
 * Marks the first-launch seeds done, so whatever this machine's local.properties embeds (keys, endpoints, models)
 * stays out of a test that is not about seeding.
 */
internal fun markEmbeddedSeedsDone(context: Context) {
    listOf("debug_jev_config", "debug_voice", "debug_image_model").forEach { prefs ->
        context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().putBoolean("embedded_seeded", true).commit()
    }
}
