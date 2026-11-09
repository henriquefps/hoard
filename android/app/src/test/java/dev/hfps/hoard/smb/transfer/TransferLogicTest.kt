package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLogicTest {

    private fun retry(decision: FailoverPlan.Decision) = decision as FailoverPlan.Decision.Retry

    @Test
    fun `retries the same address with backoff, then fails over in order`() {
        val plan = FailoverPlan(addressCount = 3, attemptsPerAddress = 3, rounds = 1, baseDelayMs = 1000)
        assertEquals(FailoverPlan.Decision.Retry(0, 1000), plan.onFailure(ErrorCode.UNREACHABLE, false))
        assertEquals(FailoverPlan.Decision.Retry(0, 2000), plan.onFailure(ErrorCode.TIMEOUT, false))
        val third = retry(plan.onFailure(ErrorCode.UNREACHABLE, false))
        assertEquals(1, third.addressIndex)
        assertEquals(1, retry(plan.onFailure(ErrorCode.UNREACHABLE, false)).addressIndex)
        retry(plan.onFailure(ErrorCode.UNREACHABLE, false))
        assertEquals(2, retry(plan.onFailure(ErrorCode.UNREACHABLE, false)).addressIndex)
        retry(plan.onFailure(ErrorCode.UNREACHABLE, false))
        retry(plan.onFailure(ErrorCode.UNREACHABLE, false))
        assertEquals(FailoverPlan.Decision.GiveUp, plan.onFailure(ErrorCode.UNREACHABLE, false))
    }

    @Test
    fun `a second round walks the list again from the top`() {
        val plan = FailoverPlan(addressCount = 2, attemptsPerAddress = 1, rounds = 2, baseDelayMs = 1000)
        assertEquals(1, retry(plan.onFailure(ErrorCode.UNREACHABLE, false)).addressIndex)
        val wrap = retry(plan.onFailure(ErrorCode.UNREACHABLE, false))
        assertEquals(0, wrap.addressIndex)
        assertTrue(wrap.delayMs >= 1000)
        assertEquals(1, retry(plan.onFailure(ErrorCode.UNREACHABLE, false)).addressIndex)
        assertEquals(FailoverPlan.Decision.GiveUp, plan.onFailure(ErrorCode.UNREACHABLE, false))
    }

    @Test
    fun `GUID mismatch skips the address immediately`() {
        val plan = FailoverPlan(addressCount = 2)
        assertEquals(FailoverPlan.Decision.Retry(1, 0), plan.onFailure(ErrorCode.GUID_MISMATCH, false))
    }

    @Test
    fun `fatal errors give up at once`() {
        for (code in listOf(ErrorCode.AUTH_FAILED, ErrorCode.ACCESS_DENIED, ErrorCode.NOT_FOUND, ErrorCode.NO_SPACE, ErrorCode.PERMISSION_REVOKED, ErrorCode.CANCELLED)) {
            assertEquals(code.name, FailoverPlan.Decision.GiveUp, FailoverPlan(3).onFailure(code, false))
        }
        assertEquals(FailoverPlan.Decision.GiveUp, FailoverPlan(3).onFailure(ErrorCode.UNKNOWN, false, fatal = true))
    }

    @Test
    fun `progress resets the attempt counter`() {
        val plan = FailoverPlan(addressCount = 2, attemptsPerAddress = 2, baseDelayMs = 1000)
        assertEquals(FailoverPlan.Decision.Retry(0, 1000), plan.onFailure(ErrorCode.UNREACHABLE, false))
        // The retry moved bytes before dropping again: still the same (working) address.
        assertEquals(FailoverPlan.Decision.Retry(0, 1000), plan.onFailure(ErrorCode.UNREACHABLE, true))
        assertEquals(1, retry(plan.onFailure(ErrorCode.UNREACHABLE, false)).addressIndex)
    }

    @Test
    fun `backoff is capped`() {
        val plan = FailoverPlan(addressCount = 1, attemptsPerAddress = 20, baseDelayMs = 1000, maxDelayMs = 15000)
        var last = 0L
        repeat(19) { last = retry(plan.onFailure(ErrorCode.TIMEOUT, false)).delayMs }
        assertEquals(15000, last)
    }

    @Test
    fun `throttle emits at most every 250 ms and only on 1 percent or 1 MiB`() {
        val throttle = ProgressThrottle()
        val total = 1_000L * 1024 * 1024 // 1000 MiB: 1% = 10 MiB, so the 1 MiB step applies
        throttle.mark(0, 0)
        assertFalse(throttle.shouldEmit(100, 5L shl 20, total)) // too soon
        assertTrue(throttle.shouldEmit(250, 5L shl 20, total))
        throttle.mark(250, 5L shl 20)
        assertFalse(throttle.shouldEmit(600, (5L shl 20) + 1000, total)) // tiny step
        assertTrue(throttle.shouldEmit(1300, (5L shl 20) + 1000, total)) // heartbeat after 1 s
        assertFalse(throttle.shouldEmit(5000, 5L shl 20, total)) // nothing moved
    }

    @Test
    fun `throttle uses 1 percent for small files`() {
        val throttle = ProgressThrottle()
        throttle.mark(0, 0)
        assertFalse(throttle.shouldEmit(300, 500, 100_000)) // 0.5%
        assertTrue(throttle.shouldEmit(300, 1_000, 100_000)) // 1%
    }

    @Test
    fun `speed is a rolling average over the window`() {
        val meter = SpeedMeter(windowMs = 3000)
        assertNull(meter.bytesPerSecond())
        meter.add(0, 0)
        meter.add(1000, 1_000_000)
        assertEquals(1_000_000L, meter.bytesPerSecond())
        meter.add(2000, 3_000_000)
        assertEquals(1_500_000L, meter.bytesPerSecond())
        meter.add(10_000, 3_000_000) // stalled: old samples age out
        meter.add(11_000, 3_500_000)
        assertEquals(500_000L, meter.bytesPerSecond())
        meter.reset()
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun `local files resume after whatever the partial file holds`() {
        assertEquals(7000, ResumeRules.offset(persisted = 4096, partSize = 7000, total = 10_000, sourceUnchanged = true, contiguous = true))
        assertEquals(7000, ResumeRules.offset(persisted = 9000, partSize = 7000, total = 10_000, sourceUnchanged = true, contiguous = true))
        assertEquals(0, ResumeRules.offset(persisted = 4096, partSize = null, total = 10_000, sourceUnchanged = true, contiguous = true))
        assertEquals(0, ResumeRules.offset(persisted = 4096, partSize = 12_000, total = 10_000, sourceUnchanged = true, contiguous = true))
    }

    @Test
    fun `SMB uploads resume at the persisted offset only while the server file still has it`() {
        // Pipelined writes: bytes past the persisted offset may hide a hole.
        assertEquals(4096, ResumeRules.offset(persisted = 4096, partSize = 7000, total = 10_000, sourceUnchanged = true, contiguous = false))
        assertEquals(4096, ResumeRules.offset(persisted = 4096, partSize = 4096, total = 10_000, sourceUnchanged = true, contiguous = false))
        assertEquals(0, ResumeRules.offset(persisted = 4096, partSize = 3000, total = 10_000, sourceUnchanged = true, contiguous = false))
        assertEquals(0, ResumeRules.offset(persisted = 0, partSize = 7000, total = 10_000, sourceUnchanged = true, contiguous = false))
    }

    @Test
    fun `a changed source always restarts`() {
        assertEquals(0, ResumeRules.offset(persisted = 4096, partSize = 7000, total = 10_000, sourceUnchanged = false, contiguous = true))
        assertEquals(0, ResumeRules.offset(persisted = 4096, partSize = 7000, total = 10_000, sourceUnchanged = false, contiguous = false))
    }

    @Test
    fun `partial names are hidden, recognisable and fit 255 characters`() {
        assertEquals(".photo.jpg.hoardpart", PartNames.of("photo.jpg"))
        assertTrue(PartNames.isPart(PartNames.of("x")))
        assertEquals(255, PartNames.of("a".repeat(300)).length)
    }

    @Test
    fun `failure scopes`() {
        assertEquals(FailureScope.Scope.RETRY, FailureScope.of(ErrorCode.UNREACHABLE, network = true))
        assertEquals(FailureScope.Scope.ITEM, FailureScope.of(ErrorCode.UNKNOWN, network = false))
        assertEquals(FailureScope.Scope.JOB, FailureScope.of(ErrorCode.NO_SPACE, network = true))
        assertEquals(FailureScope.Scope.JOB, FailureScope.of(ErrorCode.UNAVAILABLE, network = false))
        assertEquals(FailureScope.Scope.ITEM, FailureScope.of(ErrorCode.ACCESS_DENIED, network = true))
        assertEquals(FailureScope.Scope.ITEM, FailureScope.of(ErrorCode.NOT_FOUND, network = true))
    }

    @Test
    fun `paths`() {
        assertTrue(TransferPaths.isSameOrInside("DCIM", "DCIM/Camera"))
        assertTrue(TransferPaths.isSameOrInside("DCIM", "DCIM"))
        assertFalse(TransferPaths.isSameOrInside("DCIM", "DCIMX"))
        assertEquals("b/c/x", TransferPaths.rebase("a/x", "a", "b/c"))
    }
}
