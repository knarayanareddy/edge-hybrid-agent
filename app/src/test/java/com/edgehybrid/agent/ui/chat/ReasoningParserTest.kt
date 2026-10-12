package com.edgehybrid.agent.ui.chat

import com.edgehybrid.agent.ui.chat.components.ReasoningParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for o1/o3 and DeepSeek-R1 style reasoning token parser.
 */
class ReasoningParserTest {

    @Test
    fun `plain text message returns null reasoning and unmodified content`() {
        val input = "Hello, how can I help you today?"
        val parsed = ReasoningParser.parse(input)
        assertNull(parsed.reasoningText)
        assertEquals(input, parsed.cleanedAnswerText)
    }

    @Test
    fun `closed think block extracts reasoning and strips it from answer`() {
        val input = "<think>\nFirst check the device battery status.\nThen report percentage.\n</think>\nYour battery is at 85%."
        val parsed = ReasoningParser.parse(input)
        assertEquals("First check the device battery status.\nThen report percentage.", parsed.reasoningText)
        assertEquals("Your battery is at 85%.", parsed.cleanedAnswerText)
    }

    @Test
    fun `streaming unclosed think tag isolates partial reasoning without polluting answer`() {
        val input = "<think>\nAnalyzing the user prompt and preparing tools..."
        val parsed = ReasoningParser.parse(input)
        assertEquals("Analyzing the user prompt and preparing tools...", parsed.reasoningText)
        assertEquals("", parsed.cleanedAnswerText)
    }

    @Test
    fun `unclosed think tag after initial text preserves prefix in cleaned answer`() {
        val input = "Here is what I found so far:\n<think>Verifying the second constraint"
        val parsed = ReasoningParser.parse(input)
        assertEquals("Verifying the second constraint", parsed.reasoningText)
        assertEquals("Here is what I found so far:", parsed.cleanedAnswerText)
    }

    @Test
    fun `empty think block yields null reasoning`() {
        val input = "<think>   </think>Direct answer here."
        val parsed = ReasoningParser.parse(input)
        assertNull(parsed.reasoningText)
        assertEquals("Direct answer here.", parsed.cleanedAnswerText)
    }
}
