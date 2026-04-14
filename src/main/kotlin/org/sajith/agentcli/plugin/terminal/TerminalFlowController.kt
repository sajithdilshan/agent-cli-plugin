package org.sajith.agentcli.plugin.terminal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Watermark-based flow controller that sits between a PTY reader and xterm.js
 * to prevent the terminal's internal write buffer from growing unbounded during
 * fast output bursts.
 *
 * **How it works:**
 * - Every [write] call forwards the data to the terminal.
 * - Most writes go through a *fast path* with no ack callback attached.
 * - After every [callbackByteLimit] bytes, a write is flagged as needing an ack.
 *   The JS side attaches a `term.write(chunk, callback)` callback for those writes.
 * - When the callback fires, the JS side calls [ack] (via a JBCefJSQuery bridge).
 * - When the number of pending (un-acked) callbacks exceeds [highWatermark], the
 *   controller signals [onPause] so the PTY reader blocks.
 * - When pending callbacks drop below [lowWatermark], [onResume] is signalled.
 *
 * Thread safety: [write] is called from the PTY reader thread; [ack] is called
 * from the JCEF callback thread. All shared state uses atomic operations.
 */
class TerminalFlowController(
    val highWatermark: Int = 5,
    val lowWatermark: Int = 2,
    val callbackByteLimit: Int = 100_000,
    private val onWrite: (data: ByteArray, needsAck: Boolean) -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    init {
        require(highWatermark > lowWatermark) { "highWatermark must be greater than lowWatermark" }
        require(lowWatermark > 0) { "lowWatermark must be positive" }
        require(callbackByteLimit > 0) { "callbackByteLimit must be positive" }
    }

    private val _bytesWritten = AtomicLong(0)
    private val _pendingCallbacks = AtomicInteger(0)
    private val pausedFlag = AtomicBoolean(false)

    /** Accumulated bytes since the last ack-flagged write. */
    val bytesWritten: Long get() = _bytesWritten.get()

    /** Number of ack callbacks that have been requested but not yet acknowledged. */
    val pendingCallbacks: Int get() = _pendingCallbacks.get()

    /** Whether the controller has signalled a pause. */
    val isPaused: Boolean get() = pausedFlag.get()

    /**
     * Called for every chunk read from the PTY.
     * Forwards data to the terminal and manages watermark accounting.
     */
    fun write(data: ByteArray) {
        val newTotal = _bytesWritten.addAndGet(data.size.toLong())

        if (newTotal >= callbackByteLimit) {
            _bytesWritten.set(0)
            val pending = _pendingCallbacks.incrementAndGet()
            onWrite(data, true)

            if (pending > highWatermark && pausedFlag.compareAndSet(false, true)) {
                onPause()
            }
        } else {
            onWrite(data, false)
        }
    }

    /**
     * Called when xterm.js has finished processing an ack-flagged chunk.
     */
    fun ack() {
        val pending = _pendingCallbacks.updateAndGet { current -> maxOf(current - 1, 0) }

        if (pending < lowWatermark && pausedFlag.compareAndSet(true, false)) {
            onResume()
        }
    }
}
