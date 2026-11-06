package cc.noharry.bothy.app.a2ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import cc.noharry.agent.appserver.protocol.view.ReplyWords

/** What a tap or a confirmed panel sends as the user's reply, in the language the screens show ("第2个", "Option 2"). */
internal val Resources.replyWords: ReplyWords get() = ReplyWords.forLanguage(configuration.locales[0].language)

/** [replyWords] of the screen being composed. */
@Composable
@ReadOnlyComposable
internal fun replyWords(): ReplyWords = LocalContext.current.resources.replyWords
