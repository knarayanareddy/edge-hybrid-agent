package com.edgehybrid.agent.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JevRiskClassifierTest {

    private lateinit var classifier: JevRiskClassifier

    @Before
    fun setUp() {
        classifier = JevRiskClassifier()
    }

    @Test
    fun testLowRiskLocalActionsScoreBelowForty() {
        val eval = classifier.classifyAction("toggle_flashlight")
        assertTrue(eval.riskScore < 40)
        assertEquals("ALLOW", eval.verdict)
    }

    @Test
    fun testMediumRiskNetworkActionsScoreBetweenFortyAndSixtyNine() {
        val eval = classifier.classifyAction("skill:web_extract.js", "{\"url\":\"https://example.com\"}")
        assertTrue(eval.riskScore in 40..69)
        assertEquals("ALLOW_MONITORED", eval.verdict)
    }

    @Test
    fun testHighRiskActionRequiresConfirmation() {
        val eval = classifier.classifyAction("send_sms", "{\"phone\":\"+1234567890\",\"message\":\"Hi\"}")
        assertTrue(eval.riskScore >= 70)
        assertEquals("CONFIRMATION_REQUIRED", eval.verdict)
    }

    @Test
    fun testCriticalActionIsHardRejected() {
        val eval = classifier.classifyAction("bulk_delete", "{\"path\":\"/system/data\"}")
        assertTrue(eval.riskScore >= 90)
        assertEquals("REJECT", eval.verdict)
    }
}
