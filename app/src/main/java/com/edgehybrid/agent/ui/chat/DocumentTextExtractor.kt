package com.edgehybrid.agent.ui.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/** Extracts bounded text from common local document formats without uploading the file. */
internal object DocumentTextExtractor {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_EXTRACTED_CHARS = 40_000
    private const val MAX_PDF_STREAM_BYTES = 8 * 1024 * 1024

    fun extract(displayName: String, mimeType: String?, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "The selected file is empty." }
        require(bytes.size <= MAX_FILE_BYTES) { "Files must be 8 MB or smaller." }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val text = when {
            ext == "docx" || mimeType == DOCX_MIME -> extractDocx(bytes)
            ext == "pdf" || mimeType == PDF_MIME -> extractPdf(bytes)
            ext in TEXT_EXTENSIONS || mimeType?.startsWith("text/") == true || mimeType == "application/json" ->
                decodeText(bytes)
            else -> throw IllegalArgumentException(
                "Unsupported document type. Attach PDF, DOCX, or a text-based file (TXT, Markdown, CSV, JSON, and source files)."
            )
        }.trim()
        require(text.isNotBlank()) {
            if (ext == "pdf" || mimeType == PDF_MIME) {
                "No selectable text was found in this PDF. Scanned/image-only PDFs need OCR and aren't supported yet."
            } else "No readable text was found in the selected file."
        }
        return text.take(MAX_EXTRACTED_CHARS)
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, StandardCharsets.ISO_8859_1) }
            .replace("\u0000", "")
    }

    private fun extractDocx(bytes: ByteArray): String {
        val documentXml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var result: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_FILE_BYTES) {
                            throw IllegalArgumentException("DOCX document content is too large.")
                        }
                        output.write(buffer, 0, count)
                    }
                    result = output.toByteArray()
                    break
                }
            }
            result ?: throw IllegalArgumentException("This DOCX file has no readable document body.")
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(documentXml))
        val text = StringBuilder()
        fun visit(node: Node) {
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == "p") {
                if (text.isNotEmpty()) text.append('\n')
            }
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == "t") {
                text.append(node.textContent)
            } else {
                val children = node.childNodes
                for (i in 0 until children.length) visit(children.item(i))
            }
        }
        visit(document.documentElement)
        return text.toString()
    }

    /**
     * Best-effort extraction from ordinary, unencrypted PDFs with selectable text.
     * It reads literal text operators from uncompressed or Flate-compressed page streams;
     * scanned PDFs and font-specific glyph maps require OCR/ToUnicode support and are
     * rejected with a clear message rather than uploaded or silently misrepresented.
     */
    private fun extractPdf(bytes: ByteArray): String {
        val raw = String(bytes, StandardCharsets.ISO_8859_1)
        val streamStart = Regex("(?s)<<(.+?)>>\\s*stream\\r?\\n")
        val chunks = mutableListOf<String>()
        streamStart.findAll(raw).forEach { match ->
            val start = match.range.last + 1
            val end = raw.indexOf("endstream", start)
            if (end <= start) return@forEach
            val streamBytes = raw.substring(start, end).trimEnd('\r', '\n').toByteArray(StandardCharsets.ISO_8859_1)
            val decoded = if (match.groupValues[1].contains("FlateDecode")) {
                runCatching {
                    InflaterInputStream(ByteArrayInputStream(streamBytes)).use { readBounded(it, MAX_PDF_STREAM_BYTES) }
                }.getOrNull() ?: return@forEach
            } else streamBytes
            val content = String(decoded, StandardCharsets.ISO_8859_1)
            val extracted = extractPdfStrings(content).takeIf(String::isNotBlank) ?: return@forEach
            val remaining = MAX_EXTRACTED_CHARS - chunks.sumOf(String::length)
            if (remaining > 0) chunks.add(extracted.take(remaining))
        }
        return chunks.joinToString("\n").take(MAX_EXTRACTED_CHARS)
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "The PDF contains an unusually large compressed stream." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun extractPdfStrings(content: String): String {
        val result = StringBuilder()
        val literal = Regex("\\(((?:\\\\.|[^\\\\)])*)\\)\\s*Tj")
        literal.findAll(content).forEach { match ->
            appendPdfString(result, unescapePdfLiteral(match.groupValues[1]))
            result.append(' ')
        }
        val arrays = Regex("\\[(.*?)]\\s*TJ", RegexOption.DOT_MATCHES_ALL)
        arrays.findAll(content).forEach { array ->
            val pieces = Regex("\\(((?:\\\\.|[^\\\\)])*)\\)").findAll(array.groupValues[1])
            pieces.forEach { piece ->
                appendPdfString(result, unescapePdfLiteral(piece.groupValues[1]))
                result.append(' ')
            }
        }
        return result.toString()
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun unescapePdfLiteral(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\' || index >= value.length) {
                out.append(char)
                continue
            }
            when (val escaped = value[index++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                '\\', '(', ')' -> out.append(escaped)
                '\r' -> if (index < value.length && value[index] == '\n') index++
                '\n' -> Unit
                in '0'..'7' -> {
                    var octal = escaped.digitToInt()
                    repeat(2) {
                        if (index < value.length && value[index] in '0'..'7') {
                            octal = octal * 8 + value[index++].digitToInt()
                        }
                    }
                    out.append(octal.toChar())
                }
                else -> out.append(escaped)
            }
        }
        return out.toString()
    }

    private fun appendPdfString(target: StringBuilder, value: String) {
        // PDF literal strings are commonly WinAnsi/ASCII. Keep readable printable text
        // and whitespace; unknown glyph encodings are intentionally not guessed.
        val normalized = if (value.startsWith("\u00FE\u00FF")) {
            val bytes = value.drop(2).map { it.code.toByte() }.toByteArray()
            String(bytes, Charset.forName("UTF-16BE"))
        } else value
        normalized.forEach { char ->
            if (char == '\n' || char == '\r' || char == '\t' || char.code >= 0x20) target.append(char)
        }
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log",
        "yaml", "yml", "toml", "ini", "kt", "java", "py", "js", "ts", "css", "sql",
        "sh", "gradle", "properties", "c", "h", "cpp", "go", "rs", "swift"
    )

    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val PDF_MIME = "application/pdf"
}
