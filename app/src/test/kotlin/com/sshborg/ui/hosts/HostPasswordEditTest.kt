package com.sshborg.ui.hosts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPasswordEditTest {
    private fun saved(
        edit: HostPasswordEdit,
        replacement: String = "",
        removeSaved: Boolean = false,
        useKey: Boolean = false,
        encrypt: (String) -> String = { error("Unchanged or removed passwords must not require encryption") },
    ) = runBlocking { edit.encryptedForSave(replacement, removeSaved, useKey) { encrypt(it) } }

    @Test fun `editing metadata preserves even unreadable ciphertext without opening it`() {
        val edit = HostPasswordEdit("ciphertext whose keystore key is missing")
        assertTrue(edit.hasSavedPassword)
        assertFalse(edit.needsReentry)
        assertEquals("ciphertext whose keystore key is missing", saved(edit))
    }

    @Test fun `an invalid empty encrypted blob is preserved rather than silently erased`() {
        assertEquals("", saved(HostPasswordEdit("")))
    }

    @Test fun `a new host without a password stays without a saved credential`() {
        val edit = HostPasswordEdit()
        assertFalse(edit.hasSavedPassword)
        assertFalse(edit.needsReentry)
        assertNull(saved(edit))
    }

    @Test fun `explicit replacement encrypts the exact password including whitespace`() {
        val encrypted = saved(HostPasswordEdit("old ciphertext"), replacement = "  new test password  ") {
            assertEquals("  new test password  ", it)
            "new ciphertext"
        }
        assertEquals("new ciphertext", encrypted)
    }

    @Test fun `blanking a replacement restores preservation of the original credential`() {
        val edit = HostPasswordEdit("original ciphertext")
        assertEquals("replacement ciphertext", saved(edit, replacement = "new password") { "replacement ciphertext" })
        assertEquals("original ciphertext", saved(edit, replacement = ""))
    }

    @Test fun `legacy password requires explicit replacement or removal before saving`() {
        val edit = HostPasswordEdit(hasLegacyPassword = true)
        assertTrue(edit.hasSavedPassword)
        assertTrue(edit.needsReentry)
        assertThrows(IllegalStateException::class.java) { saved(edit) }
        assertEquals("encrypted replacement", saved(edit, replacement = "test password") { "encrypted replacement" })
        assertNull(saved(edit, removeSaved = true))
    }

    @Test fun `plaintext residue never changes preservation of an encrypted credential`() {
        val edit = HostPasswordEdit("encrypted password", hasLegacyPassword = true)
        assertFalse(edit.needsReentry)
        assertEquals("encrypted password", saved(edit))
    }

    @Test fun `explicit removal does not require access to the keystore`() {
        assertNull(saved(HostPasswordEdit("unreadable ciphertext"), removeSaved = true))
    }

    @Test fun `undoing removal retains the original ciphertext`() {
        val edit = HostPasswordEdit("original ciphertext")
        assertNull(saved(edit, removeSaved = true))
        assertEquals("original ciphertext", saved(edit, removeSaved = false))
    }

    @Test fun `switching to key auth removes the saved password without decrypting it`() {
        assertNull(saved(HostPasswordEdit("old ciphertext"), useKey = true))
        assertNull(saved(HostPasswordEdit(hasLegacyPassword = true), useKey = true))
    }

    @Test fun `encryption failure leaves the original available for retry`() {
        val edit = HostPasswordEdit("original ciphertext")
        val failure = IllegalStateException("test keystore unavailable")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            saved(edit, replacement = "new password") { throw failure }
        })
        assertEquals("original ciphertext", saved(edit))
        assertEquals("new ciphertext", saved(edit, replacement = "new password") { "new ciphertext" })
    }

    @Test fun `an empty encryption result cannot be persisted as a replacement`() {
        val edit = HostPasswordEdit("original ciphertext")
        for (invalid in listOf("", " ")) {
            assertThrows(IllegalStateException::class.java) {
                saved(edit, replacement = "new password") { invalid }
            }
        }
        assertEquals("original ciphertext", saved(edit))
    }

    @Test fun `encryption cancellation is propagated without discarding the original`() {
        val edit = HostPasswordEdit("original ciphertext")
        val cancellation = CancellationException("test cancelled encryption")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            saved(edit, replacement = "new password") { throw cancellation }
        })
        assertEquals("original ciphertext", saved(edit))
    }
}
