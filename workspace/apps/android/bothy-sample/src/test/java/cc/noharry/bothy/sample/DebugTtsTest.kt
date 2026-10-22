package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.transport.HttpTransport
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTtsTest {
    @Test
    fun mimoTtsRequestUsesAssistantForTargetTextAndParsesAudio() = runBlocking {
        val transport = RecordingTransport("audio-bytes".encodeUtf8().base64())
        val client = DebugMimoTtsClient(transport)

        val audio = client.synthesize(READY_CONFIG, "广州塔路线已规划")

        assertArrayEquals("audio-bytes".toByteArray(), audio.bytes)
        assertEquals("wav", audio.format)
        val request = transport.request.await()
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", request.url)
        assertEquals("key", request.headers["api-key"])
        assertFalse(request.headers.containsKey("Authorization"))
        val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)).jsonObject
        val messages = body.getValue("messages").jsonArray
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(READY_CONFIG.style, messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("assistant", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("广州塔路线已规划", messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("wav", body.getValue("audio").jsonObject["format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Chloe", body.getValue("audio").jsonObject["voice"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun streamingRequestUsesPcm16AndParsesAudioDeltas() = runBlocking {
        val transport = RecordingTransport(
            audioBase64 = "audio-bytes".encodeUtf8().base64(),
            streamAudioBase64 = listOf(
                "pcm-one".encodeUtf8().base64(),
                "pcm-two".encodeUtf8().base64(),
            ),
        )
        val client = DebugMimoTtsClient(transport)

        val chunks = client.stream(STREAM_CONFIG, "广州塔路线已规划").toList()

        assertEquals(listOf("pcm-one", "pcm-two"), chunks.map { it.bytes.toString(Charsets.UTF_8) })
        assertEquals(listOf("pcm16", "pcm16"), chunks.map { it.format })
        val request = transport.streamRequest.await()
        val body = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8)).jsonObject
        val audio = body.getValue("audio").jsonObject
        assertEquals("mimo-v2.5-tts", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", body["stream"]?.jsonPrimitive?.contentOrNull)
        assertEquals("pcm16", audio["format"]?.jsonPrimitive?.contentOrNull)
        assertEquals("冰糖", audio["voice"]?.jsonPrimitive?.contentOrNull)
        assertFalse(audio.containsKey("optimize_text_preview"))
        val messages = body.getValue("messages").jsonArray
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(STREAM_CONFIG.style, messages[0].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("assistant", messages[1].jsonObject["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("广州塔路线已规划", messages[1].jsonObject["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun parsesTtsJsonConfig() {
        val values = parseProviderConfigText(
            """
                {
                  "tts": {
                    "enabled": true,
                    "apiKey": "key",
                    "baseUrl": "https://api.xiaomimimo.com/v1",
                    "model": "mimo-v2.5-tts",
                    "voice": "Chloe",
                    "format": "wav",
                    "style": "calm"
                  }
                }
            """.trimIndent(),
        )

        val config = DebugTtsConfig.from(values)

        assertTrue(config.ready)
        assertEquals("key", config.apiKey)
        assertEquals("calm", config.style)
    }

    @Test
    fun usesMimoProviderProfileKeyAsTtsFallback() {
        val config = DebugTtsConfig.from(
            mapOf(MIMO_PROVIDER_PROFILE_API_KEY to "profile-key"),
        )

        assertTrue(config.ready)
        assertEquals("profile-key", config.apiKey)
        assertEquals("mimo-v2.5-tts", config.model)
        assertEquals("冰糖", config.voice)
        assertEquals("pcm16", config.format)
        assertTrue(config.style.contains("Grok"))
        assertTrue(config.stream)
        assertFalse(config.optimizeTextPreview)
    }

    @Test
    fun explicitTtsEnabledFalseDisablesMimoProviderProfileFallback() {
        val config = DebugTtsConfig.from(
            mapOf(
                MIMO_PROVIDER_PROFILE_API_KEY to "profile-key",
                MIMO_TTS_ENABLED to "false",
            ),
        )

        assertFalse(config.ready)
        assertEquals("profile-key", config.apiKey)
    }

    @Test
    fun announcerDoesNotBlockCallerWhileSynthesisRuns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = BlockingTtsClient()
        val player = RecordingTtsPlayer()
        val statuses = mutableListOf<String>()
        val announcer = DebugTtsAnnouncer(scope, client, player) { statuses += it }

        try {
            val elapsed = measureTimeMillis { announcer.announce(STREAM_CONFIG, "hello") }

            assertTrue("announce took ${elapsed}ms", elapsed < 100)
            withTimeout(1000) { client.started.await() }
            assertFalse(player.streamed.isCompleted)
            client.streamRelease.complete(DebugTtsAudioChunk(byteArrayOf(1, 2, 3), "pcm16"))
            val played = withTimeout(1000) { player.streamed.await() }
            assertArrayEquals(byteArrayOf(1, 2, 3), played.single().bytes)
            assertEquals(listOf("TTS streaming"), statuses)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun announcerSpeaksMarkedIntermediateProgressOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "我先看一下能力。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            assertFalse(client.started.isCompleted)

            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 我先规划路线。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }

            assertEquals("我先规划路线。", withTimeout(1000) { client.started.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test fun finalFollowUpIsIncludedInLocalSynthesisRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val announcer = DebugTtsAnnouncer(scope, client, RecordingTtsPlayer())
        try {
            announcer.observe(RUN_SCOPE, AgentEvent.ResultAvailable(firstPartyCompleted("信息已查到。需要我继续查停车场吗？")), STREAM_CONFIG) { true }
            assertEquals("信息已查到。需要我继续查停车场吗？", withTimeout(1000) { client.started.await() })
        } finally {
            announcer.stop()
            scope.cancel()
        }
    }

    @Test
    fun announcerBuffersSplitIntermediateProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(RUN_SCOPE, AgentEvent.TextDelta(RUN_ID, "TTS: 我先"), STREAM_CONFIG) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            assertFalse(client.started.isCompleted)

            announcer.observe(RUN_SCOPE, AgentEvent.TextDelta(RUN_ID, "规划路线。"), STREAM_CONFIG) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }

            assertEquals("我先规划路线。", withTimeout(1000) { client.started.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun announcerIgnoresGenericAcknowledgementProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(RUN_SCOPE, AgentEvent.TextDelta(RUN_ID, "TTS: 好的。"), STREAM_CONFIG) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }

            assertFalse(client.started.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun announcerIgnoresTooShortGenericProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(RUN_SCOPE, AgentEvent.TextDelta(RUN_ID, "TTS: 准备出发！"), STREAM_CONFIG) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }

            assertFalse(client.started.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun announcerStillSpeaksCompletedResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(firstPartyCompleted("导航已开始。")),
                STREAM_CONFIG,
            ) { true }

            assertEquals("导航已开始。", withTimeout(1000) { client.started.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun standaloneMultiIntentCompletedResultSpeaksLeadParagraph() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(
                    firstPartyCompleted(
                        "导航已开始。\n\n- 停车场、肯德基、厕所和音乐也都处理好了\n- 屏幕上可以查看完整结果",
                    ),
                ),
                STREAM_CONFIG,
            ) { true }

            // Host speaks the model's lead paragraph; later paragraphs are screen-only, no reconstructed script.
            assertEquals("导航已开始。", withTimeout(1000) { client.started.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun standaloneSimpleCompletedResultSpeaksLeadParagraphOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(
                    firstPartyCompleted("导航已开始。\n\n- 屏幕上可以查看完整结果"),
                ),
                STREAM_CONFIG,
            ) { true }

            assertEquals("导航已开始。", withTimeout(1000) { client.started.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedResultAfterProgressSpeaksConciseCue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 我先规划路线。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            assertEquals("我先规划路线。", withTimeout(1000) { client.firstStarted.await() })

            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(firstPartyCompleted("导航已开始。")),
                STREAM_CONFIG,
            ) { true }
            assertFalse(client.secondStarted.isCompleted)

            client.firstRelease.complete(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
            // Final cue uses model finish text (first sentence), not host scene scripts.
            assertEquals("导航已开始。", withTimeout(1000) { client.secondStarted.await() })
            client.secondRelease.complete(DebugTtsAudioChunk(byteArrayOf(2), "pcm16"))
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedResultAfterBroadProgressUsesModelFinishSentence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 路线、停车和音乐都安排中。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            assertEquals("路线、停车和音乐都安排中。", withTimeout(1000) { client.firstStarted.await() })

            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(firstPartyCompleted("导航、停车和音乐都已安排。")),
                STREAM_CONFIG,
            ) { true }
            assertFalse(client.secondStarted.isCompleted)

            client.firstRelease.complete(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
            assertEquals("导航、停车和音乐都已安排。", withTimeout(1000) { client.secondStarted.await() })
            client.secondRelease.complete(DebugTtsAudioChunk(byteArrayOf(2), "pcm16"))
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedResultAfterProgressClearsPendingStaleProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 我先规划路线。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            assertEquals("我先规划路线。", withTimeout(1000) { client.firstStarted.await() })

            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 我在查停车。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(
                    firstPartyCompleted("导航和停车都已安排。"),
                ),
                STREAM_CONFIG,
            ) { true }
            assertFalse(client.secondStarted.isCompleted)

            client.firstRelease.complete(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
            // Pending second progress is dropped when final is enqueued (clearPending).
            assertEquals("导航和停车都已安排。", withTimeout(1000) { client.secondStarted.await() })
            client.secondRelease.complete(DebugTtsAudioChunk(byteArrayOf(2), "pcm16"))
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun announcerAllowsUpToFourDistinctProgressLines() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient(maxStarts = 4)
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            val lines = listOf(
                "TTS: 我是小博，先规划不走高速去深圳北站。",
                "TTS: 途经广州塔肯德基，正在算路线。",
                "TTS: 路线好了，正在查停车和沿途厕所。",
                "TTS: 开始导航，并播放周杰伦的歌。",
            )
            lines.forEach { line ->
                announcer.observe(RUN_SCOPE, AgentEvent.TextDelta(RUN_ID, line), STREAM_CONFIG) { true }
                announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            }
            // Fifth distinct line must be ignored by the per-run progress cap.
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 额外第五句不应播报。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }

            val spoken = mutableListOf<String>()
            repeat(4) { index ->
                val text = withTimeout(1000) { client.startedAt(index).await() }
                spoken += text
                client.releaseAt(index).complete(DebugTtsAudioChunk(byteArrayOf((index + 1).toByte()), "pcm16"))
            }
            assertEquals(
                listOf(
                    "我是小博，先规划不走高速去深圳北站。",
                    "途经广州塔肯德基，正在算路线。",
                    "路线好了，正在查停车和沿途厕所。",
                    "开始导航，并播放周杰伦的歌。",
                ),
                spoken,
            )
            assertEquals(4, client.startCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedResultAfterNarrowProgressUsesModelFinishSentence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient()
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 我先规划路线。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }
            assertEquals("我先规划路线。", withTimeout(1000) { client.firstStarted.await() })

            announcer.observe(
                RUN_SCOPE,
                AgentEvent.ResultAvailable(
                    firstPartyCompleted("导航、停车、肯德基、厕所和周杰伦音乐都已安排。"),
                ),
                STREAM_CONFIG,
            ) { true }

            client.firstRelease.complete(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
            assertEquals(
                "导航、停车、肯德基、厕所和周杰伦音乐都已安排。",
                withTimeout(1000) { client.secondStarted.await() },
            )
            client.secondRelease.complete(DebugTtsAudioChunk(byteArrayOf(2), "pcm16"))
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun announcerSplitsAdjacentTtsMarkersWithoutPunctuation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient(maxStarts = 2)
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)

        try {
            announcer.observe(
                RUN_SCOPE,
                AgentEvent.TextDelta(RUN_ID, "TTS: 再查查深圳北站附近的肯TTS: 找到世界之窗了。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(RUN_SCOPE, toolStarted(RUN_ID), STREAM_CONFIG) { true }

            assertEquals("再查查深圳北站附近的肯", withTimeout(1000) { client.startedAt(0).await() })
            client.releaseAt(0).complete(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
            assertEquals("找到世界之窗了。", withTimeout(1000) { client.startedAt(1).await() })
            client.releaseAt(1).complete(DebugTtsAudioChunk(byteArrayOf(2), "pcm16"))
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun newRunDropsPreviousRunQueuedProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SequentialBlockingTtsClient(maxStarts = 2)
        val player = RecordingTtsPlayer()
        val announcer = DebugTtsAnnouncer(scope, client, player)
        val runA = DebugRuntimeRunScope("request-a", "session-1")
        val runB = DebugRuntimeRunScope("request-b", "session-1")

        try {
            announcer.observe(
                runA,
                AgentEvent.TextDelta("run-a", "TTS: 上一轮周杰伦音乐安排中。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(runA, toolStarted("run-a"), STREAM_CONFIG) { true }
            assertEquals("上一轮周杰伦音乐安排中。", withTimeout(1000) { client.startedAt(0).await() })

            // Second progress of run A is queued while first still holds the player.
            announcer.observe(
                runA,
                AgentEvent.TextDelta("run-a", "TTS: 上一轮还在查厕所。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(runA, toolStarted("run-a"), STREAM_CONFIG) { true }

            // New run must not speak the previous run's pending progress.
            announcer.observe(
                runB,
                AgentEvent.TextDelta("run-b", "TTS: 本轮只规划去深圳北站。"),
                STREAM_CONFIG,
            ) { true }
            announcer.observe(runB, toolStarted("run-b"), STREAM_CONFIG) { true }

            client.releaseAt(0).complete(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
            assertEquals("本轮只规划去深圳北站。", withTimeout(1000) { client.startedAt(1).await() })
            client.releaseAt(1).complete(DebugTtsAudioChunk(byteArrayOf(2), "pcm16"))
            assertEquals(2, client.startCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedNonSucceededResultsDoNotSpeakFinalTts() = runBlocking {
        val outcomes = listOf(
            RuntimeCompletionOutcome.PARTIAL,
            RuntimeCompletionOutcome.BLOCKED,
            RuntimeCompletionOutcome.FAILED,
            RuntimeCompletionOutcome.UNKNOWN,
        )
        outcomes.forEach { outcome ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = CapturingTtsClient()
            val player = RecordingTtsPlayer()
            val announcer = DebugTtsAnnouncer(scope, client, player)
            try {
                announcer.observe(
                    RUN_SCOPE,
                    AgentEvent.ResultAvailable(firstPartyCompleted("导航已开始。", outcome)),
                    STREAM_CONFIG,
                ) { true }
                delay(150)
                assertFalse(outcome.name, client.started.isCompleted)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun aSpokenRequestHearsItsPartialReplyEvenWithoutAQuestion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val announcer = DebugTtsAnnouncer(scope, client, RecordingTtsPlayer())
        val spokenRun = "spoken-run-1"
        DebugSpokenRuns.mark(spokenRun)
        try {
            val reply = "空调温度已经按22度给你设上了，不过接口只回了受理，你在中控上看一眼。"
            val result = firstPartyCompleted(reply, RuntimeCompletionOutcome.PARTIAL).copy(runId = spokenRun)
            announcer.observe(RUN_SCOPE, AgentEvent.ResultAvailable(result), STREAM_CONFIG) { true }
            val spoken = withTimeout(2_000) { client.started.await() }
            assertTrue(spoken, spoken.startsWith("空调温度已经按22度给你设上了"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSpokenRequestHearsTheWholeReplyNotJustItsFirstParagraph() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CapturingTtsClient()
        val announcer = DebugTtsAnnouncer(scope, client, RecordingTtsPlayer())
        val spokenRun = "spoken-run-2"
        DebugSpokenRuns.mark(spokenRun)
        try {
            val reply = "歌我已经点给音乐播了，你在中控上瞄一眼歌名对不对；想听别的我随时换。推荐的话，开车路上放点节奏稳、" +
                "不吵的老歌最合适，比如《晴天》这类，跟着哼两句也不分神。\n\n鼓励你一句：你今天已经比昨天多往前走了一段，慢一点不丢人，" +
                "方向对了就不算白费，我陪着你。"
            val result = firstPartyCompleted(reply, RuntimeCompletionOutcome.PARTIAL).copy(runId = spokenRun)
            announcer.observe(RUN_SCOPE, AgentEvent.ResultAvailable(result), STREAM_CONFIG) { true }
            val spoken = withTimeout(2_000) { client.started.await() }
            assertTrue(spoken, spoken.contains("鼓励你一句"))
        } finally {
            scope.cancel()
        }
    }

    private class RecordingTransport(
        private val audioBase64: String,
        private val streamAudioBase64: List<String> = emptyList(),
    ) : HttpTransport {
        val request = CompletableDeferred<HttpRequest>()
        val streamRequest = CompletableDeferred<HttpRequest>()

        override fun stream(request: HttpRequest): Flow<HttpEvent> = flow {
            streamRequest.complete(request)
            emit(HttpEvent.Response(200))
            streamAudioBase64.forEach { audio ->
                emit(HttpEvent.Data("""data: {"choices":[{"delta":{"audio":{"data":"$audio"}}}]}""".frame()))
            }
            emit(HttpEvent.Data("""data: [DONE]""".frame()))
            emit(HttpEvent.Done)
        }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            this.request.complete(request)
            val body = """{"choices":[{"message":{"audio":{"data":"$audioBase64"}}}]}"""
            return HttpResponse(statusCode = 200, body = body.toByteArray())
        }
    }

    private class BlockingTtsClient : DebugTtsClient {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<DebugTtsAudio>()
        val streamRelease = CompletableDeferred<DebugTtsAudioChunk>()

        override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio {
            started.complete(Unit)
            return release.await()
        }

        override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = flow {
            started.complete(Unit)
            emit(streamRelease.await())
        }
    }

    private class CapturingTtsClient : DebugTtsClient {
        val started = CompletableDeferred<String>()

        override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio {
            started.complete(text)
            return DebugTtsAudio(byteArrayOf(1), config.format)
        }

        override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = flow {
            started.complete(text)
            emit(DebugTtsAudioChunk(byteArrayOf(1), "pcm16"))
        }
    }

    private class SequentialBlockingTtsClient(
        private val maxStarts: Int = 2,
    ) : DebugTtsClient {
        private val started = List(maxStarts) { CompletableDeferred<String>() }
        private val releases = List(maxStarts) { CompletableDeferred<DebugTtsAudioChunk>() }
        private var calls = 0
        val startCount: Int get() = calls

        val firstStarted: CompletableDeferred<String> get() = started[0]
        val secondStarted: CompletableDeferred<String> get() = started[1]
        val firstRelease: CompletableDeferred<DebugTtsAudioChunk> get() = releases[0]
        val secondRelease: CompletableDeferred<DebugTtsAudioChunk> get() = releases[1]

        fun startedAt(index: Int): CompletableDeferred<String> = started[index]
        fun releaseAt(index: Int): CompletableDeferred<DebugTtsAudioChunk> = releases[index]

        override suspend fun synthesize(config: DebugTtsConfig, text: String): DebugTtsAudio =
            error("unused")

        override fun stream(config: DebugTtsConfig, text: String): Flow<DebugTtsAudioChunk> = flow {
            val callIndex = calls++
            check(callIndex < maxStarts) { "unexpected TTS start #$callIndex text=$text" }
            started[callIndex].complete(text)
            emit(releases[callIndex].await())
        }
    }

    private class RecordingTtsPlayer : DebugTtsPlayer {
        val played = CompletableDeferred<DebugTtsAudio>()
        val streamed = CompletableDeferred<List<DebugTtsAudioChunk>>()

        override suspend fun play(audio: DebugTtsAudio) {
            played.complete(audio)
        }

        override suspend fun playStream(chunks: Flow<DebugTtsAudioChunk>) {
            streamed.complete(chunks.toList())
        }

        override fun stop() = Unit
    }

    private companion object {
        val READY_CONFIG = DebugTtsConfig(
            enabled = true,
            apiKey = "key",
            baseUrl = "https://api.xiaomimimo.com/v1",
            model = "mimo-v2.5-tts",
            voice = "Chloe",
            format = "wav",
            style = "calm assistant",
            stream = false,
        )
        val STREAM_CONFIG = DebugTtsConfig(
            enabled = true,
            apiKey = "key",
            baseUrl = "https://api.xiaomimimo.com/v1",
            model = "mimo-v2.5-tts",
            voice = "冰糖",
            format = "pcm16",
            style = "Grok-like conversational assistant",
            stream = true,
        )
        const val RUN_ID = "run-1"
        val RUN_SCOPE = DebugRuntimeRunScope("request-1", "session-1")

        fun firstPartyCompleted(
            response: String,
            outcome: RuntimeCompletionOutcome = RuntimeCompletionOutcome.SUCCEEDED,
        ): RuntimeResult = RuntimeResult(
            RUN_ID,
            RuntimeRunStatus.COMPLETED,
            response,
            completion = RuntimeCompletionSummary(outcome = outcome),
        )
    }
}

private fun String.frame(): ByteArray = "$this\n\n".toByteArray(Charsets.UTF_8)

/** A real round speaks its progress once it goes on to a business tool. */
internal fun toolStarted(runId: String) =
    AgentEvent.NativeToolCallStarted(runId, "mcp__device__navigation_poi_search", "{}")
