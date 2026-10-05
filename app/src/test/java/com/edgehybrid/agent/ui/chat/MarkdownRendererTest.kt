package com.edgehybrid.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the markdown parser.
 *
 * These exist because the previous commit claimed the renderer was "unit-testable
 * on the JVM" and did not ship a single test for it. The claim was the reason to
 * hand-roll the parser over `compose-richtext`, so it had to be true.
 *
 * `parse` is a pure function taking a [MarkdownRenderer.Palette], so these run
 * without a Compose runtime.
 */
class MarkdownRendererTest {

    private val palette = MarkdownRenderer.Palette(
        body = Color.Black,
        muted = Color.Gray,
        accent = Color.Blue,
        codeBackground = Color.LightGray
    )

    private val body = 16.sp

    private fun render(md: String) = MarkdownRenderer.parse(md, body, palette)

    private fun plain(md: String) = render(md).text

    // ---- the actual bug this fixes

    @Test
    fun `literal asterisks are consumed by bold and heading markers`() {
        val out = plain("**Innovation Execution: the Tesla way,**")
        assertFalse("bold markers must not survive into the output", out.contains("**"))
        assertEquals("Innovation Execution: the Tesla way,", out)
    }

    @Test
    fun `a heading marker is stripped from the text`() {
        val out = plain("### Core Principles")
        assertFalse(out.contains("#"))
        assertEquals("Core Principles", out)
    }

    @Test
    fun `the run-together reply from the screenshot renders as heading plus list`() {
        val reply = """
            ### Core Principles
            1. **Absolutely High Targets**: push goals, no negotiation.
            2. **Innovation Everywhere**: simplify, then remove.
        """.trimIndent()

        val out = plain(reply)
        assertFalse(out.contains("###"))
        assertFalse(out.contains("**"))
        assertTrue("headings and list items must be on separate lines", out.contains("\n"))
        assertEquals(3, out.lines().count { it.isNotBlank() })
    }

    // ---- inline spans

    @Test
    fun `bold applies a heavier weight and keeps its text`() {
        val spans = render("Push **harder**").spanStyles
        assertTrue("expected a bold span", spans.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(render("Push **harder**").text.contains("harder"))
    }

    @Test
    fun `italic applies only inside the markers`() {
        val out = render("this is *emphasised* text")
        assertFalse(out.contains("*"))
        assertTrue(out.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `snake_case identifiers are not italicised`() {
        // The reason the italic branch requires a space-free run: half of
        // `user_id` rendering in italics is worse than no italics at all.
        val out = render("field user_id_name is required")
        assertTrue(
            "a snake_case identifier must not be styled",
            out.spanStyles.none { it.item.fontStyle == FontStyle.Italic }
        )
        assertTrue(out.text.contains("user_id_name"))
    }

    @Test
    fun `unterminated bold stays literal instead of swallowing the rest`() {
        val out = plain("a **dangling marker")
        assertEquals("a **dangling marker", out)
    }

    @Test
    fun `strike-through is consumed`() {
        val out = plain("~~old text~~ new text")
        assertFalse(out.contains("~~"))
        assertTrue(out.contains("old text"))
    }

    // ---- inline code

    @Test
    fun `inline code keeps its text and does not eat surrounding prose`() {
        val out = render("run `npm test` now")
        assertTrue(out.text.contains("npm test"))
        assertTrue(out.text.contains("now"))
        assertFalse("backticks must not survive", out.text.contains("`"))
    }

    @Test
    fun `bold markers inside inline code are not treated as bold`() {
        val out = render("use `**literal**` here")
        assertEquals("use **literal** here", out.text)
    }

    // ---- lists

    @Test
    fun `bullets get a real bullet glyph`() {
        val out = plain("- first\n- second")
        assertTrue(out.contains("•"))
        assertFalse("the hyphen must be replaced, not kept", out.contains("- first"))
    }

    @Test
    fun `ordered lists keep their numbering`() {
        val out = plain("1. one\n2. two")
        assertTrue(out.contains("1."))
        assertTrue(out.contains("2."))
    }

    @Test
    fun `task list items render a checkbox state`() {
        assertTrue(plain("- [x] done thing").contains("✓"))
        assertTrue(plain("- [ ] open thing").contains("○"))
    }

    // ---- fenced code

    @Test
    fun `fenced code is preserved verbatim and unstyled`() {
        val snippet = """
            ```bash
            echo **not bold** and `not code`
            ```
        """.trimIndent()

        val out = render(snippet)
        assertTrue(out.text.contains("echo **not bold** and `not code`"))
        assertTrue("fences themselves must not render", !out.text.contains("```"))
    }

    // ---- headings

    @Test
    fun `headings are larger than body text and ordered by level`() {
        val h1 = render("# One").spanStyles.first().item.fontSize
        val h2 = render("## Two").spanStyles.first().item.fontSize
        val bodySize = render("plain").let { 16.sp }
        assertTrue("h1 should exceed h2", h1!! > h2!!)
        assertTrue("h2 should exceed body", h2!! > bodySize)
    }

    @Test
    fun `a heading past h6 is clamped rather than crashing`() {
        val out = plain("####### seven hashes")
        assertFalse(out.contains("#"))
    }

    // ---- blocks and spacing

    @Test
    fun `a blank line separates blocks but ordinary wrapped lines do not gain gaps`() {
        val twoBlocks = plain("para one\n\npara two")
        assertTrue("a real paragraph break should survive", twoBlocks.contains("\n\n"))

        val oneBlock = plain("line one\nline two")
        assertFalse("a soft wrap must not become a paragraph gap", oneBlock.contains("\n\n"))
    }

    @Test
    fun `blockquote gets a rule marker`() {
        assertTrue(plain("> quoted").contains("▏"))
    }

    // ---- robustness

    @Test
    fun `empty and blank input produce empty output rather than throwing`() {
        assertEquals("", plain(""))
        assertEquals("", plain("   \n  \n "))
    }

    @Test
    fun `carriage returns do not survive`() {
        assertFalse(plain("a\r\nb").contains("\r"))
    }

    @Test
    fun `an unclosed fence still renders its contents`() {
        val out = plain("```\nplain code")
        assertTrue(out.contains("plain code"))
    }

    @Test
    fun `palette actually reaches the output`() {
        // Proves the parse is not silently hardcoding colours, which would mean the
        // light/dark wiring is untested.
        val dark = MarkdownRenderer.parse("# x", body,
            palette.copy(body = Color.White))
        val light = MarkdownRenderer.parse("# x", body,
            palette.copy(body = Color.Black))
        assertNotEquals(
            dark.spanStyles.first().item.color,
            light.spanStyles.first().item.color
        )
    }
}
