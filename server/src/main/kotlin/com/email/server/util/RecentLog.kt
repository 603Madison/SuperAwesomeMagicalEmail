package com.email.server.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What the server logged lately, kept in memory (logback.xml attaches it), so admins can read it
 * without the container's log (Admin › Server log). Warnings are kept longer than the rest, which
 * every request adds to.
 */
class RecentLog : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val cause = event.throwableProxy?.let { " (${it.className}: ${it.message})" }.orEmpty()
        val line = "${TIME.format(Instant.ofEpochMilli(event.timeStamp))} ${event.level} ${event.loggerName.substringAfterLast('.')}: ${event.formattedMessage}$cause"
            .take(2_000)
        synchronized(everything) {
            everything.addLast(line)
            while (everything.size > MAX_EVERYTHING) everything.removeFirst()
            if (event.level.isGreaterOrEqual(Level.WARN)) {
                warnings.addLast(line)
                while (warnings.size > MAX_WARNINGS) warnings.removeFirst()
            }
        }
    }

    companion object {
        private const val MAX_EVERYTHING = 4_000
        private const val MAX_WARNINGS = 400
        private val everything = ArrayDeque<String>()
        private val warnings = ArrayDeque<String>()
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

        /** The latest lines, oldest first: everything, or only warnings and errors. */
        fun latest(warningsOnly: Boolean = false): List<String> = synchronized(everything) { (if (warningsOnly) warnings else everything).toList() }
    }
}
