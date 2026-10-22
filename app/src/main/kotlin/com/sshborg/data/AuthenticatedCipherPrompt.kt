package com.sshborg.data

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume

/**
 * Android 11+ CryptoObject bridge. A non-crypto prompt cannot authorize the Cipher.
 * Call through ConnectionAuthorization so all connection/credential prompts are serialized.
 */
internal object AuthenticatedCipherPrompt {
    suspend fun authorize(activity: FragmentActivity, cipher: Cipher): Cipher? =
        withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext null
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
                BiometricManager.BIOMETRIC_SUCCESS) return@withContext null

            suspendCancellableCoroutine { continuation ->
                var prompt: BiometricPrompt? = null
                lateinit var observer: LifecycleEventObserver
                val request = AuthenticationPromptRequest<Cipher>(
                    executeOnMain = { action -> activity.runOnUiThread { action() } },
                    canAccept = { verified ->
                        verified === cipher && ConnectionAuthorization.isCurrentActivity(activity) &&
                            !activity.isFinishing && !activity.isDestroyed &&
                            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    },
                    cleanup = { cancelPrompt ->
                        activity.lifecycle.removeObserver(observer)
                        if (cancelPrompt) prompt?.cancelAuthentication()
                    },
                    deliver = { value ->
                        if (continuation.isActive) continuation.resume(value)
                    },
                )
                observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                        request.reject()
                    }
                }
                continuation.invokeOnCancellation { request.cancel() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    activity.lifecycle.addObserver(observer)
                    val createdPrompt = BiometricPrompt(
                        activity,
                        androidx.core.content.ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                val verified = result.cryptoObject?.cipher
                                if (verified == null) request.reject() else request.succeed(verified)
                            }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                request.reject()
                            }
                        }
                    )
                    prompt = createdPrompt
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    createdPrompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Authorize protected SSH credential")
                            .setAllowedAuthenticators(authenticators)
                            .build(),
                        BiometricPrompt.CryptoObject(cipher)
                    )
                } catch (_: RuntimeException) {
                    request.reject()
                }
            }
        }
}
