package org.sajith.claudecode.plugin.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class PtyBridge(
    private val command: Array<String>,
    private val workingDirectory: String,
    private val environment: Map<String, String>,
    private val initialCols: Int = 120,
    private val initialRows: Int = 24,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: (exitCode: Int) -> Unit
) : Disposable {

    private val LOG = Logger.getInstance(PtyBridge::class.java)
    private var process: PtyProcess? = null
    private var readerThread: Thread? = null
    private val isDisposed = AtomicBoolean(false)

    fun start() {
        val builder = PtyProcessBuilder(command)
            .setDirectory(workingDirectory)
            .setEnvironment(environment)
            .setInitialColumns(initialCols)
            .setInitialRows(initialRows)
            .setConsole(false)

        process = builder.start()

        readerThread = Thread({
            val buffer = ByteArray(8192)
            val inputStream = process!!.inputStream
            var totalBytesRead = 0L
            try {
                while (!isDisposed.get()) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        break
                    }
                    totalBytesRead += bytesRead
                    val chunk = buffer.copyOf(bytesRead)
                    onOutput(chunk)
                }
            } catch (e: Exception) {
                if (!isDisposed.get()) {
                    LOG.warn("[ClaudeCode] PtyBridge: reader thread error after $totalBytesRead total bytes", e)
                }
            } finally {
                if (!isDisposed.get()) {
                    val exitCode = try {
                        process!!.waitFor()
                    } catch (_: Exception) {
                        -1
                    }
                    onExit(exitCode)
                }
            }
        }, "ClaudeCode-PTY-Reader").apply {
            isDaemon = true
            start()
        }
    }

    fun write(data: String) {
        try {
            process?.outputStream?.let { os ->
                os.write(data.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
        } catch (e: Exception) {
            if (!isDisposed.get()) {
                LOG.warn("[ClaudeCode] PtyBridge: write error", e)
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        try {
            process?.winSize = WinSize(cols, rows)
        } catch (e: Exception) {
            LOG.warn("[ClaudeCode] PtyBridge: resize error", e)
        }
    }

    override fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            readerThread?.interrupt()
            try {
                process?.destroy()
            } catch (e: Exception) {
                LOG.warn("[ClaudeCode] PtyBridge: error destroying process", e)
            }
        }
    }
}
