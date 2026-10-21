package cc.noharry.bothy.a2a

/** Non-sensitive metadata passed to an authentication provider. */
@ExperimentalA2aClientApi
public data class A2aAuthContext(
    public val remoteId: String,
    public val method: A2aMethod,
    public val requestId: String,
)
