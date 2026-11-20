package com.orion.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsValidationTest {

    @Test
    fun buildFullSsid_combinesMandatoryPrefixTagAndName() {
        assertEquals(
            "DIRECT-DP-MyLaptop",
            WifiDirectCredentialValidator.buildFullSsid("DP", "MyLaptop")
        )
        assertEquals(
            "DIRECT-9Z",
            WifiDirectCredentialValidator.buildFullSsid("9Z", "")
        )
    }

    @Test
    fun validateAll_acceptsValidCustomTagNameAndPassphrase() {
        val result = WifiDirectCredentialValidator.validateAll(
            tag = "A7",
            name = "WorkBridge",
            passphrase = "StrongPassword123"
        )
        assertTrue(result.isValid)
        assertNull(result.tagError)
        assertNull(result.nameError)
        assertNull(result.passphraseError)
    }

    @Test
    fun validateAll_rejectsInvalidTagLengthOrSymbols() {
        val shortTag = WifiDirectCredentialValidator.validateAll("A", "Proxy", "12345678")
        assertFalse(shortTag.isValid)
        assertNotNull(shortTag.tagError)

        val symbolTag = WifiDirectCredentialValidator.validateAll("A-", "Proxy", "12345678")
        assertFalse(symbolTag.isValid)
        assertNotNull(symbolTag.tagError)
    }

    @Test
    fun validateAll_rejectsSsidExceeding32BytesAndShortPassphrase() {
        val longName = WifiDirectCredentialValidator.validateAll(
            tag = "DP",
            name = "ThisNetworkNameIsWayTooLongForWifiDirect",
            passphrase = "short"
        )
        assertFalse(longName.isValid)
        assertNotNull(longName.nameError)
        assertNotNull(longName.passphraseError)
    }

    @Test
    fun generatePassphrase_producesValidWpa2Length() {
        val generated = WifiDirectCredentialValidator.generatePassphrase(12)
        assertEquals(12, generated.length)
        assertTrue(WifiDirectCredentialValidator.validateAll("DP", "Test", generated).isValid)
    }
}
