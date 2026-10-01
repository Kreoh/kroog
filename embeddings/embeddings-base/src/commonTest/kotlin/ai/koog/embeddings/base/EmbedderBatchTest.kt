package ai.koog.embeddings.base

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbedderBatchTest {
    @Test
    fun testExistingImplementerUsesSequentialDefaultInInputOrder() = runTest {
        val calls = mutableListOf<String>()
        val embedder = object : Embedder {
            override suspend fun embed(text: String): Vector {
                calls += text
                return Vector(listOf(text.length.toDouble()))
            }
            override fun diff(embedding1: Vector, embedding2: Vector): Double = 0.0
        }
        assertEquals(listOf(Vector(listOf(2.0)), Vector(listOf(1.0))), embedder.embed(listOf("aa", "b")))
        assertEquals(listOf("aa", "b"), calls)
        assertEquals(emptyList(), embedder.embed(emptyList()))
        assertEquals(listOf("aa", "b"), calls)
    }
}
