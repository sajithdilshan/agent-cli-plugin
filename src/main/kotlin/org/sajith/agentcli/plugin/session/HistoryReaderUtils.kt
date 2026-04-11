package org.sajith.agentcli.plugin.session

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal object HistoryReaderUtils {
    fun fileTimestamp(file: File): LocalDateTime {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(file.lastModified()),
            ZoneId.systemDefault(),
        )
    }

    fun parseSessionsInParallel(
        files: Array<File>,
        sourceName: String,
        logger: Logger,
        parser: (File) -> HistoricalSession?,
    ): List<HistoricalSession> {
        return files
            .toList()
            .parallelStream()
            .map { file ->
                try {
                    parser(file)
                } catch (e: Exception) {
                    logger.warn("[$sourceName] Failed to parse session file: ${file.name}", e)
                    null
                }
            }
            .filter { it != null }
            .map { it!! }
            .toList()
            .sortedByDescending { it.timestamp }
    }
}
