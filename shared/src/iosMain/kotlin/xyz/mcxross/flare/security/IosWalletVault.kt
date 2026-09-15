@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.mcxross.flare.security

import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

class IosWalletVault : WalletVault {
  private var authorizedContext: LAContext? = null

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
    authorize(prompt, force = prompt.requireFreshAuthorization)
    delete(slot)
    memScoped {
      val serviceRef = SERVICE.toCFString() ?: throw WalletVaultException.Unavailable("Keychain string encoding failed")
      val accountRef = slot.storageKey.toCFString() ?: throw WalletVaultException.Unavailable("Keychain string encoding failed")
      val dataRef = secret.toCFData() ?: throw WalletVaultException.Unavailable("Keychain data encoding failed")
      val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        ?: throw WalletVaultException.Unavailable("Keychain dictionary creation failed")
      try {
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, serviceRef)
        CFDictionaryAddValue(dict, kSecAttrAccount, accountRef)
        CFDictionaryAddValue(dict, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        CFDictionaryAddValue(dict, kSecValueData, dataRef)
        val status = SecItemAdd(dict, null)
        if (status != errSecSuccess) {
          throw WalletVaultException.Unavailable("Keychain write failed ($status)")
        }
      } finally {
        CFRelease(dict)
        CFRelease(dataRef)
        CFRelease(serviceRef)
        CFRelease(accountRef)
      }
    }
  }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray {
    authorize(prompt, force = prompt.requireFreshAuthorization)
    return memScoped {
      val serviceRef = SERVICE.toCFString() ?: throw WalletVaultException.Corrupted()
      val accountRef = slot.storageKey.toCFString() ?: throw WalletVaultException.Corrupted()
      val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
        ?: throw WalletVaultException.Unavailable("Keychain dictionary creation failed")
      try {
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, serviceRef)
        CFDictionaryAddValue(dict, kSecAttrAccount, accountRef)
        CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        when (val status = SecItemCopyMatching(dict, result.ptr)) {
          errSecSuccess -> {
            val cfData = result.value ?: throw WalletVaultException.Corrupted()
            try {
              (cfData as CFDataRef).toByteArray()
            } finally {
              CFRelease(cfData)
            }
          }
          errSecUserCanceled -> throw WalletVaultException.Cancelled()
          errSecItemNotFound -> throw WalletVaultException.Corrupted()
          errSecInteractionNotAllowed ->
            throw WalletVaultException.Unavailable("Keychain authorization is unavailable")
          else -> throw WalletVaultException.Unavailable("Keychain read failed ($status)")
        }
      } finally {
        CFRelease(dict)
        CFRelease(serviceRef)
        CFRelease(accountRef)
      }
    }
  }

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) {
    authorize(prompt, force = prompt.requireFreshAuthorization)
    delete(slot)
  }

  override fun lock() {
    authorizedContext?.invalidate()
    authorizedContext = null
  }

  private suspend fun authorize(prompt: VaultPrompt, force: Boolean): LAContext {
    if (!force) {
      authorizedContext?.let {
        return it
      }
    }
    if (force) lock()
    return authorizeWithDevice(prompt).also { context -> authorizedContext = context }
  }

  private suspend fun authorizeWithDevice(prompt: VaultPrompt): LAContext =
    suspendCancellableCoroutine { continuation ->
      val context = LAContext().apply { localizedCancelTitle = prompt.cancelLabel }
      continuation.invokeOnCancellation { context.invalidate() }
      val canEvaluate = memScoped {
        val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
        context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)
      }
      if (!canEvaluate) {
        if (continuation.isActive)
          continuation.resumeWith(
            Result.failure(WalletVaultException.Unavailable("A device passcode must be configured"))
          )
        return@suspendCancellableCoroutine
      }
      context.evaluatePolicy(
        LAPolicyDeviceOwnerAuthentication,
        "${prompt.title}\n${prompt.subtitle}",
      ) { success, _ ->
        if (!continuation.isActive) return@evaluatePolicy
        if (success) continuation.resume(context)
        else continuation.resumeWith(Result.failure(WalletVaultException.Cancelled()))
      }
    }

  private fun delete(slot: WalletSecretSlot) {
    memScoped {
      val serviceRef = SERVICE.toCFString() ?: return@memScoped
      val accountRef = slot.storageKey.toCFString() ?: return@memScoped
      val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null) ?: return@memScoped
      try {
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, serviceRef)
        CFDictionaryAddValue(dict, kSecAttrAccount, accountRef)
        SecItemDelete(dict)
      } finally {
        CFRelease(dict)
        CFRelease(serviceRef)
        CFRelease(accountRef)
      }
    }
  }

  private companion object {
    const val SERVICE = "xyz.mcxross.flare.wallet"
  }
}

private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
  CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), size.toLong())
}

private fun CFDataRef.toByteArray(): ByteArray {
  val length = CFDataGetLength(this).toInt()
  if (length == 0) return ByteArray(0)
  val result = ByteArray(length)
  result.usePinned { pinned ->
    val src = CFDataGetBytePtr(this)
    platform.posix.memcpy(pinned.addressOf(0), src, length.convert())
  }
  return result
}

private fun String.toCFString(): CFStringRef? =
  CFStringCreateWithCString(kCFAllocatorDefault, this, kCFStringEncodingUTF8)
