package com.edgehybrid.agent.ui.chat

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextExtractorTest {
    @Test
    fun `extracts bounded UTF-8 text files`() {
        val content = "Meeting notes\nDecisions: ship the release."
        assertEquals(content, DocumentTextExtractor.extract("notes.md", "text/markdown", content.toByteArray()))
    }

    @Test
    fun `extracts paragraphs from docx document xml`() {
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("""<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>First</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
        assertEquals("First\nSecond", DocumentTextExtractor.extract("brief.docx", null, bytes))
    }

    @Test
    fun `extracts ordinary literal text from a PDF page stream`() {
        val pdf = "%PDF-1.4\n1 0 obj << /Length 32 >>\nstream\nBT (Hello from PDF) Tj ET\nendstream\nendobj".toByteArray(Charsets.ISO_8859_1)
        val result = DocumentTextExtractor.extract("sample.pdf", "application/pdf", pdf)
        assertTrue(result.contains("Hello from PDF"))
    }

    @Test
    fun `rejects unsupported and image-only documents with explanatory errors`() {
        val unsupported = runCatching {
            DocumentTextExtractor.extract("archive.zip", "application/zip", byteArrayOf(1))
        }.exceptionOrNull()
        assertTrue(unsupported?.message?.contains("Unsupported document type") == true)

        val scannedPdf = "%PDF-1.4 no text streams".toByteArray(Charsets.ISO_8859_1)
        val noText = runCatching {
            DocumentTextExtractor.extract("scan.pdf", "application/pdf", scannedPdf)
        }.exceptionOrNull()
        assertTrue(noText?.message?.contains("OCR") == true)
    }
}
