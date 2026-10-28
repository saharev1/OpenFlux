package io.openflux.ios

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.CoreShareLinkCodec
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.NodeKeepingConnection
import io.openflux.desktop.service.PhpHostingService
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIViewController

/**
 * The shared OpenFlux UI for the iOS app. Swift presents this controller; the
 * VPN itself stays in the Swift packet-tunnel extension.
 */
fun MainViewController(): UIViewController {
    val platform = IosPlatformServices()
    // No subscription, bot or OpenFlux sign-in here (AppContainer's No* defaults):
    // buying inside an iOS app must go through Apple, so the app only connects.
    val accounts = Accounts(
        repo = KeychainAccounts(),
        browser = IosAccountBrowser(),
        probe = IosSessionProbe(),
        now = platform::now,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val profiles = DefaultsProfiles()
    val settings = DefaultsSettings()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val codec = CoreShareLinkCodec(IosCoreLinks)
    val phpHosting = PhpHostingService(IosPhpTransport(), clock = platform::now)
    // The Swift UI's profiles, once (see SwiftProfileMigration).
    scope.launch {
        SwiftProfileMigration.run(profiles, codec, platform.now()) { id -> settings.update { it.copy(selectedProfileId = id) } }
    }
    val container = AppContainer(
        profiles = profiles,
        settings = settings,
        // A "без сервера" profile first asks its node on the hosting to run (as on the desktop).
        connection = NodeKeepingConnection(IosConnectionService(profiles, settings, accounts), phpHosting, scope),
        platform = platform,
        shareCodec = codec,
        nodeWizard = IosNodeWizard(),
        accounts = accounts,
        phpHosting = phpHosting,
    )
    return ComposeUIViewController {
        OpenFluxApp(container, NoScrollbars, remember { Shortcuts() }, IosBrowserViews)
    }
}
