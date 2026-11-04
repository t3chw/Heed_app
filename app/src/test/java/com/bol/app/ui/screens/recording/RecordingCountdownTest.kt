package com.bol.app.ui.screens.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCountdownTest {
    @Test fun `starts at the full minute`() = assertEquals(60, RecordingCountdown.secondsLeft(0))
    @Test fun `counts down in whole seconds`() {
        assertEquals(60, RecordingCountdown.secondsLeft(1))
        assertEquals(59, RecordingCountdown.secondsLeft(1000))
        assertEquals(5, RecordingCountdown.secondsLeft(55_000))
        assertEquals(1, RecordingCountdown.secondsLeft(59_999))
    }
    @Test fun `never goes below zero`() {
        assertEquals(0, RecordingCountdown.secondsLeft(60_000))
        assertEquals(0, RecordingCountdown.secondsLeft(60_400))
    }
}
