package cc.noharry.agent.appserver.image

import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginContext

/**
 * Pictures the model draws with an image model the person sets up, for a host that lists it among its plugins: the
 * image model's settings (`imageModel/read`, `save`, `models`, `try`; the key in no-backup storage), the
 * `image_generate` tool in every run while the model is set up and switched on, its pictures in the conversation's
 * folder of the server's picture store, and which picture each run is drawing for the screens (the offers section's
 * pictures, by request id, which a screen that sees only another agent's task also finds). [shownOn] is where the
 * host's screens show a picture, in the persona's language, which the tool's words name; null names nowhere. Stopped,
 * the requests and the tool are gone, the board of pictures is emptied and the host's debug receiver does nothing;
 * pictures already drawn stay in the store, which the server clears with the conversation or the runtime data.
 */
@ExperimentalAppServerApi
class ImagesPlugin(private val shownOn: String? = null) : AppServerPlugin {
    override val id: String = "images"

    override fun apply(ctx: PluginContext) {
        running = true
        ctx.onStop {
            running = false
            PictureBoard.clear()
        }
        ctx.routes(imageModelRoutes(ctx.app))
        ctx.ownToolServer(IMAGE_TOOL_SERVER_ID)
        // The image model is set up in its own settings entry; its switch, not the MCP list, mounts the tool.
        ctx.tools { context, _ ->
            if (loadImageModelConfig(context).ready) listOf(ImageToolProvider(context, shownOn) { running }) else emptyList()
        }
        ctx.publish {
            follow(PictureBoard.all) { copy(offerPictures = it) }
            // Turned off, the pictures leave the screens' offers with it.
            ctx.onStop { update { copy(offerPictures = emptyMap()) } }
        }
    }

    companion object {
        /** Whether it runs: only then do its tools put pictures on the board and does the debug receiver act. */
        @Volatile
        internal var running: Boolean = false
            private set
    }
}
