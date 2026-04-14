package org.sajith.agentcli.plugin.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PtyBridge(
    private val command: Array<String>,
    private val workingDirectory: String,
    private val environment: Map<String, String>,
    private val initialCols: Int = 120,
    private val initialRows: Int = 24,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: (exitCode: Int) -> Unit,
) : Disposable {
    private var process: PtyProcess? = null
    private var readerThread: Thread? = null
    private val isDisposed = AtomicBoolean(false)

    private val pauseLock = ReentrantLock()
    private val resumeCondition = pauseLock.newCondition()
    private val isPaused = AtomicBoolean(false)

    fun startWithCommand(data: String) {
        val builder =
            PtyProcessBuilder(command)
                .setDirectory(workingDirectory)
                .setEnvironment(environment)
                .setInitialColumns(initialCols)
                .setInitialRows(initialRows)
                .setConsole(false)

        process = builder.start()

        readerThread =
            Thread({
                val buffer = ByteArray(8192)
                val inputStream = process!!.inputStream
                var totalBytesRead = 0L
                try {
                    while (!isDisposed.get()) {
                        awaitResumeIfPaused()
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
                        LOG.warn("[AgentCLI] PtyBridge: reader thread error after $totalBytesRead total bytes", e)
                    }
                } finally {
                    if (!isDisposed.get()) {
                        val exitCode =
                            try {
                                process!!.waitFor()
                            } catch (_: Exception) {
                                -1
                            }
                        onExit(exitCode)
                    }
                }
            }, "AgentCLI-PTY-Reader").apply {
                isDaemon = true
                start()
            }

        write(data)
    }

    fun write(data: String) {
        try {
            process?.outputStream?.let { os ->
                os.write(data.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
        } catch (e: Exception) {
            if (!isDisposed.get()) {
                LOG.warn("[AgentCLI] PtyBridge: write error", e)
            }
        }
    }

    /** Block the reader thread until [resume] is called. */
    fun pause() {
        isPaused.set(true)
    }

    /** Unblock the reader thread. */
    fun resume() {
        isPaused.set(false)
        pauseLock.withLock { resumeCondition.signalAll() }
    }

    private fun awaitResumeIfPaused() {
        if (!isPaused.get()) return
        pauseLock.withLock {
            while (isPaused.get() && !isDisposed.get()) {
                resumeCondition.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    fun resize(
        cols: Int,
        rows: Int,
    ) {
        try {
            process?.winSize = WinSize(cols, rows)
        } catch (e: Exception) {
            LOG.warn("[AgentCLI] PtyBridge: resize error", e)
        }
    }

    override fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            // Unblock reader thread in case it is paused so it can observe isDisposed and exit.
            resume()
            readerThread?.interrupt()
            try {
                readerThread?.join(1000)
            } catch (_: InterruptedException) {
                // Caller was interrupted while waiting; proceed with cleanup.
            }
            try {
                process?.destroy()
            } catch (e: Exception) {
                LOG.warn("[AgentCLI] PtyBridge: error destroying process", e)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PtyBridge::class.java)
    }
}
