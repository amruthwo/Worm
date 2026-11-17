package com.worm.app.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.worm.app.ui.components.findActivity

/** Fingerprint / face unlock for hidden folders, with "Use PIN" as the fallback. */
object Biometrics {
    private const val AUTH = BIOMETRIC_STRONG or BIOMETRIC_WEAK

    fun available(context: Context) =
        BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    /** [onFallback] runs when the user taps "Use PIN" or biometrics fail; a plain cancel does nothing. */
    fun prompt(context: Context, title: String, onSuccess: () -> Unit, onFallback: () -> Unit) {
        val activity = context.findActivity() as? FragmentActivity ?: return onFallback()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) onFallback()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Or use your Worm PIN")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(AUTH)
                .build(),
        )
    }
}
