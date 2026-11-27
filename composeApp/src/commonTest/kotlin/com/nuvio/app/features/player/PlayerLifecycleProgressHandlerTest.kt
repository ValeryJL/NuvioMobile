package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLifecycleProgressHandlerTest {
    @Test
    fun `suppresses duplicate durable writes and retries after transient failure`() {
        val handler = PlayerLifecycleProgressHandler()
        val snapshot = PlayerPlaybackSnapshot(positionMs = 45_000L, durationMs = 120_000L, isEnded = false)
        var calls = 0

        // First attempt returns false (not durable): next identical call must still run.
        handler.flush(snapshot, shouldStopPlayback = false) { _, _ ->
            calls += 1
            false
        }
        handler.flush(snapshot, shouldStopPlayback = false) { _, _ ->
            calls += 1
            true
        }
        // Already durable for same snapshot + shouldStopPlayback=false: must be skipped.
        handler.flush(snapshot, shouldStopPlayback = false) { _, _ ->
            calls += 1
            true
        }
        // State transition to shouldStopPlayback=true must flush and emit stop scrobble.
        handler.flush(snapshot, shouldStopPlayback = true) { _, stopScrobble ->
            if (stopScrobble) calls += 1
            true
        }

        assertEquals(3, calls)
    }
}
