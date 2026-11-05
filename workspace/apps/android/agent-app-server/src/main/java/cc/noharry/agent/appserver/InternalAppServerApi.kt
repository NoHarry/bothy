package cc.noharry.agent.appserver

/**
 * Inside the app server, public only because a host's tests still reach it (tests of the server's own logic that run
 * on the host's assets and plugins). Not for hosts: it may change or go without notice. The server's own code opts in
 * throughout; another module's tests opt in by compiler option, its main code never does.
 */
@RequiresOptIn(
    message = "Inside the app server, public only for tests; not for hosts.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalAppServerApi
