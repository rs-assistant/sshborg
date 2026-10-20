package com.sshborg.data

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Experimental CryptoObject bridge. No production credential call sites yet.
 * API 29 deliberately fails closed until a separate device-credential fallback
 * has been designed and tested. A prompt without a CryptoObject does NOT unlock
 * a per-use authenticated Keystore Cipher.
 */
internal object AuthenticatedCipherPrompt {
    suspend fun authorize(activity: FragmentActivity, cipher: Cipher): Cipher? =
        withContext(Dispatchers.Main.immediate) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
            if (activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext null
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
                BiometricManager.BIOMETRIC_SUCCESS) return@withContext null

            suspendCancellableCoroutine { continuation ->
                val prompt = BiometricPrompt(
                    activity,
                    androidx.core.content.ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val verified = result.cryptoObject?.cipher
                            val allowed = !activity.isFinishing && !activity.isDestroyed &&
                                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            if (continuation.isActive) continuation.resume(if (allowed && verified === cipher) verified else null)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
                continuation.invokeOnCancellation { prompt.cancelAuthentication() }
                try {
                    prompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Authorize protected SSH credential")
                            .setAllowedAuthenticators(authenticators)
                            .build(),
                        BiometricPrompt.CryptoObject(cipher)
                    )
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
}
