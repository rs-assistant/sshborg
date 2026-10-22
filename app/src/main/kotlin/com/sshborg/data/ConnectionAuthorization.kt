package com.sshborg.data

import com.sshborg.R
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
import javax.crypto.Cipher
import java.util.concurrent.atomic.AtomicLong

/**
 * A fresh system authentication is required for every new SSH transport connection.
 * The connection grant is single-use and expires on stop or Activity replacement. Saved
 * credentials additionally require their own per-operation CryptoObject authorization.
 * Missing foreground Activity or unavailable authenticators fails closed. No grant is cached.
 */
object ConnectionAuthorization {
    private val mutex = Mutex()
    @Volatile private var activityRef: WeakReference<FragmentActivity>? = null
    private var activityObserver: LifecycleEventObserver? = null
    private val foregroundGeneration = AtomicLong()

    fun register(activity: FragmentActivity) {
        activityObserver?.let { observer -> activityRef?.get()?.lifecycle?.removeObserver(observer) }
        foregroundGeneration.incrementAndGet()
        activityRef = WeakReference(activity)
        activityObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                foregroundGeneration.incrementAndGet()
            }
        }.also { activity.lifecycle.addObserver(it) }
    }

    fun unregister(activity: FragmentActivity) {
        if (activityRef?.get() === activity) {
            activityObserver?.let { activity.lifecycle.removeObserver(it) }
            activityObserver = null
            activityRef = null
            foregroundGeneration.incrementAndGet()
        }
    }

    internal fun isCurrentActivity(activity: FragmentActivity): Boolean = activityRef?.get() === activity

    internal suspend fun requireFreshAttempt(): ConnectionAttemptGrant? {
        val generation = foregroundGeneration.get()
        if (!requireFreshAuthentication()) return null
        return withContext(Dispatchers.Main.immediate) {
            val activity = activityRef?.get() ?: return@withContext null
            if (foregroundGeneration.get() != generation || !isForeground(activity)) return@withContext null
            ConnectionAttemptGrant {
                foregroundGeneration.get() == generation && isForeground(activity)
            }
        }
    }

    internal suspend fun consumeAttempt(grant: ConnectionAttemptGrant): Boolean =
        withContext(Dispatchers.Main.immediate) { grant.consume() }

    private fun isForeground(activity: FragmentActivity): Boolean =
        isCurrentActivity(activity) && !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    /** Shares the prompt mutex with the connection gate: two CryptoObjects never compete. */
    internal suspend fun authorizeCipher(cipher: Cipher): Cipher? = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val activity = activityRef?.get() ?: return@withContext null
            AuthenticatedCipherPrompt.authorize(activity, cipher)
        }
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
                var prompt: BiometricPrompt? = null
                lateinit var lifecycleObserver: LifecycleEventObserver
                val request = AuthenticationPromptRequest<Unit>(
                    executeOnMain = { action -> activity.runOnUiThread { action() } },
                    canAccept = {
                        !activity.isFinishing && !activity.isDestroyed &&
                            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            activityRef?.get() === activity
                    },
                    cleanup = { cancelPrompt ->
                        activity.lifecycle.removeObserver(lifecycleObserver)
                        if (cancelPrompt) prompt?.cancelAuthentication()
                    },
                    deliver = { value ->
                        if (continuation.isActive) continuation.resume(value != null)
                    },
                )
                lifecycleObserver = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                        request.reject()
                    }
                }
                continuation.invokeOnCancellation { request.cancel() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    activity.lifecycle.addObserver(lifecycleObserver)
                    val createdPrompt = BiometricPrompt(
                        activity,
                        androidx.core.content.ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                request.succeed(Unit)
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
                            .setTitle(activity.getString(R.string.connection_authorization_title))
                            .setSubtitle(activity.getString(R.string.connection_authorization_subtitle))
                            .setAllowedAuthenticators(authenticators)
                            .build()
                    )
                } catch (_: RuntimeException) {
                    request.reject()
                }
            }
        }
    }
}
