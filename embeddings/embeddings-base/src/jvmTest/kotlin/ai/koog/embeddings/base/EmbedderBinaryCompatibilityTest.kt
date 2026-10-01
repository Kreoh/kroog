package ai.koog.embeddings.base

import kotlinx.coroutines.test.runTest
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmbedderBinaryCompatibilityTest {
    @Test
    fun testBatchDefaultWorksForPreviouslyCompiledImplementer() = runTest {
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        val directory = Files.createTempDirectory("legacy-embedder")
        try {
            val oldInterface = directory.resolve("Embedder.java")
            Files.writeString(
                oldInterface,
                """
                package ai.koog.embeddings.base;
                public interface Embedder {
                    Object embed(String text, kotlin.coroutines.Continuation<? super Vector> continuation);
                    double diff(Vector first, Vector second);
                }
                """.trimIndent()
            )
            val implementer = directory.resolve("LegacyEmbedder.java")
            Files.writeString(
                implementer,
                """
                package compatibility;
                import ai.koog.embeddings.base.Embedder;
                import ai.koog.embeddings.base.Vector;
                public final class LegacyEmbedder implements Embedder {
                    public Object embed(String text, kotlin.coroutines.Continuation<? super Vector> continuation) {
                        return new Vector(java.util.List.of((double) text.length()));
                    }
                    public double diff(Vector first, Vector second) { return 0.0; }
                }
                """.trimIndent()
            )
            val classpath = listOf(Vector::class.java, Continuation::class.java)
                .map { java.io.File(it.protectionDomain.codeSource.location.toURI()).path }
                .distinct().joinToString(java.io.File.pathSeparator)
            assertEquals(
                0,
                compiler.run(
                    null, null, null, "-classpath", classpath, "-d", directory.toString(),
                    oldInterface.toString(), implementer.toString(),
                )
            )
            URLClassLoader(arrayOf(directory.toUri().toURL()), Embedder::class.java.classLoader).use { loader ->
                val legacy = loader.loadClass("compatibility.LegacyEmbedder").getDeclaredConstructor().newInstance() as Embedder
                assertEquals(
                    listOf(Vector(listOf(2.0)), Vector(listOf(1.0))),
                    legacy.embed(listOf("aa", "b")),
                )
                assertEquals(emptyList(), legacy.embed(emptyList()))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
