package com.sshborg.data

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * A fresh system authentication is required for every new SSH transport connection.
 * This is a connection gate, NOT cryptographic authorization of the AES Keystore key.
 * No cached grant is issued. Missing foreground Activity or unavailable authenticators
 * fails closed. Call before loading credentials, and again for each reconnect attempt.
 */
object ConnectionAuthorization {
    private val mutex = Mutex()
    @Volatile private var activityRef: WeakReference<FragmentActivity>? = null

    fun register(activity: FragmentActivity) {
        activityRef = WeakReference(activity)
    }

    fun unregister(activity: FragmentActivity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    suspend fun requireFreshAuthentication(): Boolean = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val activity = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed || !it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                ?: return@withContext false
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
                BiometricManager.BIOMETRIC_SUCCESS) return@withContext false

            suspendCancellableCoroutine { continuation ->
                val lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                activity.lifecycle.addObserver(lifecycleObserver)
                continuation.invokeOnCompletion { activity.lifecycle.removeObserver(lifecycleObserver) }
                val prompt = BiometricPrompt(
                    activity,
                    androidx.core.content.ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(
                                !activity.isFinishing && !activity.isDestroyed &&
                                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                    activityRef?.get() === activity
                            )
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                )
                continuation.invokeOnCompletion { prompt.cancelAuthentication() }
                try {
                    prompt.authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Authorize SSH / SFTP connection")
                        .setSubtitle("Verify your identity before connecting")
                        .setAllowedAuthenticators(authenticators)
                        .build()
                    )
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    }
}
