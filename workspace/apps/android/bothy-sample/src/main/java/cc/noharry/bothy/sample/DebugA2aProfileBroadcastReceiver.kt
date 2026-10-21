package cc.noharry.bothy.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-only (DUMP) setup of a user A2A profile, so a relay's access token goes from the server to the device without
 * being typed or written to a tracked file. The profile is matched by `label` and updated in place; extras:
 * `endpoint` (required unless `delete`), `module` (default direct), `description`, `token`, `enabled` on/off
 * (default on), `loopback` on/off, `delete` true. The token is never logged.
 */
class DebugA2aProfileBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != A2A_PROFILE_ACTION) return
        val label = intent.getStringExtra(EXTRA_LABEL)?.trim().orEmpty()
        val app = context.applicationContext
        when {
            label.isEmpty() -> Log.w(TAG, "A2A_PROFILE ignored: label is required")
            intent.getStringExtra(EXTRA_DELETE).isOn() -> delete(app, label)
            else -> save(app, label, intent)
        }
    }

    private fun save(context: Context, label: String, intent: Intent) {
        val error = DebugRuntimeSession.saveA2aProfile(
            context,
            label = label,
            moduleId = intent.getStringExtra(EXTRA_MODULE) ?: DIRECT_MODULE_ID,
            endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty(),
            allowLoopbackHttpForDebug = intent.getStringExtra(EXTRA_LOOPBACK).isOn(),
            description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty(),
            token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
            replaceSameLabel = true,
        )
        if (error != null) {
            Log.w(TAG, "A2A_PROFILE saved=false error=$error")
            return
        }
        val profile = DebugA2aModuleConfig.load(context).state.profiles.first { it.userDefined && it.label == label }
        val enabled = intent.getStringExtra(EXTRA_ENABLED)?.isOn() ?: true
        DebugRuntimeSession.setA2aProfileEnabled(context, profile.id, enabled)
        val token = if (DebugA2aTokens.has(context, profile.id)) "stored" else "none"
        Log.i(TAG, "A2A_PROFILE saved=true id=${profile.id} enabled=$enabled endpoint=${profile.endpoint} token=$token")
    }

    private fun delete(context: Context, label: String) {
        val profile = DebugA2aModuleConfig.load(context).state.profiles.firstOrNull { it.userDefined && it.label == label }
        if (profile != null) DebugRuntimeSession.deleteA2aProfile(context, profile.id)
        Log.i(TAG, "A2A_PROFILE deleted=${profile != null} label=$label")
    }
}

private fun String?.isOn(): Boolean = this == "on" || this == "true"

internal const val A2A_PROFILE_ACTION = "cc.noharry.bothy.sample.DEBUG_A2A_PROFILE"

private const val TAG = "DebugA2aProfile"
private const val EXTRA_LABEL = "label"
private const val EXTRA_ENDPOINT = "endpoint"
private const val EXTRA_MODULE = "module"
private const val EXTRA_DESCRIPTION = "description"
private const val EXTRA_TOKEN = "token"
private const val EXTRA_ENABLED = "enabled"
private const val EXTRA_LOOPBACK = "loopback"
private const val EXTRA_DELETE = "delete"
