package cc.noharry.bothy.app.overlay
import cc.noharry.bothy.app.a2a.overlayHud
import cc.noharry.bothy.app.a2ui.CardItem
import cc.noharry.bothy.app.a2ui.CardLayout
import cc.noharry.bothy.app.a2ui.DisplayChoice
import cc.noharry.bothy.app.a2ui.OfferStore
import cc.noharry.bothy.app.a2ui.WidgetCard
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.model.ProviderEditState
import cc.noharry.bothy.app.model.summary
import cc.noharry.bothy.app.runtime.RuntimeDemoState
import cc.noharry.bothy.app.session.ConfirmationMode
import cc.noharry.bothy.app.session.ContextPanelState
import cc.noharry.bothy.app.session.ConversationItem
import cc.noharry.bothy.app.session.CurrentRequest
import cc.noharry.bothy.app.session.LessonEntry
import cc.noharry.bothy.app.session.SessionBoardState
import cc.noharry.bothy.app.session.SessionSummary
import cc.noharry.bothy.app.session.SessionToolCall
import cc.noharry.bothy.app.session.SessionTurn
import cc.noharry.bothy.app.session.SkillEntry
import cc.noharry.bothy.app.session.TodoEntry
import cc.noharry.bothy.app.session.ToolCallStatus
import cc.noharry.bothy.app.session.TranscriptRole
import cc.noharry.bothy.app.session.UiState
import cc.noharry.bothy.app.usage.TokenUsageState
import cc.noharry.bothy.app.usage.totalTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBoardSummaryTest {
    @Test
    fun overlayBoardShowsDemoPrimarySections() {
        val sections = populatedState().overlayBoardSections()

        assertEquals(listOf("Run", "Plan", "Result", "Timing", "Usage"), sections.map { it.title })
        assertTrue(sections.section("Run").lines.any { it.contains("LIVE") })
        assertTrue(sections.section("Plan").lines.any { it.contains("Adjust AC") })
        assertTrue(sections.section("Plan").rows.any { it.planStatus == OverlayPlanStatus.ACTIVE })
        assertTrue(sections.section("Usage").lines.any { it.contains("in") && it.contains("out") })
        assertFalse(sections.any { it.title == "MCP" })
    }

    @Test
    fun overlayBoardShowsRunResultTimingWhenEmpty() {
        val sections = emptyState().overlayBoardSections()
        assertEquals(listOf("Run", "Result", "Timing"), sections.map { it.title })
        assertTrue(sections.section("Run").lines.any { it.contains("READY") || it.contains("Ready") })
    }

    @Test
    fun overlayHudLiveShowsBigReadableProgress() {
        val now = 1_000_000L
        val state = baseState(
            isRunning = true,
            runStartedAtEpochMillis = now - 12_500L,
            status = "Tool running: command_navi_KeyWordSearch",
            currentRequest = CurrentRequest(
                "req-1",
                "我想去深圳北站并找停车场",
                "deepseek anthropic",
                "stream",
                "live model",
                "Running",
            ),
            contextPanel = ContextPanelState(
                visible = true,
                todo = listOf(
                    TodoEntry("目的地检索", "completed"),
                    TodoEntry("算路", "in_progress"),
                    TodoEntry("播放音乐", "pending"),
                ),
                lessons = emptyList(),
                skills = emptyList(),
            ),
            items = listOf(
                ConversationItem.ToolCallItem(
                    id = 1,
                    toolName = "mcp__device__command_navi_KeyWordSearch",
                    argumentsJson = "{}",
                    resultJson = null,
                    status = ToolCallStatus.RUNNING,
                    startedAt = now - 1000L,
                ),
            ),
        )
        val hud = state.overlayHud(now)

        assertEquals(OverlayPhase.LIVE, hud.phase)
        assertEquals("LIVE", hud.phaseLabel)
        assertEquals("12s", hud.elapsedLabel)
        assertEquals("1 / 3", hud.progressLabel)
        assertTrue(hud.headline.isNotBlank())
        assertTrue(hud.requestPreview?.contains("深圳北站") == true)
        assertTrue(hud.planRows.any { it.planStatus == OverlayPlanStatus.ACTIVE })
        assertTrue(hud.toolLines.any { it.running })
        assertTrue(hud.timingLine.contains("12s"))
    }

    @Test
    fun overlayHudDoneSurfacesAudienceFacingResult() {
        val state = baseState(
            isRunning = false,
            lastRunSummary = "Worked for 1m 05s",
            status = "Worked for 1m 05s",
            items = listOf(
                ConversationItem.MessageItem(
                    id = 9,
                    role = TranscriptRole.ASSISTANT,
                    content = "已规划到深圳北站，并开始播放周杰伦。",
                ),
            ),
        )
        val hud = state.overlayHud()

        assertEquals(OverlayPhase.DONE, hud.phase)
        assertEquals("DONE", hud.phaseLabel)
        assertEquals("1m 05s", hud.elapsedLabel)
        assertEquals("Completed", hud.headline)
        assertTrue(hud.resultText.contains("深圳北站"))
    }

    @Test
    fun aStoppedRunKeepsItsThinkingHiddenWhenTheSwitchIsOff() {
        val thinking = ConversationItem.MessageItem(id = 4, role = TranscriptRole.ASSISTANT, content = "用户想订位，我先读一下他的通讯录。")
        val stopped = baseState(isRunning = false, lastRunSummary = "Stopped after 12s", status = "Stopped after 12s", items = listOf(thinking))

        assertFalse(stopped.overlayHud(showThinking = false).resultText.contains("通讯录"))
        assertTrue(stopped.overlayHud(showThinking = true).resultText.contains("通讯录"))

        val answered = stopped.copy(items = listOf(thinking, thinking.copy(id = 5, content = "已订好晚上七点两位。", final = true)))
        assertEquals("已订好晚上七点两位。", answered.overlayHud(showThinking = false).resultText)
    }

    @Test
    fun aPartlyDoneRunIsDoneWithItsOwnHeadline() {
        val partial = baseState(isRunning = false, lastRunSummary = "Worked for 1m 05s · partial", status = "Worked for 1m 05s · partial")

        assertEquals(OverlayPhase.DONE, partial.overlayHud().phase)
        assertEquals(PARTLY_DONE_HEADLINE, partial.overlayHud().headline)
        assertEquals("Completed", partial.copy(lastRunSummary = "Worked for 1m 05s", status = "Worked for 1m 05s").overlayHud().headline)
    }

    @Test
    fun overlayHudNormalizesLiteralNewlinesInResult() {
        val state = baseState(
            isRunning = false,
            lastRunSummary = "Worked for 20s",
            status = "Worked for 20s",
            items = listOf(
                ConversationItem.MessageItem(
                    id = 3,
                    role = TranscriptRole.ASSISTANT,
                    // Literal backslash-n as models often emit in transcript text.
                    content = "**我是 Bothy** \\n\\n导航已安排",
                ),
            ),
        )
        val hud = state.overlayHud()
        assertFalse(hud.resultText.contains("\\n"))
        assertTrue(hud.resultText.contains("我是 Bothy"))
        assertTrue(hud.resultText.contains("导航已安排"))
        assertFalse(hud.resultText.contains("**"))
    }

    @Test
    fun overlayHudPrefersAudienceAnswerOverMissingFinishProtocolError() {
        val state = baseState(
            isRunning = false,
            lastRunSummary = "Failed after 32s",
            status = "Failed MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
            items = listOf(
                ConversationItem.MessageItem(
                    id = 1,
                    role = TranscriptRole.ASSISTANT,
                    content = "导航已开启，背景音乐周杰伦已安排。",
                ),
                ConversationItem.MessageItem(
                    id = 2,
                    role = TranscriptRole.ERROR,
                    content = "MODEL_FINISHED_WITHOUT_RUNTIME_FINISH: Model completed without finish(response).",
                ),
            ),
        )
        val hud = state.overlayHud()
        assertEquals(OverlayPhase.FAIL, hud.phase)
        assertTrue(hud.resultText.contains("导航已开启"))
        assertFalse(hud.resultText.contains("MODEL_FINISHED_WITHOUT_RUNTIME_FINISH"))
    }

    @Test
    fun overlayHudDoneWinsWhenSummaryIsWorkedForEvenIfStatusMentionsFail() {
        val hud = baseState(
            isRunning = false,
            lastRunSummary = "Worked for 40s",
            status = "Failed MODEL_FINISHED_WITHOUT_RUNTIME_FINISH",
        ).overlayHud()
        assertEquals(OverlayPhase.DONE, hud.phase)
        assertEquals("Completed", hud.headline)
    }

    @Test
    fun overlayHudTimingUsesCurrentTurnOnlyNotHistoricalToolSum() {
        val now = 2_000_000L
        val hud = baseState(
            isRunning = false,
            lastRunSummary = "Worked for 25s",
            status = "Worked for 25s",
            items = historicalPlusCurrentTurnItems(now),
        ).overlayHud(now)
        assertCurrentTurnHudTiming(hud)
    }

    @Test
    fun withoutThinkingTheCardKeepsToWhatIsSaidToThePassenger() {
        val state = baseState(
            isRunning = true,
            contextPanel = ContextPanelState(
                visible = true,
                todo = listOf(TodoEntry("查用户手机号再订位", "in_progress")),
                lessons = emptyList(),
                skills = emptyList(),
            ),
            items = listOf(
                turnHeader(1),
                ConversationItem.MessageItem(id = 2, role = TranscriptRole.ASSISTANT, content = "用户想订位，我先读一下他的通讯录。\nTTS: 好，帮你找餐厅。"),
            ),
        )
        val shown = state.overlayHud()
        val hidden = state.overlayHud(showThinking = false)

        assertEquals("查用户手机号再订位", shown.currentStep)
        assertTrue(shown.resultText.contains("通讯录"))
        assertEquals("正在处理", hidden.currentStep)
        assertEquals("好，帮你找餐厅。", hidden.resultText)
        assertTrue(hidden.planRows.isEmpty())
        assertTrue(hidden.planEmptyText.orEmpty().contains("显示思考过程"))

        // Streamed rounds run together in one line (bench 2026-10-14): the spoken parts are cut as the announcer does.
        val runTogether = state.copy(
            items = listOf(
                turnHeader(1),
                ConversationItem.MessageItem(
                    id = 2,
                    role = TranscriptRole.ASSISTANT,
                    content = "I'll check the weather and set the AC.TTS: 先查两地天气。TTS: 天气查好了，我再试一次空调。明天北京是晴天",
                ),
            ),
        )
        assertEquals("先查两地天气。\n天气查好了，我再试一次空调。", runTogether.overlayHud(showThinking = false).resultText)
        assertFalse(runTogether.overlayHud().resultText.contains("TTS:"))
    }

    @Test
    fun liveStageShowsOnlyThisTurnsProgressNeverThePreviousAnswer() {
        val previous = ConversationItem.MessageItem(id = 2, role = TranscriptRole.ASSISTANT, content = "路线已规划好。")
        val quiet = baseState(isRunning = true, items = listOf(turnHeader(1), previous, turnHeader(3))).overlayHud()
        val spoken = baseState(
            isRunning = true,
            items = listOf(
                turnHeader(1), previous, turnHeader(3),
                ConversationItem.MessageItem(id = 4, role = TranscriptRole.ASSISTANT, content = "TTS: 好，帮你找停车场。"),
            ),
        ).overlayHud()

        assertEquals("", quiet.resultText)
        assertEquals("好，帮你找停车场。", spoken.resultText)
    }

    @Test
    fun cardWidthFollowsWhatItMustShow() {
        val idle = baseState().overlayHud()
        val running = baseState(isRunning = true).overlayHud()
        val threePicks = idle.copy(choices = (1..3).map { DisplayChoice(it, "停车场$it", it == 1) })
        val fourPicks = idle.copy(choices = (1..4).map { DisplayChoice(it, "停车场$it", it == 1) })
        val longAnswer = idle.copy(resultText = (1..9).joinToString("\n") { "$it. 路线明细" })

        assertEquals(OverlaySize.COMPACT, idle.copy(resultText = "好的，已调亮。").overlaySize(showDetails = false))
        assertEquals(OverlaySize.REGULAR, running.overlaySize(showDetails = false))
        assertEquals(OverlaySize.REGULAR, threePicks.overlaySize(showDetails = false))
        assertEquals(OverlaySize.WIDE, fourPicks.overlaySize(showDetails = false))
        assertEquals(OverlaySize.WIDE, longAnswer.overlaySize(showDetails = false))
        assertEquals(OverlaySize.WIDE, idle.overlaySize(showDetails = true))
    }

    @Test
    fun twoCitiesCardsSitSideBySideOnTheWideCard() {
        fun card(title: String, layout: CardLayout = CardLayout.LIST, items: Int = 0) =
            WidgetCard(title, null, null, null, emptyList(), (1..items).map { CardItem(null, "第$it 项", null) }, layout = layout)
        val shenzhen = card("深圳 · 下周末活动")
        val guangzhou = card("广州 · 下周末活动")
        val idle = baseState().overlayHud()

        assertEquals(listOf(listOf(shenzhen, guangzhou)), listOf(shenzhen, guangzhou).overlayCardRows())
        assertEquals(OverlaySize.REGULAR, idle.copy(cards = listOf(shenzhen)).overlaySize(showDetails = false))
        assertEquals(OverlaySize.WIDE, idle.copy(cards = listOf(shenzhen, guangzhou)).overlaySize(showDetails = false))
        val foshan = card("佛山")
        assertEquals(listOf(listOf(shenzhen, guangzhou), listOf(foshan)), listOf(shenzhen, guangzhou, foshan).overlayCardRows())
        // A trip, or three columns of comparison, needs the whole width: each card takes its own row.
        val trip = card("上海⇄成都 · 3 日行程", CardLayout.TRIP)
        assertEquals(listOf(listOf(trip), listOf(shenzhen)), listOf(trip, shenzhen).overlayCardRows())
        assertEquals(OverlaySize.GRAND, idle.copy(cards = listOf(shenzhen, trip)).overlaySize(showDetails = false))
        val compare = card("三个充电站", CardLayout.COMPARE, items = 3)
        assertEquals(listOf(listOf(compare), listOf(shenzhen)), listOf(compare, shenzhen).overlayCardRows())
    }

    @Test
    fun overlayTextDropsLeakedSpeakAndShowLabels() {
        val text = "路线已规划好。\n\n看\n\n1. 终点：西广场\n2. 剩余：158公里".overlayHudMultiline(500)

        assertEquals("路线已规划好。\n\n1. 终点：西广场\n2. 剩余：158公里", text)
    }

    @Test
    fun overlayHudTurnsAFinishedPickListIntoChoices() {
        val hud = baseState(
            isRunning = false,
            lastRunSummary = "Worked for 18s",
            status = "Worked for 18s",
            items = listOf(turnHeader(10), choiceReplyMessage(11)),
        ).overlayHud()

        assertEquals(listOf("东广场停车场（推荐，离进站口近）", "西广场停车场"), hud.choices.map { it.label })
        assertEquals("深圳北站有两个停车场，推荐东广场，选哪个？", hud.resultText)
    }

    @Test
    fun overlayHudKeepsChoicesForAPartialReplyThatAsks() {
        // Partial/blocked finishes are not a first-party pass, so the stage shows FAIL; the pick is still pending.
        val hud = baseState(
            isRunning = false,
            lastRunSummary = "Failed after 18s",
            status = "Failed after 18s",
            items = listOf(turnHeader(10), choiceReplyMessage(11)),
        ).overlayHud()

        assertEquals(OverlayPhase.FAIL, hud.phase)
        assertEquals(2, hud.choices.size)
    }

    @Test
    fun overlayHudOffersNoChoicesWhileRunningStoppedOrFromAnOlderTurn() {
        val running = baseState(isRunning = true, items = listOf(turnHeader(10), choiceReplyMessage(11))).overlayHud()
        val stopped = baseState(
            isRunning = false,
            lastRunSummary = "Stopped after 5s",
            status = "Stopped after 5s",
            items = listOf(turnHeader(10), choiceReplyMessage(11)),
        ).overlayHud()
        val olderOffer = baseState(
            isRunning = false,
            lastRunSummary = "Failed after 3s",
            status = "Failed after 3s",
            items = listOf(turnHeader(10), choiceReplyMessage(11), turnHeader(12)),
        ).overlayHud()

        assertTrue(running.choices.isEmpty())
        assertTrue(stopped.choices.isEmpty())
        assertTrue(olderOffer.choices.isEmpty())
    }

    @Test
    fun imageToolCallLeadsTheCardWhileDrawingAndOnceDrawn() {
        val reference = "generated-images/ZGVidWctY29uc29sZS1zZXNzaW9u/img-mv0rmhd9-jtne.jpg"
        val drawing = listOf(turnHeader(10), imageCall(ToolCallStatus.RUNNING, result = null))
        assertEquals(OverlayPicture(null, "雪山湖泊"), drawing.overlayPicture())
        // The console may hold the result as escaped JSON inside a preview string.
        val escaped = """{\"ok\":true,\"image\":{\"reference\":\"$reference\",\"width\":1024}}"""
        assertEquals(reference, listOf(imageCall(ToolCallStatus.DONE, escaped)).overlayPicture()?.reference)
        assertEquals(reference, listOf(imageCall(ToolCallStatus.DONE, """{"image":{"reference":"$reference"}}""")).overlayPicture()?.reference)
        assertNull(listOf(imageCall(ToolCallStatus.FAILED, """{"ok":false}""")).overlayPicture())
        // A redacted reference names no file, so no picture rather than a broken frame.
        assertNull(listOf(imageCall(ToolCallStatus.DONE, """{"reference":"generated-images/x/img-[REDACTED_PHONE].jpg"}""")).overlayPicture())
        val hud = baseState(isRunning = false, lastRunSummary = "Worked for 20s", status = "Worked for 20s", items = drawing).overlayHud()
        assertEquals(OverlaySize.REGULAR, hud.copy(resultText = "好").overlaySize(showDetails = false))
    }

    @Test
    fun aShownCardDecidesTheBigPictureOverTheTurnsDrawing() {
        val drawn = "generated-images/ZGVidWctY29uc29sZS1zZXNzaW9u/img-mv0rmhd9-jtne.jpg"
        val reply = ConversationItem.MessageItem(id = 12, role = TranscriptRole.ASSISTANT, content = "行程卡已经放到屏幕上了。")
        val items = listOf(turnHeader(10), imageCall(ToolCallStatus.DONE, """{"image":{"reference":"$drawn"}}"""), reply)
        fun hud() = baseState(isRunning = false, lastRunSummary = "Worked for 20s", status = "Worked for 20s", items = items).overlayHud()
        try {
            assertEquals(drawn, hud().picture?.reference)
            val trip = WidgetCard("苏州一日游", null, null, null, emptyList(), listOf(CardItem(null, "拙政园", null, image = drawn)), image = drawn)
            OfferStore.update("session-1", "turn", 0L) { it.copy(cards = listOf(trip)) }
            // The stops carry the pictures; a big frame beside the reply would show one of them twice.
            assertNull(hud().picture)
            OfferStore.update("session-1", "turn", 0L) { it.copy(cards = listOf(trip.copy(items = emptyList(), value = "人均 90"))) }
            assertEquals(drawn, hud().picture?.reference)
        } finally {
            OfferStore.clear()
        }
    }

    private fun imageCall(status: ToolCallStatus, result: String?) = ConversationItem.ToolCallItem(
        id = 11,
        toolName = "mcp__image_gen__image_generate",
        argumentsJson = """{"prompt":"雪山湖泊"}""",
        resultJson = result,
        status = status,
        startedAt = 0L,
    )

    private fun turnHeader(id: Long) = ConversationItem.TurnHeader(id = id, label = "You", preview = "找停车场", fullText = "找停车场")

    private fun choiceReplyMessage(id: Long) = ConversationItem.MessageItem(
        id = id,
        role = TranscriptRole.ASSISTANT,
        content = "深圳北站有两个停车场，推荐东广场，选哪个？\n\n1. 东广场停车场（推荐，离进站口近）\n2. 西广场停车场",
    )

    private fun historicalPlusCurrentTurnItems(now: Long): List<ConversationItem> =
        listOf(
            doneToolItem(
                id = 1,
                toolName = "mcp__device__command_navi_KeyWordSearch",
                startedAt = now - 3_600_000L,
                finishedAt = now - 3_500_000L, // 100s historical
            ),
            doneToolItem(
                id = 2,
                toolName = "mcp__device__command_music_PlaySearchMusic",
                startedAt = now - 3_400_000L,
                finishedAt = now - 3_300_000L,
            ),
            ConversationItem.TurnHeader(
                id = 10,
                label = "You",
                preview = "去深圳北站",
                fullText = "去深圳北站",
            ),
            doneToolItem(
                id = 11,
                toolName = "mcp__device__command_navi_NavigationOpen",
                startedAt = now - 20_000L,
                finishedAt = now - 5_000L, // 15s this turn
            ),
        )

    private fun doneToolItem(
        id: Long,
        toolName: String,
        startedAt: Long,
        finishedAt: Long,
    ): ConversationItem.ToolCallItem =
        ConversationItem.ToolCallItem(
            id = id,
            toolName = toolName,
            argumentsJson = "{}",
            resultJson = "ok",
            status = ToolCallStatus.DONE,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )

    private fun assertCurrentTurnHudTiming(hud: OverlayHud) {
        // Wall-clock of this run, not sum of all historical tool durations.
        assertEquals("25s", hud.elapsedLabel)
        assertTrue(hud.timingLine.startsWith("25s"))
        assertTrue(hud.timingLine.contains("1 tools"))
        assertFalse(hud.timingLine.contains("tool "))
        assertEquals(1, hud.toolLines.size)
        assertTrue(hud.toolLines.single().name.contains("NavigationOpen"))
        assertEquals("15s", hud.toolLines.single().durationLabel)
    }

    @Test
    fun overlayHudKeepsPlanReadableForDemo() {
        val plan = "规划不走高速去深圳北站并途经广州塔附近的肯德基"
        val hud = baseState(
            contextPanel = ContextPanelState(
                false,
                listOf(TodoEntry(plan, "in_progress")),
                emptyList(),
                emptyList(),
            ),
        ).overlayHud()

        assertTrue(hud.planRows.single().text.contains("深圳北站"))
        assertTrue(hud.planRows.single().text.length <= 48)
    }

    private fun List<OverlayBoardSection>.section(title: String): OverlayBoardSection =
        first { it.title == title }

    private fun populatedState(): UiState {
        val summary = SessionSummary("session-1", "AC task", 2L, 1, "done")
        val tool = SessionToolCall(
            sequence = 1,
            toolName = "command_ac_TemperatureAdjust",
            argumentsPreview = """{"temperature":22}""",
            resultPreview = "ok=true",
            status = "done",
        )
        return baseState(
            isRunning = true,
            runStartedAtEpochMillis = System.currentTimeMillis() - 3000L,
            status = "Tool running: command_ac_TemperatureAdjust",
            currentRequest = CurrentRequest("req-1", "turn on AC", "OpenAI", "stream", "live model", "Running"),
            contextPanel = ContextPanelState(
                visible = true,
                todo = listOf(TodoEntry("Adjust AC", "in_progress")),
                lessons = listOf(LessonEntry("debug", "Use current request evidence.", 0.9f, 1)),
                skills = listOf(SkillEntry("harness-executor", "Daily implementation flow.")),
            ),
            board = SessionBoardState(
                activeSession = summary,
                recentTurns = listOf(
                    SessionTurn("turn-1", "req-0", "run-0", "session-1", "debug", "previous AC", 1L, 2L, "done", "ok", listOf(tool)),
                ),
                lastAction = tool,
            ),
            tokenUsage = TokenUsageState(
                requestCount = 1,
                inputTokens = 10,
                outputTokens = 5,
                totalTokens = 15,
                cachedInputTokens = 2,
                cacheHitTokens = 2,
                cacheMissTokens = 8,
            ),
        )
    }

    private fun emptyState(): UiState = baseState()

    private fun baseState(
        currentRequest: CurrentRequest? = null,
        contextPanel: ContextPanelState = ContextPanelState(false, emptyList(), emptyList(), emptyList()),
        board: SessionBoardState = SessionBoardState(null, emptyList(), null),
        tokenUsage: TokenUsageState = TokenUsageState(),
        items: List<ConversationItem> = emptyList(),
        isRunning: Boolean = false,
        runStartedAtEpochMillis: Long? = null,
        lastRunSummary: String? = null,
        status: String = "Idle",
    ): UiState =
        UiState(
            status = status,
            providerLabel = "OpenAI",
            streamLabel = "non-stream",
            backendLabel = "live model",
            confirmationMode = ConfirmationMode.ASK_EVERY_TIME,
            isRunning = isRunning,
            configLabel = "Config",
            configEditor = ProviderEditState("", "model", "", ""),
            sessions = emptyList(),
            activeSessionId = "session-1",
            inputHint = "Message",
            items = items,
            contextPanel = contextPanel,
            board = board,
            tokenUsage = tokenUsage,
            runtimeDemo = RuntimeDemoState(),
            mcpProviders = emptyList(),
            currentRequest = currentRequest,
            artifactPreview = null,
            runStartedAtEpochMillis = runStartedAtEpochMillis,
            lastRunSummary = lastRunSummary,
        )
}
