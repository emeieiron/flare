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
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSProcessInfo
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecValueData

class IosWalletVault : WalletVault {
  private var authorizedContext: LAContext? = null

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
    val context = authorize(prompt, force = prompt.requireFreshAuthorization)
    val accessControl =
      SecAccessControlCreateWithFlags(
        null,
        kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
        kSecAccessControlUserPresence,
        null,
      ) ?: throw WalletVaultException.Unavailable("A device passcode is required")
    try {
      val lookup =
        dictionaryOf(
          kSecClass to kSecClassGenericPassword,
          kSecAttrService to SERVICE,
          kSecAttrAccount to slot.storageKey,
          kSecUseAuthenticationContext to context,
        )
      val attributes =
        dictionaryOf(kSecAttrAccessControl to accessControl, kSecValueData to secret.toNSData())
      val updateStatus =
        withCFDictionary(lookup) { query ->
          withCFDictionary(attributes) { values -> SecItemUpdate(query, values) }
        }
      val status =
        if (updateStatus == errSecItemNotFound) {
          val item =
            dictionaryOf(
              kSecClass to kSecClassGenericPassword,
              kSecAttrService to SERVICE,
              kSecAttrAccount to slot.storageKey,
              kSecAttrAccessControl to accessControl,
              kSecUseAuthenticationContext to context,
              kSecValueData to secret.toNSData(),
            )
          withCFDictionary(item) { SecItemAdd(it, null) }
        } else {
          updateStatus
        }
      if (status != errSecSuccess) {
        throw WalletVaultException.Unavailable("Keychain write failed ($status)")
      }
    } finally {
      CFRelease(accessControl)
    }
  }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray {
    val context = authorize(prompt, force = prompt.requireFreshAuthorization)
    val query =
      dictionaryOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to slot.storageKey,
        kSecReturnData to kCFBooleanTrue,
        kSecMatchLimit to kSecMatchLimitOne,
        kSecUseAuthenticationContext to context,
        kSecUseOperationPrompt to "${prompt.title}\n${prompt.subtitle}",
      )
    return withCFDictionary(query) { cfQuery ->
      memScoped {
        val result = alloc<CFTypeRefVar>()
        when (val status = SecItemCopyMatching(cfQuery, result.ptr)) {
          errSecSuccess -> {
            val data =
              CFBridgingRelease(result.value) as? NSData ?: throw WalletVaultException.Corrupted()
            data.toByteArray()
          }
          errSecUserCanceled -> throw WalletVaultException.Cancelled()
          errSecItemNotFound -> throw WalletVaultException.Corrupted()
          errSecInteractionNotAllowed ->
            throw WalletVaultException.Unavailable("Keychain authorization is unavailable")
          else -> throw WalletVaultException.Unavailable("Keychain read failed ($status)")
        }
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

  private fun monotonicMilliseconds(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()

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
    val query =
      dictionaryOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to slot.storageKey,
      )
    withCFDictionary(query) { SecItemDelete(it) }
  }

  private companion object {
    const val SERVICE = "xyz.mcxross.flare.wallet"
  }
}

private fun dictionaryOf(vararg pairs: Pair<Any?, Any?>): NSMutableDictionary =
  NSMutableDictionary().apply {
    pairs.forEach { (key, value) ->
      if (key != null && value != null) setObject(value, key as NSCopyingProtocol)
    }
  }

private fun <T> withCFDictionary(
  dictionary: NSMutableDictionary,
  block: (CFDictionaryRef?) -> T,
): T {
  val retained = platform.Foundation.CFBridgingRetain(dictionary) as? CFDictionaryRef
  return try {
    block(retained)
  } finally {
    if (retained != null) CFRelease(retained)
  }
}

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
  CFBridgingRelease(CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())) as NSData
}

private fun NSData.toByteArray(): ByteArray {
  val output = ByteArray(length.toInt())
  if (output.isEmpty()) return output
  val source = bytes ?: return output
  output.usePinned { pinned ->
    platform.posix.memcpy(pinned.addressOf(0), source, length.convert())
  }
  return output
}
