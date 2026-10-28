package io.openflux.ios

import io.openflux.desktop.model.NodeWizardException

import io.openflux.desktop.model.AppSettings
import io.openflux.app.platform.BrowserPage
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.YandexDocument
import io.openflux.desktop.service.ConnectionService
import io.openflux.desktop.service.NodeWizardService
import io.openflux.desktop.service.PlatformKind
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

// First step of the iOS port: enough of the platform for the shared screens to
// run. Profiles and settings live in memory, the connection never connects;
// the real services (NETunnelProviderManager, the core, Keychain) replace these
// one by one.

class InMemoryProfiles : ProfileRepository {
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    override val profiles: StateFlow<List<Profile>> = _profiles
    override fun upsert(profile: Profile) {
        _profiles.value = _profiles.value.filterNot { it.id == profile.id } + profile
    }
    override fun delete(id: String) { _profiles.value = _profiles.value.filterNot { it.id == id } }
    override fun newId(): String = NSUUID().UUIDString()
}

class InMemorySettings : SettingsRepository {
    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings
    override fun update(transform: (AppSettings) -> AppSettings) { _settings.value = transform(_settings.value) }
}

class IdleConnection : ConnectionService {
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)
    override val traffic: StateFlow<TrafficStats> = MutableStateFlow(TrafficStats())
    override val exitAddress: StateFlow<ExitAddress> = MutableStateFlow(ExitAddress.Unknown)
    override val logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val captcha: StateFlow<CaptchaPrompt?> = MutableStateFlow(null)
    override val exitShareLink: StateFlow<String?> = MutableStateFlow(null)
    override val socksAddress: StateFlow<String?> = MutableStateFlow(null)
    override val captchaPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override fun connect(profile: Profile, mode: ConnectionMode?) = Unit
    override fun disconnect() = Unit
    override fun refreshExitAddress() = Unit
    override fun clearLogs() { logs.value = emptyList() }
    override fun openCaptcha() = Unit
    override fun submitCaptcha() = Unit
    override fun dismissCaptcha() = Unit
    override fun shutdown() = Unit
}

object NoShareCodec : ShareLinkCodec {
    override suspend fun encode(config: ShareConfig): String = error("ссылки ещё не подключены на iOS")
    override suspend fun decode(link: String): ShareConfig = error("ссылки ещё не подключены на iOS")
}

class NoNodeWizard : NodeWizardService {
    // A NodeWizardException, which the server screens show as their error; any other
    // exception escapes their coroutine and takes the whole app down (TestFlight crash
    // reports from build 115/116: «вводишь адрес, пароль — приложение закрывается»).
    private fun no(): Nothing = throw NodeWizardException(
        "Установка узла с iPhone пока недоступна. Поставьте узел с компьютера (OpenFlux для Windows, macOS или Linux) " +
            "и добавьте его сюда ссылкой или QR-кодом",
    )
    override suspend fun connect(target: SshTarget, source: NodeCoreSource): ServerProbe = no()
    override suspend fun newChannel(): NewChannel = no()
    override suspend fun plan(channel: String, transports: List<NodeTransport>, withCookies: Boolean, autoUpdate: Boolean, mode: NodeMode): NodePlan = no()
    override suspend fun apply(channel: NewChannel, transports: List<NodeTransport>, port: Int, autoUpdate: Boolean, sudoPassword: String, cookieHeader: String, mode: NodeMode) = no()
    override suspend fun remove(channel: String, sudoPassword: String) = no()
    override suspend fun checkDocument(documentUrl: String) = no()
    override suspend fun createCupsRooms(): String = no()
    override suspend fun shareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>): String = no()
    override suspend fun resolve(host: String): Set<String> = emptySet()
    override val documentPage: StateFlow<BrowserPage?> = MutableStateFlow(null)
    override suspend fun createDocument(fileName: String, onStep: (String) -> Unit): YandexDocument = no()
    override fun cancelDocument() = Unit
    override val logs = MutableStateFlow<List<LogLine>>(emptyList())
    override fun clearLogs() { logs.value = emptyList() }
    override fun note(text: String, level: LogLevel) = Unit
    override fun close() = Unit
}
