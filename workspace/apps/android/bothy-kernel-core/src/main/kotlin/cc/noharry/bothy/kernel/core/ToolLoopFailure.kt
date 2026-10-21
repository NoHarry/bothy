package cc.noharry.bothy.kernel.core

data class ToolLoopFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)
