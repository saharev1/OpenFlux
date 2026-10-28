package io.openflux.ios

import io.openflux.desktop.model.AccountCookies
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * What the tunnel extension reads from the Keychain group app and extension
 * share, in the same items the Swift UI writes. The encryption secret
 * (Secrets.setEncryptionKey): service "openflux.encryption", account the
 * profile's UUID. The Cookie header for the core's Yandex carriers
 * (Secrets.setCaptchaCookies). The VPN profile's providerConfiguration carries
 * only the id, never the secret.
 */
@OptIn(ExperimentalForeignApi::class)
object SharedKeychain {
    private const val SERVICE = "openflux.encryption"
    private const val COOKIES_SERVICE = "openflux.captcha"
    private const val COOKIES_ACCOUNT = "cookies"
    private const val SIGN_IN_ACCOUNT = "signin"
    private const val GROUP = "8GQH8GQ252.com.p1neapplexpress-saharev.openflux"

    // The extension reconnects on its own after a network change, maybe locked.
    fun setSecret(profileUuid: String, secret: String): Boolean =
        Keychain.write(SERVICE, profileUuid, secret, GROUP, kSecAttrAccessibleAfterFirstUnlock)

    /**
     * Puts the Yandex sign-in's [cookies] into the Cookie header the extension gives the core's
     * Yandex carriers before they start. It first takes back the cookies it put there last time,
     * so empty [cookies] only clear those. A passed check's cookies, which the Swift UI keeps in
     * the same item, stay; when both have a cookie of the same name, the sign-in's value wins.
     */
    fun setYandexSignIn(cookies: Map<String, String>): Boolean {
        val given = AccountCookies.parseHeader(readCookies(SIGN_IN_ACCOUNT))
        val kept = AccountCookies.parseHeader(readCookies(COOKIES_ACCOUNT)).filterNot { (name, value) -> given[name] == value }
        return writeCookies(COOKIES_ACCOUNT, kept + cookies) && writeCookies(SIGN_IN_ACCOUNT, cookies)
    }

    private fun readCookies(account: String) = Keychain.read(COOKIES_SERVICE, account, GROUP).orEmpty()

    // An item added here is, like the sessions, never restored to another device; one the Swift UI added keeps the setting the Swift UI gave it.
    private fun writeCookies(account: String, cookies: Map<String, String>) =
        Keychain.write(COOKIES_SERVICE, account, AccountCookies.header(cookies), GROUP, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)

    /**
     * The UUID the extension looks the secret up by. A shared profile's id is a
     * UUID when this app made it; one that is not (made elsewhere) gets a UUID
     * of its own, remembered so the same profile always maps to the same item.
     */
    fun uuidFor(profileId: String): String {
        NSUUID(uUIDString = profileId).let { u -> if (u.UUIDString().equals(profileId, ignoreCase = true)) return u.UUIDString() }
        val defaults = NSUserDefaults.standardUserDefaults
        val key = "kmp.uuid.$profileId"
        defaults.stringForKey(key)?.let { return it }
        return NSUUID().UUIDString().also { defaults.setObject(it, key) }
    }
}

/**
 * Generic-password items holding a string. [group] null is the default group,
 * the first of the app's keychain-access-groups (the one it shares with the extension).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object Keychain {
    /**
     * Sets the item's value, adding the item when there is none; a blank [value] removes it.
     * Updated in place, as Secrets.swift does, so the extension never finds it missing.
     */
    fun write(service: String, account: String, value: String, group: String?, accessible: CFStringRef?): Boolean =
        withItem(service, account, group) { item ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return@withItem SecItemDelete(item).let { it == errSecSuccess || it == errSecItemNotFound }
            val bytes = NSString.create(string = trimmed).dataUsingEncoding(NSUTF8StringEncoding) ?: return@withItem false
            val data = CFBridgingRetain(bytes)
            val change = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            CFDictionaryAddValue(change, kSecValueData, data)
            var status = SecItemUpdate(item, change)
            if (status == errSecItemNotFound) {
                CFDictionaryAddValue(item, kSecValueData, data)
                CFDictionaryAddValue(item, kSecAttrAccessible, accessible)
                status = SecItemAdd(item, null)
            }
            CFRelease(change)
            CFRelease(data)
            status == errSecSuccess
        }

    fun read(service: String, account: String, group: String?): String? =
        withItem(service, account, group) { item ->
            CFDictionaryAddValue(item, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(item, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val out = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(item, out.ptr) != errSecSuccess) return@memScoped null
                (CFBridgingRelease(out.value) as? NSData)?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
            }
        }

    /** Runs [block] on a query for the item; the query is released after it. */
    private fun <T> withItem(service: String, account: String, group: String?, block: (CFMutableDictionaryRef?) -> T): T {
        val item = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        val groupRef = group?.let { CFBridgingRetain(it) }
        CFDictionaryAddValue(item, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(item, kSecAttrService, serviceRef)
        CFDictionaryAddValue(item, kSecAttrAccount, accountRef)
        if (groupRef != null) CFDictionaryAddValue(item, kSecAttrAccessGroup, groupRef)
        try {
            return block(item)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            groupRef?.let { CFRelease(it) }
            CFRelease(item)
        }
    }
}
