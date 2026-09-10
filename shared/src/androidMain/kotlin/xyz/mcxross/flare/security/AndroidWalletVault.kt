package xyz.mcxross.flare.security

import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidWalletVault(private val activity: FragmentActivity) : WalletVault {
  @Volatile private var authorizedUntilElapsedMs: Long = 0L
  private val preferences =
    activity.applicationContext.getSharedPreferences(
      PREFERENCES_NAME,
      android.content.Context.MODE_PRIVATE,
    )

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
    try {
      val key = getOrCreateKey()
      authorize(prompt, force = prompt.requireFreshAuthorization)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val ciphertext = cipher.doFinal(secret)
      val committed =
        preferences
          .edit()
          .putString(ivKey(slot), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
          .putString(dataKey(slot), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
          .commit()
      ciphertext.fill(0)
      check(committed) { "Unable to persist encrypted wallet material" }
    } catch (error: UserNotAuthenticatedException) {
      throw WalletVaultException.Cancelled()
    } catch (error: KeyPermanentlyInvalidatedException) {
      throw WalletVaultException.Corrupted(error)
    }
  }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray {
    val iv =
      preferences.getString(ivKey(slot), null)?.let(::decode)
        ?: throw WalletVaultException.Corrupted()
    val ciphertext =
      preferences.getString(dataKey(slot), null)?.let(::decode)
        ?: throw WalletVaultException.Corrupted()
    return try {
      val key = getOrCreateKey()
      authorize(prompt, force = prompt.requireFreshAuthorization)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
      cipher.doFinal(ciphertext)
    } catch (error: UserNotAuthenticatedException) {
      throw WalletVaultException.Cancelled()
    } catch (error: KeyPermanentlyInvalidatedException) {
      throw WalletVaultException.Corrupted(error)
    } catch (error: AEADBadTagException) {
      throw WalletVaultException.Corrupted(error)
    } finally {
      iv.fill(0)
      ciphertext.fill(0)
    }
  }

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) {
    authorize(prompt, force = prompt.requireFreshAuthorization)
    val committed = preferences.edit().remove(ivKey(slot)).remove(dataKey(slot)).commit()
    check(committed) { "Unable to remove encrypted wallet material" }
  }

  override fun lock() {
    authorizedUntilElapsedMs = 0L
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let {
      return it
    }

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    val builder =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(true)
        .setInvalidatedByBiometricEnrollment(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.setUserAuthenticationParameters(
        AUTHORIZATION_SECONDS,
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
      )
    } else {
      @Suppress("DEPRECATION")
      builder.setUserAuthenticationValidityDurationSeconds(AUTHORIZATION_SECONDS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUnlockedDeviceRequired(true)
    generator.init(builder.build())
    return generator.generateKey()
  }

  private suspend fun authorize(prompt: VaultPrompt, force: Boolean) {
    if (!force && SystemClock.elapsedRealtime() < authorizedUntilElapsedMs) return
    val authenticators =
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (
      BiometricManager.from(activity).canAuthenticate(authenticators) !=
        BiometricManager.BIOMETRIC_SUCCESS
    ) {
      throw WalletVaultException.Unavailable(
        "A secure screen lock or strong biometric must be configured before storing a wallet"
      )
    }

    suspendCancellableCoroutine { continuation ->
      val callback =
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            authorizedUntilElapsedMs =
              SystemClock.elapsedRealtime() + AUTHORIZATION_SECONDS * 1_000L
            if (continuation.isActive) continuation.resume(Unit)
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (continuation.isActive) {
              continuation.resumeWith(
                Result.failure(
                  if (
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                      errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                      errorCode == BiometricPrompt.ERROR_CANCELED
                  ) {
                    WalletVaultException.Cancelled()
                  } else {
                    WalletVaultException.Unavailable(errString.toString())
                  }
                )
              )
            }
          }
        }
      val biometricPrompt =
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
      continuation.invokeOnCancellation { biometricPrompt.cancelAuthentication() }
      val info =
        BiometricPrompt.PromptInfo.Builder()
          .setTitle(prompt.title)
          .setSubtitle(prompt.subtitle)
          .setAllowedAuthenticators(authenticators)
          .build()
      biometricPrompt.authenticate(info)
    }
  }

  private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

  private fun ivKey(slot: WalletSecretSlot) = "${slot.storageKey}_iv"

  private fun dataKey(slot: WalletSecretSlot) = "${slot.storageKey}_ciphertext"

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "flare_wallet_wrapping_key_v1"
    const val PREFERENCES_NAME = "flare_wallet_vault"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_LENGTH_BITS = 128
    const val AUTHORIZATION_SECONDS = 5 * 60
  }
}
