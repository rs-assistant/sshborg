package com.sshborg.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialBlobV2Test {
    private val iv = ByteArray(12) { it.toByte() }
    private val tag = ByteArray(16) { (it + 20).toByte() }

    @Test fun roundTrip() {
        val encrypted = byteArrayOf(1, 2, 3) + tag
        val (decodedIv, decodedData) = CredentialBlobV2.decode(CredentialBlobV2.encode(iv, encrypted))
        assertArrayEquals(iv, decodedIv)
        assertArrayEquals(encrypted, decodedData)
    }

    @Test fun rejectsUnsupportedVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBlobV2.decode("v1:AAAA")
        }
    }

    @Test fun rejectsMalformedBase64() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBlobV2.decode("v2:not-valid!")
        }
    }

    @Test fun rejectsTruncatedTag() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBlobV2.decode("v2:AA==")
        }
    }

    @Test fun rejectsInvalidIvAndTag() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBlobV2.encode(ByteArray(11), tag)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBlobV2.encode(iv, ByteArray(15))
        }
    }
}
