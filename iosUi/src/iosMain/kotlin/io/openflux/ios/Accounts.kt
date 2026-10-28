package io.openflux.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.openflux.app.platform.BrowserPage
import io.openflux.app.platform.BrowserViews
import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.YandexDisk
import io.openflux.desktop.service.AccountBrowser
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.ProbeResult
import io.openflux.desktop.service.SessionProbe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectZero
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSinceDate
import platform.WebKit.WKContentWorld
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [AccountBrowser] on WKWebView: one page at a time, in a website data store
 * that keeps nothing, so closing the page wipes its cookies. Yandex only for
 * now: the tunnel extension passes the core no other service's sign-in.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAccountBrowser : AccountBrowser {
    private val _page = MutableStateFlow<BrowserPage?>(null)
    override val page: StateFlow<BrowserPage?> = _page.asStateFlow()
    private val current get() = _page.value as? WebPage

    // WebKit is main-thread only, and so are the callers: the UI's and the screen models' scopes.
    override val url get() = current?.view?.URL?.absoluteString.orEmpty()
    override val loading get() = current?.view?.loading ?: false
    override val closed get() = current == null

    override suspend fun open(kind: AccountKind, url: String, cookies: Map<String, String>, onStep: (String) -> Unit) {
        if (kind != AccountKind.Yandex) throw AccountException("Вход в ${kind.label} на iOS ещё не подключён")
        withContext(Dispatchers.Main) {
            close()
            val page = WebPage()
            // Shown at once, so a close while the cookies are being set stops this page too.
            _page.value = page
            // In place before the page loads, so its first request is already signed in.
            for ((name, value) in cookies) page.setCookie(name, value, kind.cookieDomain)
            if (current === page) page.load(url)
        }
    }

    override suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
        val page = current ?: error("Страница закрыта")
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) { page.evaluate(script) } ?: error("Страница не ответила")
    }

    override fun load(url: String) = onMain { current?.load(url) }

    override suspend fun cookies(kind: AccountKind): Map<String, String> = withContext(Dispatchers.Main) {
        val hosts = kind.cookieUrls.mapNotNull { NSURL.URLWithString(it)?.host }
        val now = NSDate()
        val live = current?.cookies().orEmpty().filter { c ->
            hosts.any { c.appliesTo(it) } && (c.expiresDate?.let { it.timeIntervalSinceDate(now) > 0 } ?: true)
        }
        // The most specific domain wins, as a browser sends them (BuiltInBrowser.selectForUrl on the desktop).
        val selected = linkedMapOf<String, Pair<Int, String>>()
        for (c in live) {
            if (c.name.isEmpty() || (c.name + c.value).any { it == ';' || it == '\r' || it == '\n' }) continue
            val weight = c.domain.removePrefix(".").length
            if (weight >= (selected[c.name]?.first ?: -1)) selected[c.name] = weight to c.value
        }
        selected.mapValues { it.value.second }
    }

    override fun close() {
        val page = current ?: return
        _page.value = null
        onMain { page.close() }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }

    private fun NSHTTPCookie.appliesTo(host: String): Boolean {
        val d = domain.removePrefix(".")
        return host == d || host.endsWith(".$d")
    }

    private companion object {
        /** As long as the desktop's built-in browser waits for a script. */
        const val EVALUATE_TIMEOUT_MS = 90_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private class WebPage : BrowserPage {
    val view = WKWebView(
        frame = CGRectZero.readValue(),
        configuration = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() },
    )

    // WKWebView keeps its UI delegate weakly.
    private val windows = SameWindow()

    init {
        // Yandex ties a sign-in to the user agent: the core's own, as on the desktop.
        view.customUserAgent = YandexDisk.USER_AGENT
        view.UIDelegate = windows
    }

    fun load(address: String) {
        val url = NSURL.URLWithString(address) ?: return
        view.loadRequest(NSURLRequest.requestWithURL(url))
    }

    suspend fun setCookie(name: String, value: String, domain: String) {
        val properties = mapOf<Any?, Any?>(
            NSHTTPCookieName to name, NSHTTPCookieValue to value, NSHTTPCookieDomain to domain,
            NSHTTPCookiePath to "/", NSHTTPCookieSecure to "TRUE",
        )
        val cookie = NSHTTPCookie.cookieWithProperties(properties) ?: return
        suspendCancellableCoroutine { c -> view.configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { c.resume(Unit) } }
    }

    suspend fun cookies(): List<NSHTTPCookie> = suspendCancellableCoroutine { c ->
        view.configuration.websiteDataStore.httpCookieStore.getAllCookies { list -> c.resume(list.orEmpty().filterIsInstance<NSHTTPCookie>()) }
    }

    /**
     * Runs [expression] (a Promise is awaited) and returns its value as a
     * string; a thrown error comes back as {"state":"fail","error":...}, as on the desktop.
     */
    suspend fun evaluate(expression: String): String = suspendCancellableCoroutine { c ->
        val body = "try { return String(await ($expression)); } " +
            "catch (e) { return JSON.stringify({state: 'fail', error: String((e && e.message) || e)}); }"
        view.callAsyncJavaScript(body, arguments = null, inFrame = null, inContentWorld = WKContentWorld.pageWorld) { result, error ->
            if (error != null) c.resumeWithException(IllegalStateException(error.localizedDescription)) else c.resume(result?.toString().orEmpty())
        }
    }

    fun close() {
        view.stopLoading()
        view.UIDelegate = null
    }
}

/** A link for a new window (another way to sign in, say) opens in the page itself: there is one window. */
@OptIn(ExperimentalForeignApi::class)
private class SameWindow : NSObject(), WKUIDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        if (forNavigationAction.targetFrame == null) webView.loadRequest(forNavigationAction.request)
        return null
    }
}

/** Shows an [IosAccountBrowser] page inside the shared UI. */
object IosBrowserViews : BrowserViews {
    @OptIn(ExperimentalForeignApi::class)
    @Composable
    override fun Page(page: BrowserPage, modifier: Modifier) {
        val view = (page as? WebPage)?.view ?: return
        key(page) { UIKitView(factory = { view }, modifier = modifier) }
    }
}

/**
 * Opens Yandex Disk with the saved cookies, without following redirects: a
 * sign-in page means the session is gone (HttpSessionProbe on the desktop).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSessionProbe : SessionProbe {
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate = NoRedirects(), delegateQueue = null,
    )

    override suspend fun probe(kind: AccountKind, cookies: Map<String, String>): ProbeResult {
        if (kind != AccountKind.Yandex) return ProbeResult.Offline
        val url = NSURL.URLWithString("https://disk.yandex.ru/client/disk") ?: return ProbeResult.Offline
        val request = NSMutableURLRequest(uRL = url).apply {
            HTTPShouldHandleCookies = false
            setTimeoutInterval(15.0)
            setValue(YandexDisk.USER_AGENT, forHTTPHeaderField = "User-Agent")
            setValue(AccountCookies.header(cookies), forHTTPHeaderField = "Cookie")
        }
        return suspendCancellableCoroutine { c ->
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                val http = response as? NSHTTPURLResponse
                c.resume(
                    if (error != null || http == null) ProbeResult.Offline
                    else {
                        val code = http.statusCode.toInt()
                        val body = if (code in 200..299) data?.let { d -> d.bytes?.reinterpret<ByteVar>()?.readBytes(d.length.toInt())?.decodeToString() }.orEmpty() else ""
                        classify(code, http.valueForHTTPHeaderField("Location").orEmpty(), body)
                    },
                )
            }
            c.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    private fun classify(code: Int, location: String, body: String): ProbeResult = when {
        "showcaptcha" in location || "showcaptcha" in body -> ProbeResult.NeedsCheck
        code in 300..399 && "passport.yandex" in location -> ProbeResult.Expired
        code == 200 && "\"sk\":\"" in body -> ProbeResult.SignedIn
        code == 200 -> ProbeResult.Expired
        else -> ProbeResult.Offline
    }

    private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) = completionHandler(null)
    }
}
