package cc.noharry.bothy.sample

import android.content.Context
import android.content.Intent
import android.net.Uri

internal data class DebugWorkspaceSelectionState(
    val label: String = "App private",
    val safEnabled: Boolean = false,
)

internal data class DebugSafWorkspace(
    val uri: Uri,
    val label: String,
)

internal fun selectedDebugSafWorkspace(context: Context): DebugSafWorkspace? {
    val prefs = context.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
    val value = prefs.getString(KEY_URI, null)?.takeIf { it.isNotBlank() } ?: return null
    val uri = Uri.parse(value)
    return DebugSafWorkspace(uri = uri, label = uri.lastPathSegment ?: "SAF workspace")
}

internal fun debugWorkspaceSelectionState(context: Context): DebugWorkspaceSelectionState {
    val selected = selectedDebugSafWorkspace(context)
    return DebugWorkspaceSelectionState(
        label = selected?.label ?: "App private",
        safEnabled = selected != null,
    )
}

internal fun saveDebugSafWorkspace(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    context.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_URI, uri.toString())
        .apply()
}

internal fun clearDebugSafWorkspace(context: Context) {
    context.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_URI)
        .apply()
}

internal fun debugSafPickerUnavailableMessage(): String =
    "SAF picker unavailable: no document-tree picker installed."

internal fun debugSafPickerLaunchFailedMessage(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return "SAF picker unavailable: $detail"
}

internal const val SAF_WORKSPACE_SOURCE = "saf://selected-tree"

private const val SAF_PREFS = "debug_saf_workspace"
private const val KEY_URI = "tree_uri"
