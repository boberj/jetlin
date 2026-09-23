package jetlin.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Collects everything the server logged while a test ran.
 *
 * Asserting on log output is usually a bad idea, but it's worth it here, because these particular
 * lines are the feature. A limit that does its job silently teaches nobody anything, and the
 * application it protects people from never gets fixed. Silence is the regression.
 */
internal class LogCapture private constructor(
    private val logger: Logger,
    private val appender: ListAppender<ILoggingEvent>,
) : AutoCloseable {

    val lines: List<String> get() = appender.list.map { it.formattedMessage }

    fun contains(fragment: String): Boolean = lines.any { it.contains(fragment) }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }

    companion object {
        fun of(name: String): LogCapture {
            val logger = LoggerFactory.getLogger(name) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            return LogCapture(logger, appender)
        }
    }
}
