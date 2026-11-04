package com.bol.app.ai.parser

import com.bol.app.data.local.DefaultCategories
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceIntentParserTest {
    @Test
    fun `prompt matches the training format exactly`() {
        val expected = "Categories:\n- tasks (General): general things to do\n- buy (Buy): things to purchase\n" +
            "- watch (Watch): movies, shows, videos to see\n- ideas (Ideas): thoughts to remember\n" +
            "- work (Work): office and job tasks\n- journal (Journal): feelings, emotions, stories with no action\n" +
            "Note: call the bank\nTasks:\n"
        assertEquals(expected, OnDeviceIntentParser.prompt(DefaultCategories.list, "call the bank"))
    }
}
