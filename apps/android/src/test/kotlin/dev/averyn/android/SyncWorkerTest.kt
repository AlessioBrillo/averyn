package dev.averyn.android

import dev.averyn.sync.SyncRunResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncWorkerTest {
    @Test
    fun retriesOnlyWhatCanStillSucceedWithoutTheUser() {
        assertEquals(true, shouldRetry(SyncRunResult(uploaded = 1, retryable = 1, permanent = 0, needsLogin = false)))
        assertEquals(false, shouldRetry(SyncRunResult(uploaded = 2, retryable = 0, permanent = 0, needsLogin = false)))
        assertEquals(false, shouldRetry(SyncRunResult(uploaded = 0, retryable = 0, permanent = 3, needsLogin = false)))
        // Signed out or token refused: backoff retries would just fail again; signing in enqueues a fresh run.
        assertEquals(false, shouldRetry(SyncRunResult(uploaded = 0, retryable = 1, permanent = 0, needsLogin = true)))
    }
}
