package ai.koog.prompt.executor.clients.bedrock.converse

import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamMetadataEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BedrockTokenUsageTest {
    @Test
    fun testRepeatedSnapshotsProduceOneFinalMeasurement() = runTest {
        val initial = TokenUsage {
            inputTokens = 10
            outputTokens = 5
            totalTokens = 15
            cacheReadInputTokens = 30
            cacheWriteInputTokens = 20
        }
        val finalUsage = TokenUsage {
            inputTokens = 10
            outputTokens = 7
            totalTokens = 17
        }
        val frames = BedrockConverseConverters.transformConverseStreamChunks(
            flowOf(
                ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent { usage = initial }),
                ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent { usage = initial }),
                ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent { usage = finalUsage }),
                ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent {}),
            )
        ).toList()
        val meta = frames.filterIsInstance<StreamFrame.End>().single().metaInfo!!
        assertEquals(60, meta.inputTokensCount)
        assertEquals(7, meta.outputTokensCount)
        assertEquals(67, meta.totalTokensCount)
        assertEquals(30, meta.cacheReadTokensCount)
        assertEquals(20, meta.cacheWriteTokensCount)
    }

    @Test
    fun testCacheUsageAcrossTransports() = runTest {
        listOf(null, 0, 30).forEach { read ->
            listOf(null, 0, 20).forEach { write ->
                val usage = TokenUsage {
                    inputTokens = 10
                    outputTokens = 5
                    totalTokens = 15
                    cacheReadInputTokens = read
                    cacheWriteInputTokens = write
                }
                val response = ConverseResponse {
                    this.usage = usage
                    stopReason = StopReason.EndTurn
                }
                val nonStream = BedrockConverseConverters.convertConverseResponse(response, KoogClock.System).metaInfo
                val streamed = BedrockConverseConverters.transformConverseStreamChunks(
                    flowOf(
                        ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent { this.usage = usage }),
                    )
                ).toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!
                listOf(nonStream, streamed).forEach { meta ->
                    assertEquals(10 + (read ?: 0) + (write ?: 0), meta.inputTokensCount)
                    assertEquals(5, meta.outputTokensCount)
                    assertEquals(15 + (read ?: 0) + (write ?: 0), meta.totalTokensCount)
                    assertEquals(read, meta.cacheReadTokensCount)
                    assertEquals(write, meta.cacheWriteTokensCount)
                    assertEquals(null, meta.reasoningTokensCount)
                    assertEquals(null, meta.metadata)
                }
            }
        }
    }

    @Test
    fun testAbsentUsageRemainsUnknown() = runTest {
        val nonStream = BedrockConverseConverters.convertConverseResponse(ConverseResponse { stopReason = StopReason.EndTurn }, KoogClock.System).metaInfo
        val streamed = BedrockConverseConverters.transformConverseStreamChunks(
            flowOf(
                ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent {}),
            )
        ).toList().filterIsInstance<StreamFrame.End>().single().metaInfo!!
        listOf(nonStream, streamed).forEach { meta ->
            assertEquals(null, meta.inputTokensCount)
            assertEquals(null, meta.outputTokensCount)
            assertEquals(null, meta.totalTokensCount)
            assertEquals(null, meta.cacheReadTokensCount)
            assertEquals(null, meta.cacheWriteTokensCount)
        }
    }
}
