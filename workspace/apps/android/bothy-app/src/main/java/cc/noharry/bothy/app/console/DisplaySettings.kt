package cc.noharry.bothy.app.console
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens show of the agent's work (owner 2026-10-14: 「我希望思考的具体信息做个开关可以控制要不要显示，因为有
 * 时候会显示一些不便展示的信息」, then 「思考默认关」). 设置 → 通用 → 显示思考过程, off by default; read by the overlay
 * card and the console.
 */
internal object DisplaySettings {
    private val mutableShowThinking = MutableStateFlow(SHOW_THINKING_DEFAULT)

    /**
     * The model's in-between notes on the card while it works, its plan and the step it is on, and the console's
     * Thinking. Off, the card shows only what 小博 says to the user (spoken progress, the result) and a plain step.
     */
    val showThinking: StateFlow<Boolean> = mutableShowThinking

    fun init(context: Context) {
        mutableShowThinking.value = prefs(context).getBoolean(KEY_SHOW_THINKING, SHOW_THINKING_DEFAULT)
    }

    fun setShowThinking(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_THINKING, show).apply()
        mutableShowThinking.value = show
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "debug_display"
    private const val KEY_SHOW_THINKING = "show_thinking"
    private const val SHOW_THINKING_DEFAULT = false
}
