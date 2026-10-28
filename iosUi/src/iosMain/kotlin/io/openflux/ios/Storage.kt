package io.openflux.ios

import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.Profile
import io.openflux.desktop.service.AccountRepository
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import platform.Foundation.NSLock
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/**
 * Profiles of the shared UI, as JSON in the app's defaults. A profile's secret
 * is part of the model (as on Android and the desktop); the tunnel extension
 * gets it from the shared Keychain when it starts, never from these defaults.
 */
class DefaultsProfiles(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : ProfileRepository {
    private val key = "kmp.profiles"
    private val _profiles = MutableStateFlow(load())
    override val profiles: StateFlow<List<Profile>> = _profiles

    private fun load(): List<Profile> =
        defaults.stringForKey(key)?.let { runCatching { storeJson.decodeFromString(ListSerializer(Profile.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun save(list: List<Profile>) {
        _profiles.value = list
        defaults.setObject(storeJson.encodeToString(ListSerializer(Profile.serializer()), list), key)
    }

    override fun upsert(profile: Profile) {
        val list = _profiles.value
        save(if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile)
    }

    override fun delete(id: String) = save(_profiles.value.filterNot { it.id == id })

    override fun newId(): String = NSUUID().UUIDString()
}

/**
 * The Services screen's sign-ins, as JSON in the Keychain. A session is a
 * login, so it stays out of the defaults and is never restored to another
 * device. The UI and the background checks both save sessions, one write at a time.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainAccounts : AccountRepository {
    private val service = "openflux.accounts"
    private val account = "sessions"
    private val lock = NSLock()
    private val _sessions = MutableStateFlow(load())
    override val sessions: StateFlow<Map<AccountKind, AccountSession>> = _sessions

    private fun load(): Map<AccountKind, AccountSession> {
        val stored = Keychain.read(service, account, group = null) ?: return emptyMap()
        // A session this build cannot read (a service it does not know) is skipped; the others still load.
        val items = runCatching { storeJson.parseToJsonElement(stored).jsonArray }.getOrNull().orEmpty()
        return items.mapNotNull { runCatching { storeJson.decodeFromJsonElement(AccountSession.serializer(), it) }.getOrNull() }
            .associateBy { it.kind }
    }

    private fun update(transform: (Map<AccountKind, AccountSession>) -> Map<AccountKind, AccountSession>) {
        lock.lock()
        try {
            val next = transform(_sessions.value)
            val json = storeJson.encodeToString(ListSerializer(AccountSession.serializer()), next.values.toList())
            // As the desktop's file: a failed write is an error, and the sessions stay as stored.
            check(Keychain.write(service, account, json, group = null, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)) {
                "Не удалось сохранить вход в Keychain"
            }
            _sessions.value = next
        } finally {
            lock.unlock()
        }
    }

    override fun save(session: AccountSession) = update { it + (session.kind to session) }

    override fun remove(kind: AccountKind) = update { it - kind }
}

class DefaultsSettings(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : SettingsRepository {
    private val key = "kmp.settings"
    private val _settings = MutableStateFlow(
        defaults.stringForKey(key)?.let { runCatching { storeJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings(),
    )
    override val settings: StateFlow<AppSettings> = _settings

    override fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        defaults.setObject(storeJson.encodeToString(AppSettings.serializer(), next), key)
    }
}
