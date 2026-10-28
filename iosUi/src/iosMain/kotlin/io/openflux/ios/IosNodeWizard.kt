package io.openflux.ios

import io.openflux.app.platform.BrowserPage
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodeCoreSource
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeUpgrade
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.YandexDocument
import io.openflux.desktop.service.NodeWizardService
import io.openflux.ios.core.OpenFluxFreeString
import io.openflux.ios.core.OpenFluxNodeApply
import io.openflux.ios.core.OpenFluxNodeCheckDocument
import io.openflux.ios.core.OpenFluxNodeConnect
import io.openflux.ios.core.OpenFluxNodeCreateAvitoRooms
import io.openflux.ios.core.OpenFluxNodeCreateCupsRooms
import io.openflux.ios.core.OpenFluxNodeDisconnect
import io.openflux.ios.core.OpenFluxNodeNewChannel
import io.openflux.ios.core.OpenFluxNodePlan
import io.openflux.ios.core.OpenFluxNodePushSignIn
import io.openflux.ios.core.OpenFluxNodeRemove
import io.openflux.ios.core.OpenFluxNodeSetTransports
import io.openflux.ios.core.OpenFluxNodeShareLink
import io.openflux.ios.core.OpenFluxNodeShareLinkContext
import io.openflux.ios.core.OpenFluxNodeShowChannel
import io.openflux.ios.core.OpenFluxNodeUpgrade
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.posix.AF_UNSPEC
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo

/**
 * The "Свой сервер" wizard on iOS: the core's Node* calls (SSH to the VDS, the
 * pinned installer) through the C API, as AndroidNodeWizard makes them through
 * gomobile. The calls block, so they run off the main thread, one at a time.
 * The channel's Yandex document is not made here (no built-in browser yet):
 * the wizard's own steps without a document still install a node.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNodeWizard : NodeWizardService {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    override val documentPage: StateFlow<BrowserPage?> = MutableStateFlow(null)

    private var lineId = 0L
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    override suspend fun connect(target: SshTarget, source: NodeCoreSource): ServerProbe {
        val reply = call("nodeConnect", "${target.host}:${target.port} (${target.user}) core=${source.id}") {
            OpenFluxNodeConnect(
                target.host.cstr, target.port, target.user.cstr, target.password.cstr,
                target.privateKey.cstr, target.passphrase.cstr, target.hostKey.cstr, source.id.cstr,
            )
        }
        return json.decodeFromJsonElement(ServerProbe.serializer(), reply.getValue("probe"))
    }

    override suspend fun newChannel(): NewChannel {
        val reply = call("nodeNewChannel") { OpenFluxNodeNewChannel() }
        return NewChannel(reply.string("id"), reply.string("key"))
    }

    override suspend fun plan(channel: String, transports: List<NodeTransport>, withCookies: Boolean, autoUpdate: Boolean, mode: NodeMode): NodePlan {
        val reply = call("nodePlan", "channel=$channel ${transports.names()} withCookies=$withCookies autoUpdate=$autoUpdate mode=${mode.cli}") {
            OpenFluxNodePlan(channel.cstr, 0, transports.json().cstr, withCookies.c, autoUpdate.c, mode.cli.cstr)
        }
        return json.decodeFromJsonElement(NodePlan.serializer(), reply.getValue("plan"))
    }

    override suspend fun apply(
        channel: NewChannel,
        transports: List<NodeTransport>,
        port: Int,
        autoUpdate: Boolean,
        sudoPassword: String,
        cookieHeader: String,
        mode: NodeMode,
    ) {
        call("nodeApply", "channel=${channel.id} ${transports.names()} port=$port autoUpdate=$autoUpdate mode=${mode.cli}") {
            OpenFluxNodeApply(
                channel.id.cstr, transports.json().cstr, channel.key.cstr, port, autoUpdate.c,
                sudoPassword.cstr, cookieHeader.cstr, mode.cli.cstr,
            )
        }
    }

    override suspend fun createCupsRooms(): String = call("nodeCreateCupsRooms") { OpenFluxNodeCreateCupsRooms() }.string("rooms")

    override suspend fun createAvitoRooms(): String = call("nodeCreateAvitoRooms") { OpenFluxNodeCreateAvitoRooms() }.string("rooms")

    override suspend fun remove(channel: String, sudoPassword: String) {
        call("nodeRemove", "channel=$channel") { OpenFluxNodeRemove(channel.cstr, sudoPassword.cstr) }
    }

    override suspend fun upgrade(sudoPassword: String): NodeUpgrade {
        val reply = call("nodeUpgrade") { OpenFluxNodeUpgrade(sudoPassword.cstr) }
        return json.decodeFromJsonElement(NodeUpgrade.serializer(), reply)
    }

    override suspend fun showChannel(channel: String, sudoPassword: String): InstalledChannel {
        val reply = call("nodeShowChannel", "channel=$channel") { OpenFluxNodeShowChannel(channel.cstr, sudoPassword.cstr) }
        return json.decodeFromJsonElement(InstalledChannel.serializer(), reply.getValue("channel"))
    }

    override suspend fun setTransports(channel: String, transports: List<NodeTransport>, sudoPassword: String, cookieHeader: String, mode: NodeMode?): InstalledChannel {
        val reply = call("nodeSetTransports", "channel=$channel ${transports.names()} mode=${mode?.cli ?: "keep"}") {
            OpenFluxNodeSetTransports(channel.cstr, transports.json().cstr, cookieHeader.cstr, sudoPassword.cstr, mode?.cli.orEmpty().cstr)
        }
        return json.decodeFromJsonElement(InstalledChannel.serializer(), reply.getValue("channel"))
    }

    override suspend fun pushSignIn(channel: String, transports: List<NodeTransport>, sudoPassword: String, cookieHeader: String): InstalledChannel {
        val reply = call("nodePushSignIn", "channel=$channel ${transports.names()}") {
            OpenFluxNodePushSignIn(channel.cstr, transports.json().cstr, cookieHeader.cstr, sudoPassword.cstr)
        }
        return json.decodeFromJsonElement(InstalledChannel.serializer(), reply.getValue("channel"))
    }

    override suspend fun channelShareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>, context: String): String =
        call("nodeShareLinkContext", "$host:$port ${transports.names()}") {
            OpenFluxNodeShareLinkContext(name.cstr, transports.json().cstr, key.cstr, host.cstr, port, context.cstr)
        }.string("link")

    override suspend fun checkDocument(documentUrl: String) {
        call("nodeCheckDocument") { OpenFluxNodeCheckDocument(documentUrl.cstr) }
    }

    override suspend fun shareLink(name: String, key: String, host: String, port: Int, transports: List<NodeTransport>): String =
        call("nodeShareLink", "$host:$port ${transports.names()}") {
            OpenFluxNodeShareLink(name.cstr, transports.json().cstr, key.cstr, host.cstr, port)
        }.string("link")

    override suspend fun resolve(host: String): Set<String> = withContext(Dispatchers.IO) {
        val out = mutableSetOf<String>()
        memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_UNSPEC
                ai_socktype = SOCK_STREAM
            }
            val res = alloc<CPointerVar<addrinfo>>()
            if (getaddrinfo(host, null, hints.ptr, res.ptr) == 0) {
                var p = res.value
                val buf = allocArray<ByteVar>(NI_MAXHOST)
                while (p != null) {
                    val a = p.pointed
                    if (getnameinfo(a.ai_addr, a.ai_addrlen, buf, NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST) == 0) out += buf.toKString()
                    p = a.ai_next
                }
                freeaddrinfo(res.value)
            }
        }
        log(LogLevel.Debug, "мастер: resolve $host -> ${out.joinToString().ifEmpty { "ничего" }}")
        out
    }

    /** The wizard's document step is not wired up on iOS yet: it is made on a computer. */
    override suspend fun createDocument(fileName: String, onStep: (String) -> Unit): YandexDocument =
        throw NodeWizardException("Документ Яндекса с iPhone пока не создаётся: выберите каналы без документа (прямое подключение, cups.online) или создайте документ на компьютере")

    override fun cancelDocument() = Unit

    override fun close() {
        log(LogLevel.Info, "мастер: закрываю ядро")
        // Closes SSH, which removes the downloaded installer from the server.
        CoroutineScope(Dispatchers.IO).launch { OpenFluxNodeDisconnect() }
    }

    override fun clearLogs() {
        _logs.value = emptyList()
    }

    override fun note(text: String, level: LogLevel) = log(level, text)

    private fun log(level: LogLevel, text: String) {
        // Negative ids: never collide with the connection's own (positive) ids in the Logs tab.
        _logs.update { (it + LogLine(--lineId, (NSDate().timeIntervalSince1970 * 1000).toLong(), text, level)).takeLast(MAX_LOG_LINES) }
    }

    /** One core call off the main thread, its {"ok":…} JSON read; a failure becomes the NodeWizardException the screens show. */
    private suspend fun call(method: String, summary: String = "", block: () -> CPointer<ByteVar>?): JsonObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val label = if (summary.isEmpty()) method else "$method $summary"
            log(LogLevel.Debug, "мастер: → $label")
            val raw = block()
            val text = try {
                raw?.toKString()
            } finally {
                raw?.let { OpenFluxFreeString(it) }
            }
            val reply = runCatching { Json.parseToJsonElement(text.orEmpty()).jsonObject }.getOrElse {
                log(LogLevel.Error, "мастер: ← $label ядро не ответило")
                throw NodeWizardException("Ядро OpenFlux не ответило мастеру")
            }
            if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val error = reply["error"]?.jsonPrimitive?.content ?: "Ошибка мастера"
                log(LogLevel.Error, "мастер: ← $label ошибка: $error")
                throw NodeWizardException(
                    error,
                    hostKey = reply["hostKey"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
                    trust = reply.flag("trust"),
                    mismatch = reply.flag("mismatch"),
                    sudo = reply.flag("sudo"),
                    captcha = reply.flag("captcha"),
                )
            }
            log(LogLevel.Debug, "мастер: ← $label ok")
            reply
        }
    }

    private val Boolean.c: Int get() = if (this) 1 else 0

    private fun List<NodeTransport>.json() = json.encodeToString(ListSerializer(NodeTransport.serializer()), this)

    /** For the log: the types only, links and rooms stay out. */
    private fun List<NodeTransport>.names() = "transports=" + joinToString(",") { it.type }.ifEmpty { "direct" }

    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.content ?: throw NodeWizardException("Ядро не вернуло $name")

    private fun JsonObject.flag(name: String) = this[name]?.jsonPrimitive?.booleanOrNull == true

    private companion object {
        const val MAX_LOG_LINES = 2000
    }
}
