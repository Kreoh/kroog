package ai.koog.prompt.cache

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.CacheControl
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.PromptCacheControl
import ai.koog.prompt.message.PromptCacheTtl
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class PromptCachePolicyTest {
    private val json = Json

    @Test
    fun testNonNullCacheMetadataRoundTripsThroughResponseMetaInfoJson() {
        val original = ResponseMetaInfo(
            timestamp = ResponseMetaInfo.Empty.timestamp,
            metadata = buildJsonObject {
                put("cacheCreationInputTokens", 123)
                put("cacheReadInputTokens", 456)
            },
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ResponseMetaInfo>(encoded)

        assertEquals(original, decoded)
        assertEquals(original.metadata, decoded.metadata)
    }

    @Test
    fun testNonNullPromptCacheControlRoundTripsThroughMessagePartJson() {
        val metadataJson = Json {
            serializersModule = SerializersModule {
                polymorphic(CacheControl::class) {
                    subclass(PromptCacheControl::class)
                }
            }
        }
        val original: MessagePart = MessagePart.Text(
            text = "persisted",
            cacheControl = PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
        )

        val encoded = metadataJson.encodeToString(MessagePart.serializer(), original)
        val decoded = metadataJson.decodeFromString(MessagePart.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(
            PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
            decoded.cacheControl,
        )
    }

    @Test
    fun testLegacyRequestViewSourceShapeRemainsUnambiguous() {
        val legacyReference:
            (Prompt, List<PromptCacheTtl>, Boolean, (CacheControl) -> PromptCacheControl?) -> Prompt =
            PromptCachePolicy::requestView
        val prompt = Prompt(emptyList(), "legacy-source-shape")

        assertSame(
            prompt,
            legacyReference(prompt, emptyList(), false) { it as? PromptCacheControl },
        )
        assertSame(prompt, PromptCachePolicy.requestView(prompt, automaticBreakpoint = false))
    }

    @Test
    fun testRequestViewAddsFiveMinuteMarkerToLatestEligiblePartWithoutMutatingHistory() {
        val eligible = Message.User(
            parts = listOf(MessagePart.Text("cache me"), MessagePart.Text("   ")),
            metaInfo = RequestMetaInfo.Empty,
        )
        val reasoningTail = Message.Assistant(
            part = MessagePart.Reasoning(content = "thought"),
            metaInfo = ResponseMetaInfo.Empty,
        )
        val blankTail = Message.User("   ", RequestMetaInfo.Empty)
        val prompt = Prompt(listOf(eligible, reasoningTail, blankTail), "immutable")

        val requestView = PromptCachePolicy.requestView(prompt)

        assertNull((prompt.messages.first().parts.first() as MessagePart.Text).cacheControl)
        val marker = requestView.messages.first().parts.first().cacheControl as PromptCacheControl
        assertTrue(marker.cacheable)
        assertEquals(PromptCacheTtl.FiveMinutes, marker.ttl)
        assertNull(requestView.messages.first().parts.last().cacheControl)
        assertNull(requestView.messages.last().parts.single().cacheControl)
    }

    @Test
    fun testRequestViewCountsOnlyCacheableProviderEmittableBreakpoints() {
        val ignored = Message.User(
            parts = listOf(
                MessagePart.Text("blank marker follows"),
                MessagePart.Text(" ", PromptCacheControl(cacheable = true)),
                MessagePart.Tool.Result(
                    id = "call",
                    tool = "lookup",
                    output = " ",
                    cacheControl = PromptCacheControl(cacheable = true),
                ),
                MessagePart.Text("metadata only", PromptCacheControl(cacheable = false)),
            ),
            metaInfo = RequestMetaInfo.Empty,
        )
        val prompt = Prompt(listOf(ignored), "eligible-count")

        val requestView = PromptCachePolicy.requestView(prompt)

        val latest = requestView.messages.single().parts.last().cacheControl as PromptCacheControl
        assertTrue(latest.cacheable)
    }

    @Test
    fun testRequestViewDoesNotAddFifthBreakpoint() {
        val controls = List(4) {
            Message.User(
                "message-$it",
                RequestMetaInfo.Empty,
                PromptCacheControl(cacheable = true),
            )
        }
        val prompt = Prompt(controls + Message.User("latest", RequestMetaInfo.Empty), "capacity")

        assertSame(prompt, PromptCachePolicy.requestView(prompt))
    }

    @Test
    fun testRequestViewDoesNotAddOuterToolResultMarkerAfterFourNestedBreakpoints() {
        val marker = PromptCacheControl(cacheable = true)
        val toolResult = MessagePart.Tool.Result(
            id = "call",
            tool = "lookup",
            parts = listOf(
                MessagePart.Text("first", marker),
                MessagePart.Attachment(
                    source = AttachmentSource.Image(
                        content = AttachmentContent.URL("https://example.com/image.png"),
                        format = "png",
                    ),
                    cacheControl = marker,
                ),
                MessagePart.Text("third", marker),
                MessagePart.Text("fourth", marker),
            ),
        )
        val prompt = Prompt(
            messages = listOf(Message.User(parts = listOf(toolResult), metaInfo = RequestMetaInfo.Empty)),
            id = "nested-capacity",
        )

        val requestView = PromptCachePolicy.requestView(prompt)

        assertSame(prompt, requestView)
        assertNull((requestView.messages.single().parts.single() as MessagePart.Tool.Result).cacheControl)
    }

    @Test
    fun testRequestViewRejectsMoreThanFourBreakpoints() {
        val messages = List(5) {
            Message.User(
                "message-$it",
                RequestMetaInfo.Empty,
                PromptCacheControl(cacheable = true),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            PromptCachePolicy.requestView(Prompt(messages, "too-many"))
        }
    }

    @Test
    fun testRequestViewRejectsOneHourAfterFiveMinutes() {
        val prompt = Prompt(
            listOf(
                Message.User(
                    "short",
                    RequestMetaInfo.Empty,
                    PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.FiveMinutes),
                ),
                Message.User(
                    "long",
                    RequestMetaInfo.Empty,
                    PromptCacheControl(cacheable = true, ttl = PromptCacheTtl.OneHour),
                ),
            ),
            "ttl-order",
        )

        assertFailsWith<IllegalArgumentException> {
            PromptCachePolicy.requestView(prompt)
        }
    }

    @Test
    fun testRequestViewRejectsOuterOneHourAfterNestedFiveMinuteBreakpoint() {
        val toolResult = MessagePart.Tool.Result(
            id = "call",
            tool = "lookup",
            parts = listOf(
                MessagePart.Text(
                    text = "nested",
                    cacheControl = PromptCacheControl(
                        cacheable = true,
                        ttl = PromptCacheTtl.FiveMinutes,
                    ),
                ),
            ),
            cacheControl = PromptCacheControl(
                cacheable = true,
                ttl = PromptCacheTtl.OneHour,
            ),
        )
        val prompt = Prompt(
            messages = listOf(Message.User(parts = listOf(toolResult), metaInfo = RequestMetaInfo.Empty)),
            id = "nested-ttl-order",
        )

        assertFailsWith<IllegalArgumentException> {
            PromptCachePolicy.requestView(prompt)
        }
    }

    @Test
    fun testLeadingBreakpointsParticipateInCapacityAndOrdering() {
        val prompt = Prompt(listOf(Message.User("latest", RequestMetaInfo.Empty)), "leading")

        assertSame(
            prompt,
            PromptCachePolicy.requestView(prompt, leadingBreakpoints = List(4) { PromptCacheTtl.FiveMinutes }),
        )
        assertFailsWith<IllegalArgumentException> {
            PromptCachePolicy.requestView(
                prompt,
                leadingBreakpoints = listOf(PromptCacheTtl.FiveMinutes, PromptCacheTtl.OneHour),
            )
        }
    }

    @Test
    fun testTrailingBreakpointsParticipateInCapacityAndOrdering() {
        val prompt = Prompt(listOf(Message.User("latest", RequestMetaInfo.Empty)), "trailing")

        assertSame(
            prompt,
            PromptCachePolicy.requestView(
                prompt,
                leadingBreakpoints = emptyList(),
                trailingBreakpoints = List(4) { PromptCacheTtl.FiveMinutes },
            ),
        )
        assertSame(
            prompt,
            PromptCachePolicy.requestView(
                prompt,
                leadingBreakpoints = emptyList(),
                trailingBreakpoints = listOf(PromptCacheTtl.OneHour),
            ),
        )
        val explicitFiveMinutes = Prompt(
            listOf(
                Message.User(
                    "explicit",
                    RequestMetaInfo.Empty,
                    PromptCacheControl(cacheable = true),
                )
            ),
            "trailing-explicit-order",
        )
        assertFailsWith<IllegalArgumentException> {
            PromptCachePolicy.requestView(
                explicitFiveMinutes,
                leadingBreakpoints = emptyList(),
                trailingBreakpoints = listOf(PromptCacheTtl.OneHour),
            )
        }
    }
}
