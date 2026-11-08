package dev.averyn.tracking

import kotlin.test.Test
import kotlin.test.assertEquals

class PreparingNoticeTest {
    @Test
    fun theNoticeComesAfterTheThresholdAndNeverBefore() {
        assertEquals(false, preparingTooLong(nowMs = 1_000 + PREPARING_NOTICE_MS - 1, startedMs = 1_000))
        assertEquals(true, preparingTooLong(nowMs = 1_000 + PREPARING_NOTICE_MS, startedMs = 1_000))
    }

    @Test
    fun anUnknownStartNeverTriggersIt() {
        assertEquals(false, preparingTooLong(nowMs = 10 * PREPARING_NOTICE_MS, startedMs = 0))
    }
}
