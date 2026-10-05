package com.edgehybrid.agent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * Renders the subset of Markdown that LLMs actually emit.
 *
 * ## Why hand-rolled instead of a library
 *
 * The alternative was `compose-richtext`: a heavier dependency for a narrower need.
 * This app renders chat messages, not documents. The markdown a chat model produces
 * is bold, italics, inline code, fenced code, headings, lists, blockquotes and task
 * lists. Hand-rolling covers exactly that, adds no dependency, and — the reason it
 * won — makes the behaviour unit-testable on the JVM, which a composable-only
 * renderer would not be.
 *
 * The symptom it fixes: assistant output printed `**Innovation Execution: the Tesla
 * way,**` and `### Core Principles` literally, because the body was a plain
 * `Text(message.content)`.
 *
 * ## Block model
 *
 * Input is split on blank lines into blocks, and each block is rendered line by
 * line. Separating on blank lines is what keeps a run-together message like
 *
 * ```
 * ### Core Principles
 * 1. **Absurdly High Targets**: push goals...
 * 2. **Innovation Everywhere**: simplify...
 * ```
 *
 * rendering as a heading plus a list rather than one paragraph. Adding a blank line
 * before *every* line instead would double-space ordinary wrapped prose.
 */
object MarkdownRenderer {

    /** Resolved colours, passed in so the parser itself stays pure. */
    data class Palette(
        val body: Color,
        val muted: Color,
        val accent: Color,
        val codeBackground: Color
    )

    /** Heading scale, relative to body size so it tracks the user's font scale. */
    private val HEADING_SCALE = listOf(1.5f, 1.34f, 1.2f, 1.1f, 1.04f, 1f)
    private val HEADING_WEIGHT = listOf(
        FontWeight.Bold, FontWeight.Bold, FontWeight.SemiBold,
        FontWeight.SemiBold, FontWeight.Medium, FontWeight.Medium
    )

    private val TASK_ITEM = Regex("^-\\s*\\[[ xX]\\]")
    private val ORDERED_ITEM = Regex("^\\d{1,3}\\.\\s+")
    private val BULLET_ITEM = Regex("^[-*+]\\s+")
    private val FENCE = Regex("^(```|~~~)")

    /**
     * Pure markdown-to-styled-text conversion.
     *
     * No Compose state is read here, so this is directly unit-testable; the
     * [markdown] composable is the thin wrapper that supplies theme colours.
     */
    fun parse(markdown: String, baseSize: TextUnit, palette: Palette): AnnotatedString {
        val mono = FontFamily.Monospace
        val normalised = markdown.replace("\r\n", "\n").trim()

        return buildAnnotatedString {
            if (normalised.isEmpty()) return@buildAnnotatedString

            val blocks = splitBlocks(normalised)
            blocks.forEachIndexed { blockIndex, block ->
                if (blockIndex > 0) append("\n\n")

                if (block.isFencedCode) {
                    pushStyle(SpanStyle(fontFamily = mono, fontSize = baseSize * 0.88f))
                    append(block.lines.joinToString("\n").trimEnd())
                    pop()
                    return@forEachIndexed
                }

                block.lines.forEachIndexed { lineIndex, raw ->
                    if (lineIndex > 0) append("\n")
                    renderLine(raw, baseSize, palette, mono)
                }
            }
        }
    }

    /** Theme-aware entry point for the UI layer. */
    @Composable
    fun markdown(markdown: String, baseSize: TextUnit): AnnotatedString {
        val palette = Palette(
            body = MaterialTheme.colorScheme.onSurface,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
            accent = MaterialTheme.colorScheme.primary,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant
        )
        return remember(markdown, baseSize, palette) {
            parse(markdown, baseSize, palette)
        }
    }

    private class Block(val lines: List<String>, val isFencedCode: Boolean)

    /** Groups lines into blocks, treating a fenced code region as one block. */
    private fun splitBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var current = mutableListOf<String>()
        var inFence = false

        fun flush() {
            if (current.isNotEmpty()) blocks += Block(current.toList(), isFencedCode = false)
            current = mutableListOf()
        }

        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (FENCE.containsMatchIn(trimmed)) {
                if (inFence) {
                    flush()                       // fence body is its own block
                    inFence = false
                } else {
                    flush()                       // anything before the fence stands alone
                    inFence = true
                }
                return@forEach
            }
            if (inFence) {
                current.add(line)
            } else if (trimmed.isEmpty()) {
                flush()
            } else {
                current.add(line)
            }
        }
        flush()
        return blocks
    }

    private fun AnnotatedString.Builder.renderLine(
        raw: String,
        baseSize: TextUnit,
        palette: Palette,
        mono: FontFamily
    ) {
        val line = raw.trim()
        when {
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                val text = line.drop(level).trim().trimEnd('#').trim()
                if (text.isEmpty()) return
                pushStyle(
                    SpanStyle(
                        fontSize = baseSize * HEADING_SCALE[level - 1],
                        fontWeight = HEADING_WEIGHT[level - 1],
                        color = palette.body
                    )
                )
                appendInline(text, palette, mono, baseSize)
                pop()
            }

            line.startsWith(">") -> {
                pushStyle(SpanStyle(color = palette.muted))
                append("▏ ")
                appendInline(line.trimStart('>', ' '), palette, mono, baseSize)
                pop()
            }

            TASK_ITEM.containsMatchIn(line) -> {
                val done = line.substring(2, 5).equals("x", ignoreCase = true)
                pushStyle(SpanStyle(color = if (done) palette.accent else palette.muted))
                append(if (done) "✓  " else "○  ")
                pop()
                appendInline(line.drop(5).trim(), palette, mono, baseSize)
            }

            ORDERED_ITEM.containsMatchIn(line) -> {
                val label = line.substringBefore('.').trim()
                pushStyle(SpanStyle(color = palette.muted, fontWeight = FontWeight.Medium))
                append("$label.  ")
                pop()
                appendInline(line.substringAfter('.').trim(), palette, mono, baseSize)
            }

            BULLET_ITEM.containsMatchIn(line) -> {
                pushStyle(SpanStyle(color = palette.muted))
                append("•  ")
                pop()
                appendInline(line.drop(1).trim(), palette, mono, baseSize)
            }

            else -> appendInline(line, palette, mono, baseSize)
        }
    }

    /**
     * Applies inline spans with `pushStyle`/`pop` rather than a replace loop, so
     * nesting like `**bold with `code` inside**` degrades to the inner style
     * instead of dropping the outer one.
     */
    private fun AnnotatedString.Builder.appendInline(
        text: String,
        palette: Palette,
        mono: FontFamily,
        baseSize: TextUnit
    ) {
        var i = 0
        while (i < text.length) {
            val bold = text.startsWith("**", i)
            val strike = text.startsWith("~~", i)
            val code = text[i] == '`'
            val italic = (text[i] == '*' || text[i] == '_') && !bold

            if (bold || strike || code || italic) {
                val closer = when {
                    bold -> "**"
                    strike -> "~~"
                    code -> "`"
                    else -> text[i].toString()
                }
                val end = text.indexOf(closer, i + closer.length)
                val inner = if (end > 0) text.substring(i + closer.length, end) else ""

                // An unterminated or empty span is literal text, and an italic marker
                // only styles when the run has no spaces - so `snake_case_names`
                // survives intact instead of turning half the word italic.
                val styles = when {
                    end <= 0 || inner.isEmpty() -> null
                    italic && inner.contains(' ') -> null
                    else -> when {
                        bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
                        strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        code -> SpanStyle(
                            fontFamily = mono,
                            fontSize = baseSize * 0.9f,
                            background = palette.codeBackground
                        )
                        else -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                }

                if (styles != null) {
                    pushStyle(styles)
                    appendInline(inner, palette, mono, baseSize)
                    pop()
                    i = end + closer.length
                } else {
                    append(text[i]); i++
                }
            } else {
                append(text[i]); i++
            }
        }
    }
}
