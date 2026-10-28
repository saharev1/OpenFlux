package io.openflux.ios

import io.openflux.app.platform.BrowserPage
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.service.ConnectionService
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import platform.Foundation.dataUsingEncoding
import platform.Foundation.timeIntervalSince1970
import platform.NetworkExtension.NEOnDemandRuleConnect
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.NetworkExtension.NETunnelProviderSession
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDidChangeNotification
import platform.NetworkExtension.NEVPNStatusDisconnected
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusInvalid
import platform.NetworkExtension.NEVPNStatusReasserting

/**
 * The shared UI's connection on iOS. The core runs in the packet-tunnel
 * extension (the same one the Swift UI starts), so this does what the Swift
 * VPNController does: write the profile's secret (and the Yandex sign-in's
 * cookies) to the shared Keychain, put what the extension needs into the VPN
 * profile's providerConfiguration, start it, and follow it — the system's VPN
 * status for the state, the extension's "log" IPC for the journal.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosConnectionService(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val accounts: Accounts,
) : ConnectionService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _traffic = MutableStateFlow(TrafficStats())
    override val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()
    private var lastUp = 0L
    private var lastDown = 0L
    private val _exitAddress = MutableStateFlow<ExitAddress>(ExitAddress.Unknown)
    override val exitAddress: StateFlow<ExitAddress> = _exitAddress.asStateFlow()
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()
    override val captcha: StateFlow<CaptchaPrompt?> = MutableStateFlow(null)
    override val exitShareLink: StateFlow<String?> = MutableStateFlow(null)
    override val socksAddress: StateFlow<String?> = MutableStateFlow(null)
    override val captchaPage: StateFlow<BrowserPage?> = MutableStateFlow(null)

    private var manager: NETunnelProviderManager? = null
    /** The profile the running tunnel was started with (found again by its id after a restart of the app). */
    private var active: Profile? = null
    private var since: Long = 0
    private var logJob: Job? = null
    private var lineId = 0L
    /** A stop asked for here (disconnect, or a restart onto another profile): not a failure to explain. */
    private var stopping = false

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(NEVPNStatusDidChangeNotification, null, NSOperationQueue.mainQueue) { _ ->
            refresh()
        }
        NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { list, _ ->
            scope.launch {
                manager = list?.firstOrNull() as? NETunnelProviderManager
                val conf = (manager?.protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration
                val id = conf?.get("kmpProfileId") as? String
                active = profiles.profiles.value.firstOrNull { it.id == id }
                refresh()
            }
        }
    }

    private fun now(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private fun note(text: String, level: LogLevel = LogLevel.Info) {
        _logs.value = (_logs.value + LogLine(++lineId, now(), text, level)).takeLast(2000)
    }

    override fun connect(profile: Profile, mode: ConnectionMode?) {
        if (mode != null && mode != ConnectionMode.Client) {
            _state.value = ConnectionState.Failed(profile, "На iPhone — только подключение к узлу")
            return
        }
        active = profile
        since = now()
        _state.value = ConnectionState.Connecting(profile, ConnectionMode.Client, since)
        val uuid = SharedKeychain.uuidFor(profile.id)
        if (!profile.stream && !SharedKeychain.setSecret(uuid, profile.secret)) {
            note("Не удалось сохранить ключ в Keychain", LogLevel.Error)
        }
        if (!SharedKeychain.setYandexSignIn(yandexSignIn(profile))) {
            note("Не удалось сохранить вход в Яндекс в Keychain", LogLevel.Error)
        }
        val conf = providerConfiguration(profile, uuid)
        scope.launch {
            try {
                val m = loadManager() ?: NETunnelProviderManager()
                // A tunnel already up (started by the Swift UI, or on demand) keeps the
                // configuration it started with: saving a new one does not restart it and
                // startVPNTunnel on a running tunnel does nothing. Stop it first.
                if (isUp(m.connection.status)) {
                    note("Останавливаю текущее подключение, чтобы запустить «${profile.name}»")
                    stopping = true
                    m.onDemandEnabled = false
                    save(m)
                    m.connection.stopVPNTunnel()
                    var waited = 0
                    while (m.connection.status != NEVPNStatusDisconnected && m.connection.status != NEVPNStatusInvalid && waited < 15_000) {
                        delay(200); waited += 200
                    }
                }
                val proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = "com.p1neapplexpress-saharev.openflux.tunnel"
                proto.serverAddress = "OpenFlux"
                proto.providerConfiguration = conf
                m.protocolConfiguration = proto
                m.localizedDescription = "OpenFlux"
                m.enabled = true
                // As the Swift UI: iOS brings the tunnel back when it drops.
                m.onDemandEnabled = true
                m.onDemandRules = listOf(NEOnDemandRuleConnect())
                save(m)
                manager = m
                stopping = false
                memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    if (!m.connection.startVPNTunnelAndReturnError(err.ptr)) {
                        fail(profile, "VPN не запустился: ${err.value?.localizedDescription ?: "неизвестная ошибка"}")
                    } else {
                        note("Подключаюсь через «${profile.name}»")
                    }
                }
            } catch (e: Exception) {
                fail(profile, e.message ?: "VPN не запустился")
            }
        }
    }

    /**
     * The Yandex sign-in's cookies for the profile, or none. The core gives them to all its Yandex
     * carriers (OpenFluxSetInitialCookies), the board included. A board must be joined as a guest,
     * so a profile with a board gets none.
     */
    private fun yandexSignIn(p: Profile): Map<String, String> {
        if (!settings.settings.value.useAccountSessions) return emptyMap()
        val kind = AccountKind.Yandex
        val types = p.carriers.map { it.type }
        if (types.none { it in kind.signedInTransports } || TransportType.BOARDS in types) return emptyMap()
        return accounts.validSession(kind)?.cookies.orEmpty()
    }

    private fun isUp(s: NEVPNStatus) =
        s == NEVPNStatusConnecting || s == NEVPNStatusConnected || s == NEVPNStatusReasserting || s == NEVPNStatusDisconnecting

    private suspend fun loadManager(): NETunnelProviderManager? = suspendCoroutine { c ->
        NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { list, _ -> c.resume(list?.firstOrNull() as? NETunnelProviderManager) }
    }

    /** saveToPreferences, then loadFromPreferences (iOS wants the reload before a start). */
    private suspend fun save(m: NETunnelProviderManager) {
        val saveError = suspendCoroutine { c -> m.saveToPreferencesWithCompletionHandler { e -> c.resume(e) } }
        if (saveError != null) throw IllegalStateException("Не удалось сохранить VPN-профиль: ${saveError.localizedDescription}")
        suspendCoroutine { c -> m.loadFromPreferencesWithCompletionHandler { _ -> c.resume(Unit) } }
    }

    /** What the extension reads (PacketTunnelProvider.startTunnel): the same keys the Swift VPNController writes. */
    private fun providerConfiguration(p: Profile, uuid: String): Map<Any?, Any?> {
        val debug = settings.settings.value.debugLevel > 0
        val base = mutableMapOf<Any?, Any?>(
            "transport" to p.transport.cliName,
            "url" to p.value.trim(),
            "maxToken" to if (p.transport.cliName == "oneme") p.value.trim() else "",
            "maxUid" to p.uid.trim(),
            "dns" to "",
            "udp" to "1",
            "split" to "",
            "directDomains" to "",
            "profileID" to uuid,
            "keySlot" to "",
            // The node's direct address must stay out of the tunnel (excludedRoutes): the
            // extension dials it itself, and captured by its own tunnel the carrier loops and
            // drops every half a minute, leaving the traffic on the slow document carriers.
            "nodeAddr" to if (p.session && !p.stream) p.sessionSpecs().firstOrNull { it.type == TransportType.DIRECT }?.value?.trim().orEmpty() else "",
            "session" to if (p.session && !p.stream) IosCoreSpecs.session(p) else "",
            "stream" to if (p.stream) "1" else "0",
            "debug" to if (debug) "1" else "0",
            "kmpProfileId" to p.id,
        )
        return base
    }

    private fun fail(profile: Profile?, message: String) {
        note(message, LogLevel.Error)
        _state.value = ConnectionState.Failed(profile, message)
    }

    override fun disconnect() {
        val m = manager ?: return
        active?.let { _state.value = ConnectionState.Disconnecting(it) }
        stopping = true
        // On-demand off first, or iOS would bring the tunnel straight back.
        m.onDemandEnabled = false
        m.saveToPreferencesWithCompletionHandler { _ ->
            m.loadFromPreferencesWithCompletionHandler { _ -> m.connection.stopVPNTunnel() }
        }
    }

    private fun refresh() {
        val status: NEVPNStatus = manager?.connection?.status ?: return
        val p = active
        // A new start (ours, or iOS on demand) after a stop asked for here: its failure is news again.
        if (status == NEVPNStatusConnecting) stopping = false
        _state.value = when {
            p == null -> if (status == NEVPNStatusConnected) _state.value else ConnectionState.Idle
            status == NEVPNStatusConnecting -> ConnectionState.Connecting(p, ConnectionMode.Client, since)
            status == NEVPNStatusConnected -> {
                if (_state.value !is ConnectionState.Connected) { since = now(); refreshExitAddress() }
                ConnectionState.Connected(p, ConnectionMode.Client, since)
            }
            status == NEVPNStatusReasserting -> ConnectionState.Reconnecting(p, ConnectionMode.Client, since)
            status == NEVPNStatusDisconnecting -> ConnectionState.Disconnecting(p)
            _state.value is ConnectionState.Failed -> _state.value
            else -> {
                _exitAddress.value = ExitAddress.Unknown
                // Down without being asked: the extension failed to start or iOS stopped it. Its
                // error (the core's own words, see PacketTunnelProvider) says why, instead of a
                // silent «Отключено».
                // (iOS passes through «Disconnecting» on the way down whoever stopped it: `stopping` tells.)
                if (!stopping && _state.value.let { it is ConnectionState.Connecting || it is ConnectionState.Connected || it is ConnectionState.Reconnecting || it is ConnectionState.Disconnecting }) {
                    explainStop(p, ranUntilGone = reportLastRun())
                }
                ConnectionState.Idle
            }
        }
        val running = status == NEVPNStatusConnecting || status == NEVPNStatusConnected || status == NEVPNStatusReasserting
        if (running && logJob == null) logJob = scope.launch { pollLog() }
        if (!running) {
            logJob?.cancel(); logJob = null
            lastUp = 0; lastDown = 0
            _traffic.value = TrafficStats()
        }
    }

    /**
     * The extension's own account of how its run ended (its line in the journal); true when it
     * was still "running" — the process went away without stopTunnel.
     */
    private fun reportLastRun(): Boolean {
        val report = IosHooks.lastTunnelReport?.invoke()?.lines()?.firstOrNull { it.isNotBlank() } ?: return false
        note(report, LogLevel.Error)
        return ", running," in report
    }

    /**
     * Why the tunnel went down. A process gone without stopTunnel after the core reported a failed
     * start is iOS tearing it down, not a kill; without that report it was killed — the 50 MB.
     */
    private fun explainStop(p: Profile, ranUntilGone: Boolean) {
        manager?.connection?.fetchLastDisconnectErrorWithCompletionHandler { err: NSError? ->
            if (ranUntilGone && err?.domain != "OpenFlux") {
                scope.launch { note("Расширение VPN завершено системой без остановки — скорее всего, по лимиту памяти (50 МБ)", LogLevel.Error) }
            }
            val why = err?.localizedDescription?.takeIf { it.isNotBlank() } ?: return@fetchLastDisconnectErrorWithCompletionHandler
            // The core did not start (PacketTunnelProvider's "OpenFlux" error: no carrier came
            // up, or the exit never answered the handshake in its 20 s). On-demand would start
            // it again at once, failing the same way every ~20 s for as long as the phone is on;
            // a tunnel that ran and dropped is not this, and keeps on-demand.
            if (err.domain == "OpenFlux") {
                manager?.let { m ->
                    m.onDemandEnabled = false
                    m.saveToPreferencesWithCompletionHandler { _ -> }
                }
            }
            scope.launch {
                if (err.domain == "OpenFlux") note("Автоподключение выключено: узел не отвечает. Подключитесь снова, когда он оживёт", LogLevel.Error)
                if (_state.value !is ConnectionState.Idle) return@launch
                why.lines().filter { it.isNotBlank() }.forEach { note(it, LogLevel.Error) }
                _state.value = ConnectionState.Failed(p, why.lines().lastOrNull { it.isNotBlank() } ?: why)
            }
        }
    }

    /** The extension's journal, drained once a second over the provider IPC (its own process). */
    private suspend fun pollLog() {
        while (scope.isActive) {
            val session = manager?.connection as? NETunnelProviderSession
            val stats = NSString.create(string = "stats").dataUsingEncoding(NSUTF8StringEncoding)
            if (session != null && stats != null) {
                runCatching {
                    session.sendProviderMessage(stats, null) { data: NSData? ->
                        val parts = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }.orEmpty().split('\t')
                        val up = parts.getOrNull(0)?.toLongOrNull()
                        val down = parts.getOrNull(1)?.toLongOrNull()
                        if (up != null && down != null) scope.launch { onStats(up, down, parts.getOrNull(2).orEmpty()) }
                    }
                }
            }
            val msg = NSString.create(string = "log").dataUsingEncoding(NSUTF8StringEncoding)
            if (session != null && msg != null) {
                runCatching {
                    session.sendProviderMessage(msg, null) { data: NSData? ->
                        val text = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }.orEmpty()
                        if (text.isNotEmpty()) scope.launch { text.lines().filter { it.isNotBlank() }.forEach { note(it, levelOf(it)) } }
                    }
                }
            }
            delay(1000)
        }
    }

    /** Totals since the tunnel started; the rate is this second's difference. */
    private fun onStats(up: Long, down: Long, carrier: String) {
        val restarted = up < lastUp || down < lastDown
        val upRate = if (restarted) 0 else up - lastUp
        val downRate = if (restarted) 0 else down - lastDown
        lastUp = up
        lastDown = down
        _traffic.value = TrafficStats(
            upBytesPerSec = upRate, downBytesPerSec = downRate, totalUp = up, totalDown = down,
            activeTransport = carrier, activeTransports = listOfNotNull(carrier.takeIf { it.isNotEmpty() }), live = true,
        )
    }

    private fun levelOf(line: String): LogLevel = when {
        line.contains("error", ignoreCase = true) || line.contains("ошибк", ignoreCase = true) -> LogLevel.Error
        line.contains("warn", ignoreCase = true) -> LogLevel.Warning
        else -> LogLevel.Debug
    }

    override fun refreshExitAddress() {
        if (_state.value !is ConnectionState.Connected) return
        _exitAddress.value = ExitAddress.Checking
        val url = NSURL.URLWithString("https://api.ipify.org") ?: return
        // The app's own traffic goes through the tunnel, so this is the exit's address.
        NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, error ->
            val ip = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString()?.trim() }
            scope.launch {
                _exitAddress.value = if (!ip.isNullOrEmpty() && error == null) ExitAddress.Known(ip)
                else ExitAddress.Unavailable(error?.localizedDescription ?: "нет ответа")
            }
        }.resume()
    }

    override fun clearLogs() { _logs.value = emptyList() }
    override fun openCaptcha() = Unit
    override fun submitCaptcha() = Unit
    override fun dismissCaptcha() = Unit
    override fun shutdown() { logJob?.cancel() }
}
