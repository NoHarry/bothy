package cc.noharry.agent.appserver.server

/**
 * Whether the app's own screens are in front, as the app last said (`app/inFront`): what the person looks at then is a
 * front end's conversation, where a drawn page shows ([cc.noharry.agent.appserver.pages.PageToolProvider]).
 */
internal object AppScreen {
    @Volatile var inFront: Boolean = false
}
