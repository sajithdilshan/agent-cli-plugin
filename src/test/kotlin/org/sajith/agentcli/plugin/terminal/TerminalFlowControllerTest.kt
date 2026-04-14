package org.sajith.agentcli.plugin.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerminalFlowControllerTest {
    // ── Helpers ──────────────────────────────────────────────────────

    /** Collects all calls made by the flow controller for assertion. */
    private class Recorder {
        data class WriteRecord(val size: Int, val needsAck: Boolean)

        val writes = CopyOnWriteArrayList<WriteRecord>()
        val pauseCount = AtomicInteger(0)
        val resumeCount = AtomicInteger(0)

        fun onWrite(
            data: ByteArray,
            needsAck: Boolean,
        ) {
            writes.add(WriteRecord(data.size, needsAck))
        }

        fun onPause() {
            pauseCount.incrementAndGet()
        }

        fun onResume() {
            resumeCount.incrementAndGet()
        }

        /** Count of writes that were flagged as needing an ack. */
        val ackWrites: Int get() = writes.count { it.needsAck }

        /** Count of writes on the fast path (no ack). */
        val fastWrites: Int get() = writes.count { !it.needsAck }
    }

    private fun controller(
        highWatermark: Int = 5,
        lowWatermark: Int = 2,
        callbackByteLimit: Int = 100,
        recorder: Recorder = Recorder(),
    ): Pair<TerminalFlowController, Recorder> {
        val fc =
            TerminalFlowController(
                highWatermark = highWatermark,
                lowWatermark = lowWatermark,
                callbackByteLimit = callbackByteLimit,
                onWrite = recorder::onWrite,
                onPause = recorder::onPause,
                onResume = recorder::onResume,
            )
        return fc to recorder
    }

    private fun chunk(size: Int): ByteArray = ByteArray(size)

    // ── Constructor validation ──────────────────────────────────────

    @Test
    fun `highWatermark must be greater than lowWatermark`() {
        assertThrows<IllegalArgumentException> {
            TerminalFlowController(
                highWatermark = 2,
                lowWatermark = 2,
                callbackByteLimit = 100,
                onWrite = { _, _ -> },
                onPause = {},
                onResume = {},
            )
        }
    }

    @Test
    fun `lowWatermark must be positive`() {
        assertThrows<IllegalArgumentException> {
            TerminalFlowController(
                highWatermark = 5,
                lowWatermark = 0,
                callbackByteLimit = 100,
                onWrite = { _, _ -> },
                onPause = {},
                onResume = {},
            )
        }
    }

    @Test
    fun `callbackByteLimit must be positive`() {
        assertThrows<IllegalArgumentException> {
            TerminalFlowController(
                highWatermark = 5,
                lowWatermark = 2,
                callbackByteLimit = 0,
                onWrite = { _, _ -> },
                onPause = {},
                onResume = {},
            )
        }
    }

    // ── Fast path: writes below the byte limit ─────────────────────

    @Test
    fun `small writes below byte limit use fast path without ack`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        // Write 10 bytes x 9 = 90 bytes, all below the 100 byte limit
        repeat(9) { fc.write(chunk(10)) }

        assertEquals(9, rec.fastWrites)
        assertEquals(0, rec.ackWrites)
        assertEquals(0, rec.pauseCount.get())
        assertFalse(fc.isPaused)
        assertEquals(90, fc.bytesWritten)
        assertEquals(0, fc.pendingCallbacks)
    }

    @Test
    fun `single write smaller than byte limit uses fast path`() {
        val (fc, rec) = controller(callbackByteLimit = 1000)

        fc.write(chunk(999))

        assertEquals(1, rec.fastWrites)
        assertEquals(0, rec.ackWrites)
        assertEquals(999, fc.bytesWritten)
    }

    // ── Ack trigger at byte limit boundary ─────────────────────────

    @Test
    fun `write that reaches byte limit triggers ack`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        // 99 bytes: fast path
        fc.write(chunk(99))
        assertEquals(1, rec.fastWrites)
        assertEquals(0, rec.ackWrites)

        // 1 more byte reaches 100: ack path
        fc.write(chunk(1))
        assertEquals(1, rec.fastWrites)
        assertEquals(1, rec.ackWrites)
        assertEquals(0, fc.bytesWritten) // counter resets after ack-flagged write
        assertEquals(1, fc.pendingCallbacks)
    }

    @Test
    fun `write that exceeds byte limit triggers ack`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        fc.write(chunk(150))

        assertEquals(0, rec.fastWrites)
        assertEquals(1, rec.ackWrites)
        assertEquals(0, fc.bytesWritten)
        assertEquals(1, fc.pendingCallbacks)
    }

    @Test
    fun `single write exactly at byte limit triggers ack`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        fc.write(chunk(100))

        assertEquals(0, rec.fastWrites)
        assertEquals(1, rec.ackWrites)
        assertEquals(0, fc.bytesWritten)
        assertEquals(1, fc.pendingCallbacks)
    }

    @Test
    fun `byte counter resets after each ack-flagged write`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        // First cycle: 100 bytes -> ack
        fc.write(chunk(100))
        assertEquals(0, fc.bytesWritten)
        assertEquals(1, fc.pendingCallbacks)

        // Second cycle: 50 (fast) + 50 (reaches 100 -> ack)
        fc.write(chunk(50))
        assertEquals(50, fc.bytesWritten)
        fc.write(chunk(50))
        assertEquals(0, fc.bytesWritten)
        assertEquals(2, fc.pendingCallbacks)

        assertEquals(1, rec.fastWrites)
        assertEquals(2, rec.ackWrites)
    }

    // ── Pause at high watermark ────────────────────────────────────

    @Test
    fun `pause fires when pending callbacks exceed high watermark`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 1, callbackByteLimit = 10)

        // 4 ack writes -> pending = 4, exceeds high=3 -> pause
        repeat(4) { fc.write(chunk(10)) }

        assertEquals(4, fc.pendingCallbacks)
        assertEquals(1, rec.pauseCount.get())
        assertTrue(fc.isPaused)
    }

    @Test
    fun `pause does not fire at exactly high watermark`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 1, callbackByteLimit = 10)

        // 3 ack writes -> pending = 3, equals high=3, should NOT pause
        repeat(3) { fc.write(chunk(10)) }

        assertEquals(3, fc.pendingCallbacks)
        assertEquals(0, rec.pauseCount.get())
        assertFalse(fc.isPaused)
    }

    @Test
    fun `pause is idempotent - fires only once even with continued writes`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 1, callbackByteLimit = 10)

        // Write 10 ack chunks. Pause should fire once at pending=4, not again.
        repeat(10) { fc.write(chunk(10)) }

        assertEquals(10, fc.pendingCallbacks)
        assertEquals(1, rec.pauseCount.get())
        assertTrue(fc.isPaused)
    }

    // ── Resume at low watermark ────────────────────────────────────

    @Test
    fun `resume fires when acks bring pending below low watermark`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 1, callbackByteLimit = 10)

        // Push to paused: 4 writes, pending=4
        repeat(4) { fc.write(chunk(10)) }
        assertTrue(fc.isPaused)

        // Ack 3 times: pending=3,2,1 -- resume fires when pending drops below low=1
        fc.ack() // pending = 3
        assertFalse(rec.resumeCount.get() > 0)
        fc.ack() // pending = 2
        assertFalse(rec.resumeCount.get() > 0)
        fc.ack() // pending = 1
        assertFalse(rec.resumeCount.get() > 0)

        fc.ack() // pending = 0 < low=1 -> resume!
        assertEquals(1, rec.resumeCount.get())
        assertFalse(fc.isPaused)
        assertEquals(0, fc.pendingCallbacks)
    }

    @Test
    fun `resume does not fire when pending equals low watermark`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 2, callbackByteLimit = 10)

        // Push to paused: 4 writes, pending=4
        repeat(4) { fc.write(chunk(10)) }
        assertTrue(fc.isPaused)

        // Ack down to pending=2 (equals low=2): should NOT resume
        fc.ack() // pending = 3
        fc.ack() // pending = 2
        assertEquals(0, rec.resumeCount.get())
        assertTrue(fc.isPaused)
    }

    @Test
    fun `resume is idempotent - does not fire when not paused`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 1, callbackByteLimit = 10)

        // Never paused, ack should not trigger resume
        fc.write(chunk(10))
        fc.ack()

        assertEquals(0, rec.resumeCount.get())
        assertFalse(fc.isPaused)
    }

    // ── Ack underflow protection ───────────────────────────────────

    @Test
    fun `ack never drops pending callbacks below zero`() {
        val (fc, _) = controller(callbackByteLimit = 10)

        fc.write(chunk(10)) // pending = 1
        fc.ack() // pending = 0
        fc.ack() // spurious ack
        fc.ack() // spurious ack

        assertEquals(0, fc.pendingCallbacks)
    }

    // ── Full pause-resume cycle ────────────────────────────────────

    @Test
    fun `full cycle - pause then resume then pause again`() {
        val (fc, rec) = controller(highWatermark = 3, lowWatermark = 1, callbackByteLimit = 10)

        // Phase 1: build up to pause
        repeat(4) { fc.write(chunk(10)) }
        assertTrue(fc.isPaused)
        assertEquals(1, rec.pauseCount.get())

        // Phase 2: drain to resume
        repeat(4) { fc.ack() }
        assertFalse(fc.isPaused)
        assertEquals(1, rec.resumeCount.get())

        // Phase 3: build up to pause again
        repeat(4) { fc.write(chunk(10)) }
        assertTrue(fc.isPaused)
        assertEquals(2, rec.pauseCount.get())

        // Phase 4: drain to resume again
        repeat(4) { fc.ack() }
        assertFalse(fc.isPaused)
        assertEquals(2, rec.resumeCount.get())
    }

    // ── Mixed fast and ack writes ──────────────────────────────────

    @Test
    fun `interleaved small and large writes produce correct mix`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        fc.write(chunk(30)) // fast (30)
        fc.write(chunk(30)) // fast (60)
        fc.write(chunk(30)) // fast (90)
        fc.write(chunk(30)) // ack (120 >= 100)
        fc.write(chunk(10)) // fast (10)
        fc.write(chunk(200)) // ack (210 >= 100)

        assertEquals(4, rec.fastWrites)
        assertEquals(2, rec.ackWrites)
        assertEquals(2, fc.pendingCallbacks)
    }

    @Test
    fun `many small writes accumulate to trigger ack`() {
        val (fc, rec) = controller(callbackByteLimit = 100)

        // 100 writes of 1 byte each: 100th write triggers ack
        repeat(100) { fc.write(chunk(1)) }

        assertEquals(99, rec.fastWrites)
        assertEquals(1, rec.ackWrites)
        assertEquals(1, fc.pendingCallbacks)
    }

    // ── Data integrity: all writes forwarded ───────────────────────

    @Test
    fun `all data is forwarded regardless of ack flag`() {
        val (fc, rec) = controller(callbackByteLimit = 50)

        val sizes = listOf(10, 20, 30, 40, 50, 60)
        sizes.forEach { fc.write(chunk(it)) }

        // Every write should be forwarded exactly once
        assertEquals(sizes.size, rec.writes.size)
        assertEquals(sizes, rec.writes.map { it.size })
    }

    @Test
    fun `data content is preserved through write`() {
        val captured = mutableListOf<ByteArray>()
        val fc =
            TerminalFlowController(
                callbackByteLimit = 1000,
                onWrite = { data, _ -> captured.add(data) },
                onPause = {},
                onResume = {},
            )

        val original = byteArrayOf(0x00, 0x7F, 0x48, 0x65, 0x6C, 0x6C, 0x6F)
        fc.write(original)

        assertEquals(1, captured.size)
        assertTrue(original.contentEquals(captured[0]))
    }

    // ── Edge case: lowWatermark = 0 is rejected ─────────────────────

    @Test
    fun `lowWatermark zero is rejected to prevent deadlock`() {
        assertThrows<IllegalArgumentException> {
            TerminalFlowController(
                highWatermark = 2,
                lowWatermark = 0,
                callbackByteLimit = 10,
                onWrite = { _, _ -> },
                onPause = {},
                onResume = {},
            )
        }
    }

    // ── Edge case: high=2, low=1 is the minimum useful config ──────

    @Test
    fun `minimum useful watermarks high=2 low=1`() {
        val (fc, rec) = controller(highWatermark = 2, lowWatermark = 1, callbackByteLimit = 10)

        // 3 ack writes: pending=3 > high=2 -> pause
        repeat(3) { fc.write(chunk(10)) }
        assertTrue(fc.isPaused)
        assertEquals(1, rec.pauseCount.get())

        // ack to pending=0 < low=1 -> resume
        fc.ack() // pending = 2
        fc.ack() // pending = 1
        assertEquals(0, rec.resumeCount.get())
        fc.ack() // pending = 0 < 1 -> resume!
        assertEquals(1, rec.resumeCount.get())
        assertFalse(fc.isPaused)
    }

    // ── Realistic scenario ─────────────────────────────────────────

    @Test
    fun `realistic scenario with default-like watermarks`() {
        val (fc, rec) =
            controller(
                highWatermark = 5,
                lowWatermark = 2,
                callbackByteLimit = 100_000,
            )

        // Simulate a burst: 800KB in 8KB chunks = 100 writes
        // Every 100KB (about 13 writes) triggers an ack-flagged write
        repeat(100) { fc.write(chunk(8192)) }

        // 800KB / 100KB = 8 ack writes expected (at 100KB, 200KB, ..., 800KB)
        // Since 8192 * 13 = 106496 >= 100000, every ~13th write triggers ack
        val expectedAcks = rec.ackWrites
        assertTrue(expectedAcks in 6..10, "Expected ~8 ack writes, got $expectedAcks")
        assertEquals(100, rec.writes.size, "All 100 writes should be forwarded")

        // pending = expectedAcks > high=5, so should be paused
        assertTrue(fc.isPaused)
        assertEquals(1, rec.pauseCount.get())

        // Drain all acks: should resume when pending < low=2
        repeat(expectedAcks) { fc.ack() }
        assertFalse(fc.isPaused)
        assertEquals(1, rec.resumeCount.get())
    }

    // ── Thread safety ──────────────────────────────────────────────

    @Test
    fun `concurrent writes do not corrupt state`() {
        val rec = Recorder()
        val fc =
            TerminalFlowController(
                highWatermark = 100,
                lowWatermark = 50,
                callbackByteLimit = 100,
                onWrite = rec::onWrite,
                onPause = rec::onPause,
                onResume = rec::onResume,
            )

        val writerCount = 8
        val writesPerThread = 1000
        val barrier = CyclicBarrier(writerCount)
        val latch = CountDownLatch(writerCount)

        repeat(writerCount) {
            Thread {
                barrier.await()
                repeat(writesPerThread) { fc.write(chunk(50)) }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Writers should complete within timeout")

        val totalWrites = writerCount * writesPerThread
        assertEquals(totalWrites, rec.writes.size, "All writes must be forwarded")

        // pendingCallbacks should be non-negative
        assertTrue(fc.pendingCallbacks >= 0, "pendingCallbacks should never be negative")
    }

    @Test
    fun `concurrent writes and acks do not corrupt state`() {
        val rec = Recorder()
        val fc =
            TerminalFlowController(
                highWatermark = 10,
                lowWatermark = 3,
                callbackByteLimit = 100,
                onWrite = rec::onWrite,
                onPause = rec::onPause,
                onResume = rec::onResume,
            )

        val writerCount = 4
        val ackerCount = 4
        val writesPerThread = 500
        val acksPerThread = 500
        val barrier = CyclicBarrier(writerCount + ackerCount)
        val latch = CountDownLatch(writerCount + ackerCount)

        // Writers
        repeat(writerCount) {
            Thread {
                barrier.await()
                repeat(writesPerThread) { fc.write(chunk(50)) }
                latch.countDown()
            }.start()
        }

        // Ackers
        repeat(ackerCount) {
            Thread {
                barrier.await()
                repeat(acksPerThread) { fc.ack() }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete within timeout")

        // Core invariant: pending callbacks never negative
        assertTrue(fc.pendingCallbacks >= 0, "pendingCallbacks should never be negative")

        // All writes forwarded
        val totalWrites = writerCount * writesPerThread
        assertEquals(totalWrites, rec.writes.size, "All writes must be forwarded")
    }

    @Test
    fun `pause and resume counts are consistent under concurrency`() {
        val rec = Recorder()
        val fc =
            TerminalFlowController(
                highWatermark = 5,
                lowWatermark = 2,
                callbackByteLimit = 10,
                onWrite = rec::onWrite,
                onPause = rec::onPause,
                onResume = rec::onResume,
            )

        val barrier = CyclicBarrier(2)
        val latch = CountDownLatch(2)

        // Writer thread: produces enough to trigger multiple pause cycles
        Thread {
            barrier.await()
            repeat(200) { fc.write(chunk(10)) }
            latch.countDown()
        }.start()

        // Acker thread: drains all acks
        Thread {
            barrier.await()
            repeat(200) { fc.ack() }
            latch.countDown()
        }.start()

        assertTrue(latch.await(10, TimeUnit.SECONDS))

        // Resume should never exceed pause count (could equal if fully drained)
        assertTrue(
            rec.resumeCount.get() <= rec.pauseCount.get(),
            "Resume count (${rec.resumeCount.get()}) should not exceed pause count (${rec.pauseCount.get()})",
        )

        // Pending callbacks should be non-negative
        assertTrue(fc.pendingCallbacks >= 0)
    }

    // ── Exposed state getters ──────────────────────────────────────

    @Test
    fun `bytesWritten tracks accumulated bytes correctly`() {
        val (fc, _) = controller(callbackByteLimit = 100)

        fc.write(chunk(25))
        assertEquals(25, fc.bytesWritten)

        fc.write(chunk(25))
        assertEquals(50, fc.bytesWritten)

        fc.write(chunk(25))
        assertEquals(75, fc.bytesWritten)

        // This one crosses the limit: 100 >= 100, triggers ack, resets to 0
        fc.write(chunk(25))
        assertEquals(0, fc.bytesWritten)
    }

    @Test
    fun `pendingCallbacks increments on ack writes and decrements on acks`() {
        val (fc, _) = controller(callbackByteLimit = 10)

        assertEquals(0, fc.pendingCallbacks)

        fc.write(chunk(10))
        assertEquals(1, fc.pendingCallbacks)

        fc.write(chunk(10))
        assertEquals(2, fc.pendingCallbacks)

        fc.ack()
        assertEquals(1, fc.pendingCallbacks)

        fc.ack()
        assertEquals(0, fc.pendingCallbacks)
    }

    @Test
    fun `isPaused reflects current pause state`() {
        val (fc, _) = controller(highWatermark = 2, lowWatermark = 1, callbackByteLimit = 10)

        assertFalse(fc.isPaused)

        repeat(3) { fc.write(chunk(10)) } // pending=3 > high=2
        assertTrue(fc.isPaused)

        repeat(3) { fc.ack() } // pending=0 < low=1
        assertFalse(fc.isPaused)
    }
}
