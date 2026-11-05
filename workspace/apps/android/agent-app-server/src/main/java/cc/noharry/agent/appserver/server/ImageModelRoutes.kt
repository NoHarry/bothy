package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.image.IMAGE_TEST_SESSION
import cc.noharry.agent.appserver.image.ImageGenerator
import cc.noharry.agent.appserver.image.ImageResult
import cc.noharry.agent.appserver.image.TEST_IMAGES_KEPT
import cc.noharry.agent.appserver.image.fetchImageModels
import cc.noharry.agent.appserver.image.loadImageModelConfig
import cc.noharry.agent.appserver.image.saveImageModelConfig
import cc.noharry.agent.appserver.protocol.ImageModelRequests
import cc.noharry.agent.appserver.protocol.ImageModelSaveResult
import cc.noharry.agent.appserver.protocol.ImageTryResult
import cc.noharry.agent.appserver.protocol.ModelListResult
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The image model: its settings, the provider's models and a test generation. */
internal class ImageModelRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(ImageModelRequests.READ) { withContext(Dispatchers.IO) { loadImageModelConfig(app) } }
        on(ImageModelRequests.SAVE) { params ->
            withContext(Dispatchers.IO) {
                val problem = saveImageModelConfig(app, params.config, params.replacementKey, params.clearKey)
                ImageModelSaveResult(loadImageModelConfig(app), problem)
            }
        }
        on(ImageModelRequests.MODELS) {
            val saved = withContext(Dispatchers.IO) { loadImageModelConfig(app) }
            fetchImageModels(app, saved.baseUrl).fold(
                onSuccess = { ModelListResult(it) },
                onFailure = { throw JsonRpcException(JsonRpcErrorCodes.REJECTED, it.message.orEmpty()) },
            )
        }
        on(ImageModelRequests.TRY) { params ->
            val saved = withContext(Dispatchers.IO) { loadImageModelConfig(app) }
            when (val result = ImageGenerator(app).generate(saved, params.text, IMAGE_TEST_SESSION, keep = TEST_IMAGES_KEPT)) {
                is ImageResult.Success -> ImageTryResult(image = result.image, elapsedMillis = result.image.elapsedMillis)
                is ImageResult.Failure -> ImageTryResult(failure = result.message, elapsedMillis = result.elapsedMillis)
            }
        }
    }
}
