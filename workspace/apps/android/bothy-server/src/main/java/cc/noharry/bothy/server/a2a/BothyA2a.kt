package cc.noharry.bothy.server.a2a

import cc.noharry.agent.a2a.server.A2aOutput
import cc.noharry.agent.appserver.a2a.A2aHost
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.bothy.server.server.ServerLink

/**
 * Bothy's side of A2A ([A2aHost]). The local endpoint runs, so a PC debug tool or another app on the device can hand
 * 小博 a task. Such a task brings the card up, so the device visibly responds without its console being opened. A
 * caller may also open or fold the card itself with `carOverlay=show|hide`, alone with `carOverlayOnly=true` (the
 * agent does not run, and the text is ignored). Tasks 小博 hands other agents name the car as their source, as the
 * relay's paired bots know it. [card] opens or folds the card: null when done, else why not.
 */
internal class BothyA2aHost(
    private val card: suspend (OverlayCommand) -> String? = { ServerLink.assistant.card(it) },
) : A2aHost {
    override val localServer: Boolean get() = true

    override val contextSource: String get() = "car"

    override val nicknames: List<String> get() = BOTHY_AGENT_NICKNAMES

    override val nicknamesMeaning: String get() = "their cloud / personal assistant"

    override suspend fun intercept(metadata: Map<String, String>): A2aOutput.Result? {
        val applied = overlayCommand(metadata)?.let { applyCard(it) }
        // A control-only message never runs its placeholder text, even when its command is missing or unknown.
        if (!isOverlayControlOnly(metadata)) return null
        return applied ?: A2aOutput.Result("carOverlayOnly 需要同时设置 carOverlay=show 或 hide", A2aOutput.State.REJECTED)
    }

    // An explicit command was applied before the task, and a hide wins.
    override suspend fun inboundStarted(metadata: Map<String, String>) {
        if (overlayCommand(metadata) == null) card(OverlayCommand.SHOW)
    }

    /** While the full-screen console is in front a show waits until it leaves. */
    private suspend fun applyCard(command: OverlayCommand): A2aOutput.Result {
        val failure = card(command) ?: return A2aOutput.Result(command.done, A2aOutput.State.COMPLETED)
        return A2aOutput.Result(failure, A2aOutput.State.FAILED)
    }
}

/** What Bothy's users call their own cloud agents, whatever their labels (owner 2026-10-17). */
internal val BOTHY_AGENT_NICKNAMES = listOf("云端助手", "个人助手")

/** The one Bothy installs. */
internal val BothyA2a: A2aHost = BothyA2aHost()

/** What an A2A caller is told once [this] was applied to the card. */
private val OverlayCommand.done: String
    get() = when (this) {
        OverlayCommand.SHOW -> "悬浮窗已弹出"
        OverlayCommand.HIDE -> "悬浮窗已收起"
    }

/** `carOverlay=show|hide`; anything else is no command. */
internal fun overlayCommand(metadata: Map<String, String>): OverlayCommand? =
    when (metadata[CAR_OVERLAY_METADATA]?.trim()?.lowercase()) {
        "show" -> OverlayCommand.SHOW
        "hide" -> OverlayCommand.HIDE
        else -> null
    }

/** `carOverlayOnly=true`: the message only controls the card; the agent does not run and the text is ignored. */
internal fun isOverlayControlOnly(metadata: Map<String, String>): Boolean =
    metadata[CAR_OVERLAY_ONLY_METADATA]?.trim()?.lowercase() == "true"

internal const val CAR_OVERLAY_METADATA = "carOverlay"
internal const val CAR_OVERLAY_ONLY_METADATA = "carOverlayOnly"
