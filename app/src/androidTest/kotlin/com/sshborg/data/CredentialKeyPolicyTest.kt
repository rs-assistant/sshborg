package com.sshborg.data

import android.app.KeyguardManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Own disposable aliases only. Configure a secure lock on the test device before running. */
@RunWith(AndroidJUnit4::class)
class CredentialKeyPolicyTest {
    private val alias = "sshborg-test-credential-${UUID.randomUUID()}"

    private fun generate(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()

    @After fun removeOwnTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let {
            if (it.containsAlias(alias)) it.deleteEntry(alias)
        }
    }

    private fun protectedKey(): SecretKey {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Configure a secure device lock before this acceptance test",
            context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        return generate(KeystoreV2.keySpecFor(alias))
    }

    @Test fun productionPolicyRequiresPerOperationAuthentication() {
        val key = protectedKey()
        assertSame(key, KeystoreV2.requireProtectedKey(key))
    }

    @Test fun aFreshProtectedCipherCannotEncryptWithoutCryptoObjectAuthorization() {
        val key = protectedKey()
        KeystoreV2.requireProtectedKey(key)
        assertThrows(GeneralSecurityException::class.java) {
            Cipher.getInstance(CredentialCipherV2.TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal("disposable test credential".toByteArray())
            }
        }
    }

    @Test fun anUnauthenticatedKeyIsRefusedWithoutReplacingIt() {
        val key = generate(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        assertThrows(IllegalStateException::class.java) { KeystoreV2.requireProtectedKey(key) }
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }
}
