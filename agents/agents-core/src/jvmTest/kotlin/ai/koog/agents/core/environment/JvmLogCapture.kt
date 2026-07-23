package ai.koog.agents.core.environment

import java.lang.reflect.Proxy

/**
 * Captures matching Logback events from explicitly named loggers without changing logger configuration.
 *
 * The helper attaches only its own appender. Existing levels, additivity and appenders remain untouched, so
 * unrelated tests and normal parent propagation continue while capture is active.
 */
internal class JvmLogCapture(
    private val correlationId: String,
    vararg loggerNames: String,
) : AutoCloseable {
    private val lock = Any()
    private val renderedEvents = mutableListOf<String>()
    private val loggerClass = Class.forName("ch.qos.logback.classic.Logger")
    private val appenderClass = Class.forName("ch.qos.logback.core.Appender")
    private val throwableProxyClass = Class.forName("ch.qos.logback.classic.spi.IThrowableProxy")
    private val throwableProxyUtilClass = Class.forName("ch.qos.logback.classic.spi.ThrowableProxyUtil")
    private val appender = captureAppender()
    private val loggers: List<Any>
    private var appenderStarted = true
    private var closed = false

    init {
        require(correlationId.isNotBlank()) { "A non-blank correlation ID is required" }
        require(loggerNames.isNotEmpty()) { "At least one explicit logger name is required" }
        require(loggerNames.all(String::isNotBlank)) { "Logger names must be non-blank" }
        require(loggerNames.none { it == "ROOT" }) { "ROOT logger capture is forbidden" }
        require(loggerNames.distinct().size == loggerNames.size) { "Logger names must be unique" }

        val loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory")
        val getLogger = loggerFactoryClass.getMethod("getLogger", String::class.java)
        loggers = loggerNames.map { loggerName ->
            getLogger.invoke(null, loggerName).also { logger ->
                check(loggerClass.isInstance(logger)) {
                    "Expected Logback for JVM log-capture tests, but found ${logger.javaClass.name}"
                }
            }
        }

        val attached = mutableListOf<Any>()
        try {
            loggers.forEach { logger ->
                loggerClass.getMethod("addAppender", appenderClass).invoke(logger, appender)
                attached += logger
            }
        } catch (error: Throwable) {
            attached.forEach { detachAppender(it) }
            stopAppender()
            throw error
        }
    }

    fun rendered(): String = synchronized(lock) {
        renderedEvents.joinToString("\n")
    }

    override fun close() {
        if (closed) return
        closed = true

        loggers.forEach(::detachAppender)
        stopAppender()
    }

    private fun captureAppender(): Any = Proxy.newProxyInstance(
        appenderClass.classLoader,
        arrayOf(appenderClass),
    ) { proxy, method, arguments ->
        when (method.name) {
            "doAppend" -> {
                if (!appenderStarted) return@newProxyInstance null
                val event = requireNotNull(arguments).single()
                val formattedMessage = event.javaClass.getMethod("getFormattedMessage").invoke(event) as String
                val throwableProxy = event.javaClass.getMethod("getThrowableProxy").invoke(event)
                val throwableRendering = throwableProxy?.let {
                    throwableProxyUtilClass
                        .getMethod("asString", throwableProxyClass)
                        .invoke(null, it) as String
                }
                val rendered = listOfNotNull(formattedMessage, throwableRendering).joinToString("\n")
                if (rendered.contains(correlationId)) {
                    synchronized(lock) {
                        renderedEvents += rendered
                    }
                }
                null
            }

            "getName" -> "JvmLogCapture[$correlationId]"
            "start" -> {
                appenderStarted = true
                null
            }

            "stop" -> {
                appenderStarted = false
                null
            }

            "isStarted" -> appenderStarted
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.singleOrNull()
            "toString" -> "JvmLogCapture[$correlationId]"
            else -> null
        }
    }

    private fun detachAppender(logger: Any) {
        loggerClass.getMethod("detachAppender", appenderClass).invoke(logger, appender)
    }

    private fun stopAppender() {
        appenderClass.getMethod("stop").invoke(appender)
    }
}
