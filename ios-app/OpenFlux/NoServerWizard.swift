import SwiftUI
import UIKit

// «Без сервера»: выход — маленькая PHP-программа на обычном (хоть бесплатном)
// веб-хостинге. Мастер заливает её по FTP, проверяет сайт, запускает ноду и
// подключается через неё. Каждый шаг и каждое решение — в ядре
// (provision/phphost, OpenFluxPhpCall); здесь только форма, порядок шагов и
// слова для кодов ошибок. Порядок и формулировки — как в мастере овнера
// (OpenFluxClientShared: PhpWizardModel / PhpHosting), чтобы на всех
// платформах было одинаково.

// MARK: - Мост к ядру

/// Неудавшийся шаг: code — причина от ядра (phphost.Code*), param — к чему она.
struct PhpFailure: Error {
    let code: String
    var param: String = ""
    var detail: String = ""

    var message: String { PhpText.message(code, param, detail) }

    /// Ядро вошло в аккаунт, но не поняло, какая папка — сайт: выбор за человеком.
    var folderChoices: [String] {
        code == "ftp_no_webroot" ? param.split(separator: ",").map(String.init).filter { !$0.isEmpty } : []
    }
}

enum PhpBridge {
    /// Один шаг phphost.Call. Блокирует (заливка ~2 МБ, старт ждёт ноду) —
    /// поэтому уходит с главного потока. Ответ — data при успехе.
    static func call(_ method: String, _ params: [String: Any]) async throws -> Any? {
        let json = (try? JSONSerialization.data(withJSONObject: params))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let raw: String = await Task.detached(priority: .userInitiated) {
            method.withCString { m in
                json.withCString { p -> String in
                    guard let c = OpenFluxPhpCall(UnsafeMutablePointer(mutating: m),
                                                  UnsafeMutablePointer(mutating: p)) else { return "" }
                    defer { OpenFluxFreeString(c) }
                    return String(cString: c)
                }
            }
        }.value
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw PhpFailure(code: "bad_params", param: method, detail: "нет ответа от ядра") }
        if obj["ok"] as? Bool == true { return obj["data"] }
        throw PhpFailure(code: obj["code"] as? String ?? "",
                         param: obj["param"] as? String ?? "",
                         detail: obj["error"] as? String ?? "")
    }

    /// События заливки с прошлого вызова (phphost.Progress), по одному на строку.
    static func progress() -> [[String: Any]] {
        guard let c = OpenFluxPhpProgress() else { return [] }
        let s = String(cString: c)
        OpenFluxFreeString(c)
        return s.split(separator: "\n").compactMap {
            (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [String: Any]
        }
    }

    static func cancel() { OpenFluxPhpCancel() }

    /// Параметры шагов, которые говорят с сайтом ноды.
    static func siteParams(site: String, token: String, carrier: String, target: String) -> [String: Any] {
        ["url": site, "token": token, "carrier": carrier, "target": target]
    }

    /// Будит ноду stream-профиля перед подключением (start ничего не делает,
    /// если она уже работает). Без ответа за `timeout` — подключаемся как есть:
    /// из сети, где открыт только канал, хостинг напрямую недоступен, а нода,
    /// пока ей пользуются, продлевает себя сама.
    static func wake(_ p: Profile, timeout: TimeInterval = 15) async -> Bool {
        guard p.isStream, let site = p.phpSite, !site.isEmpty,
              let token = Secrets.phpToken(for: p.id) else { return false }
        var params = siteParams(site: site, token: token, carrier: p.transport,
                                target: p.url.trimmingCharacters(in: .whitespaces))
        params["chain"] = true
        params["waitSec"] = Int(max(5, timeout - 3))
        let watchdog = Task {
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            if !Task.isCancelled { cancel() }
        }
        defer { watchdog.cancel() }
        do {
            let data = try await call("start", params) as? [String: Any]
            return data?["running"] as? Bool ?? false
        } catch {
            return false
        }
    }
}

// MARK: - Слова и проверки формы (как PhpMessages / PhpHosts у овнера)

enum PhpText {
    static let tokenShape = "Свой ключ доступа: от 8 до 64 знаков — латинские буквы, цифры, «-» и «_»"

    static func message(_ code: String, _ param: String = "", _ detail: String = "") -> String {
        switch code {
        case "bad_params":
            switch param {
            case "host/user/password": return "Укажите адрес FTP-сервера, логин и пароль"
            case "token": return tokenShape
            default: return "Проверьте введённые данные"
            }
        case "ftp_connect":
            return "Не удалось подключиться к FTP-серверу\(param.isEmpty ? "" : " \(param)"): проверьте адрес и порт, "
                + "что хостинг пускает FTP из вашей сети и что это FTP или FTPS, а не SFTP (SSH)"
        case "ftp_login":
            return "FTP не принял логин или пароль. Пароль FTP на хостинге часто не совпадает с паролем от личного кабинета"
        case "ftp_tls": return "Не получилось установить защищённое соединение с FTP-сервером"
        case "ftp_no_webroot": return "Не понятно, какая папка на хостинге отдаётся как сайт. Выберите её из списка"
        case "ftp_dir_missing": return "Папки «\(param)» на хостинге нет"
        case "ftp_not_writable":
            return "В папку сайта\(param.isEmpty ? "" : " («\(param)»)") нельзя записывать: проверьте права или выберите другую папку"
        case "ftp_upload": return "Файл \(param) не загрузился: хостинг оборвал передачу или места не хватило. Повторите"
        case "site_unreachable":
            return "Сайт\(param.isEmpty ? "" : " \(param)") не отвечает: проверьте адрес. "
                + "У только что созданного домена бывает несколько минут, пока он заработает"
        case "site_antibot":
            return "Хостинг ставит перед сайтом проверку браузером, которую приложению не пройти. "
                + "Откройте адрес ноды в браузере и повторите"
        case "site_not_phpbox":
            return "Сайт отвечает, но это не нода: проверьте адрес сайта, папку, в которую залиты файлы, и что на хостинге включён PHP"
        case "site_token":
            return "Нода не приняла ключ доступа: на хостинге лежат файлы от другой установки. Установите заново"
        case "site_token_given":
            return "Нода не приняла этот ключ доступа. Ключ стоит в адресе страницы ноды после «k=» "
                + "и в файле config.php на хостинге (PHPBOX_TOKEN)"
        case "php_missing": return "На этом хостинге отключены PHP-функции, без которых нода не работает: \(param)"
        case "node_not_started":
            return "Нода не запустилась за отведённое время. Откройте адрес ноды в браузере: там виден её журнал"
        default:
            return detail.isEmpty ? "Ошибка установки на хостинг" : "Ошибка установки на хостинг: \(detail)"
        }
    }

    /// Как прошёл пароль FTP, если защищён он был плохо; nil — всё хорошо.
    static func security(_ level: String) -> String? {
        switch level {
        case "tls_unverified":
            return "Пароль FTP передан в шифрованном соединении, но сертификат хостинга не подтверждён (для бесплатных хостингов это обычно)."
        case "none":
            return "Этот хостинг принимает FTP только без шифрования: пароль FTP прошёл по сети открытым текстом. После установки смените его."
        default: return nil
        }
    }

    /// Состояние ноды одной строкой.
    static func nodeStatus(_ s: [String: Any]) -> String {
        let running = s["running"] as? Bool ?? false
        let stopping = s["stopping"] as? Bool ?? false
        guard running else { return stopping ? "останавливается" : "остановлена" }
        let state = s["state"] as? [String: Any] ?? [:]
        var out = (state["phase"] as? String) == "connecting" ? "подключается" : "работает"
        if let gen = state["gen"] as? Int, gen > 0 { out += " · поколение \(gen)" }
        if s["chain"] as? Bool == true, let next = s["next_in"] as? Int { out += " · смена через \(next) с" }
        if let d = s["draining"] as? Int, d > 0 { out += " · предыдущее дорабатывает соединения" }
        if stopping { out += " · останавливается" }
        return out
    }

    private static let hostRe = try! NSRegularExpression(pattern: "^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
    private static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    static func ftpHost(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("ftp://") { s.removeFirst(6) }
        while s.hasSuffix("/") { s.removeLast() }
        return !s.isEmpty && matches(hostRe, s) ? s : nil
    }

    /// Адрес сайта как его берёт мастер: схема и хост, без пути.
    static func siteURL(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespaces)
        if s.isEmpty || s.contains(where: { $0.isWhitespace }) { return nil }
        if !s.contains("://") { s = "https://" + s }
        guard let r = s.range(of: "://") else { return nil }
        let scheme = s[..<r.lowerBound].lowercased()
        guard scheme == "http" || scheme == "https" else { return nil }
        let rest = s[r.upperBound...]
        let host = String(rest.split(whereSeparator: { "/?#".contains($0) }).first ?? "").lowercased()
        let bare = String(host.split(separator: ":").first ?? "")
        guard host.contains("."), matches(hostRe, bare) else { return nil }
        return "\(scheme)://\(host)"
    }

    static func ftpProblem(host: String, port: String, user: String, password: String) -> String? {
        if ftpHost(host) == nil { return "Укажите адрес FTP-сервера, например ftpupload.net" }
        if !port.isEmpty, !(Int(port.trimmingCharacters(in: .whitespaces)).map { (1...65535).contains($0) } ?? false) {
            return "Порт FTP — число от 1 до 65535 (обычно 21)"
        }
        if user.trimmingCharacters(in: .whitespaces).isEmpty { return "Укажите логин FTP" }
        if password.isEmpty { return "Укажите пароль FTP" }
        return nil
    }

    private static let tokenRe = try! NSRegularExpression(pattern: "^[0-9A-Za-z_-]{8,64}$")
    static func chosenTokenProblem(_ t: String) -> String? { t.isEmpty || matches(tokenRe, t) ? nil : tokenShape }

    static func tokenProblem(_ t: String) -> String? {
        if t.trimmingCharacters(in: .whitespaces).isEmpty { return "Укажите ключ доступа ноды" }
        if t.contains(where: { $0.isWhitespace }) || t.count > 200 { return "Ключ доступа — одна строка без пробелов" }
        return nil
    }

    private static let roomRe = try! NSRegularExpression(
        pattern: "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private static let roomURLRe = try! NSRegularExpression(
        pattern: "^https://interview\\.cups\\.online/live-coding/\\?room=[0-9a-fA-F-]{36}$")
    static func cupsRoom(_ input: String) -> String? {
        let s = input.trimmingCharacters(in: .whitespaces)
        if matches(roomRe, s) { return "https://interview.cups.online/live-coding/?room=" + s.lowercased() }
        return matches(roomURLRe, s) ? s : nil
    }

    private static let mailruRe = try! NSRegularExpression(
        pattern: "^https://cloud\\.mail\\.ru/public/[A-Za-z0-9_-]{2,64}/[A-Za-z0-9_-]{2,128}$")
    static func cleanMailru(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespaces)
        if let i = s.firstIndex(where: { $0 == "?" || $0 == "#" }) { s = String(s[..<i]) }
        while s.hasSuffix("/") { s.removeLast() }
        return matches(mailruRe, s) ? s : nil
    }

    /// Адрес страницы ноды (…/cupsexit.php?k=KEY&room=UUID или
    /// …/mailruexit.php?k=KEY&url=DOC): из него берутся сайт, ключ и канал.
    struct NodeAddress { let site: String; let token: String; let carrier: TransportKind?; let target: String }

    static func nodeAddress(_ input: String) -> NodeAddress? {
        let s = input.trimmingCharacters(in: .whitespaces)
        guard let site = siteURL(s) else { return nil }
        let comps = URLComponents(string: s.contains("://") ? s : "https://" + s)
        let path = (comps?.path ?? "").lowercased()
        var q: [String: String] = [:]
        for item in comps?.queryItems ?? [] { q[item.name] = item.value ?? "" }
        let carrier: TransportKind? = path.hasSuffix("cupsexit.php") ? .cupsonline
            : path.hasSuffix("mailruexit.php") ? .mail : nil
        var target = ""
        if carrier == .cupsonline { target = cupsRoom(q["room"] ?? "") ?? cupsRoom(q["url"] ?? "") ?? "" }
        if carrier == .mail { target = q["url"] ?? "" }
        return NodeAddress(site: site, token: (q["k"] ?? "").trimmingCharacters(in: .whitespaces),
                           carrier: carrier, target: target)
    }

    /// Ключ на экране — только концы: целиком он секрет.
    static func mask(_ t: String) -> String {
        t.count <= 8 ? String(repeating: "•", count: t.count) : "\(t.prefix(4))…\(t.suffix(4))"
    }
}

// MARK: - Модель мастера

@MainActor
final class NoServerWizardModel: ObservableObject {
    enum Step: Int { case hosting = 1, channel, install, verify, done }

    @Published var step: Step = .hosting
    /// Что делает текущий шаг; nil — ничего не идёт.
    @Published var busy: String?
    @Published var error: String?
    @Published var notice: String?

    // Шаг 1: хостинг.
    /// Файлы ноды уже на хостинге (залиты руками): нужны сайт и ключ, без FTP.
    @Published var existing = false
    @Published var tokenInput = ""
    @Published var ftpHost = ""
    @Published var ftpPort = "21"
    @Published var ftpUser = ""
    @Published var ftpPassword = ""
    @Published var siteInput = ""
    @Published var allowPlainFTP = false
    @Published var folder = ""
    @Published var chosenToken = ""
    @Published var folderChoices: [String] = []

    // Шаг 2: канал.
    @Published var name = ""
    @Published var carrier: TransportKind = .cupsonline
    @Published var mailruInput = ""
    @Published var knownRoom = ""
    private(set) var target = ""
    private var cupsRoomURL = ""

    // Шаг 3: установка.
    @Published var uploadFraction: Double?
    @Published var installedToken: String?
    @Published var tokenReused = false
    @Published var parserSkipped = false
    @Published var securityLevel = ""
    @Published var nodeStatus: String?
    private(set) var siteURL = ""

    // Шаг 4: проверка и итог.
    @Published var verifiedIP = ""
    @Published var verifyFailed: String?
    @Published var shareLink = ""
    @Published var saved = false

    private var ftp: [String: Any]?
    private var work: Task<Void, Never>?
    private let tunnel: TunnelController
    private let port: Int

    init(tunnel: TunnelController, port: Int) {
        self.tunnel = tunnel
        self.port = port
    }

    /// Ушли бы сейчас — на хостинге осталась бы нода без сохранённого профиля.
    var unsaved: Bool { installedToken != nil && !saved }
    var canRemove: Bool { !existing && installedToken != nil && ftp != nil }
    var securityNote: String? { PhpText.security(securityLevel) }

    // MARK: шаг 1

    func probeHosting() {
        if let p = PhpText.ftpProblem(host: ftpHost, port: ftpPort, user: ftpUser, password: ftpPassword) {
            error = p; return
        }
        guard let site = PhpText.siteURL(siteInput) else {
            error = "Укажите адрес сайта на хостинге, например https://ваш-сайт.ru"; return
        }
        if let p = PhpText.chosenTokenProblem(chosenToken.trimmingCharacters(in: .whitespaces)) { error = p; return }
        siteURL = site
        let target: [String: Any] = [
            "host": PhpText.ftpHost(ftpHost) ?? ftpHost.trimmingCharacters(in: .whitespaces),
            "port": Int(ftpPort.trimmingCharacters(in: .whitespaces)) ?? 21,
            "user": ftpUser.trimmingCharacters(in: .whitespaces),
            "password": ftpPassword,
            // Как у овнера: "auto" — TLS, затем TLS без проверки сертификата
            // (обычное дело на бесплатных хостингах), затем открытый FTP; ядро
            // сообщает, что получилось. "none" — только открытый.
            "tls": allowPlainFTP ? "none" : "auto",
            "dir": folder.trimmingCharacters(in: .whitespaces),
        ]
        run("Проверяю вход на хостинг…", onFailure: { [weak self] f in
            guard let self, !f.folderChoices.isEmpty else { return false }
            self.folderChoices = f.folderChoices
            self.error = f.message
            return true
        }) { [self] in
            let probe = try await PhpBridge.call("probe", ["ftp": target, "url": site]) as? [String: Any] ?? [:]
            securityLevel = probe["security"] as? String ?? ""
            ftp = target
            folderChoices = []
            if name.isEmpty { name = "Свой хостинг · \(target["host"] as? String ?? "")" }
            step = .channel
        }
    }

    /// Вставили адрес страницы ноды целиком — ключ и канал подставляются сами.
    func onExistingAddress(_ input: String) {
        siteInput = input.trimmingCharacters(in: .whitespaces)
        guard let node = PhpText.nodeAddress(siteInput) else { return }
        if !node.token.isEmpty { tokenInput = node.token }
        switch node.carrier {
        case .cupsonline?:
            carrier = .cupsonline
            if !node.target.isEmpty { knownRoom = node.target; cupsRoomURL = node.target }
        case .mail?:
            carrier = .mail
            if !node.target.isEmpty { mailruInput = node.target }
        default: break
        }
    }

    func useExisting() {
        guard let node = PhpText.nodeAddress(siteInput) else {
            error = "Укажите адрес сайта с нодой или адрес её страницы, например https://ваш-сайт.ru"; return
        }
        if let p = PhpText.tokenProblem(tokenInput) { error = p; return }
        error = nil
        siteURL = node.site
        if name.isEmpty { name = "Свой хостинг · \(node.site.replacingOccurrences(of: "https://", with: ""))" }
        step = .channel
    }

    func chooseFolder(_ dir: String) {
        folder = dir
        folderChoices = []
        error = nil
        probeHosting()
    }

    func back() {
        guard busy == nil else { return }
        error = nil; notice = nil
        if step == .channel { step = .hosting }
        else if step == .install, installedToken == nil { step = .channel }
    }

    // MARK: шаг 2

    func prepareChannel() {
        error = nil
        if name.trimmingCharacters(in: .whitespaces).isEmpty { error = "Назовите профиль"; return }
        switch carrier {
        case .cupsonline:
            if !cupsRoomURL.isEmpty { target = cupsRoomURL; step = .install; return }
            run("Создаю комнату cups.online…") { [self] in
                let room = try await PhpBridge.call("newRoom", [:]) as? [String: Any] ?? [:]
                guard let url = room["url"] as? String, !url.isEmpty else {
                    throw PhpFailure(code: "bad_params", param: "newRoom", detail: "cups.online не дал комнату")
                }
                cupsRoomURL = url
                target = url
                step = .install
            }
        case .mail:
            guard let link = PhpText.cleanMailru(mailruInput) else {
                error = "Нужна публичная ссылка на документ Mail.ru: https://cloud.mail.ru/public/…"; return
            }
            target = link
            step = .install
        default:
            error = "Этот транспорт не подходит для режима без сервера"
        }
    }

    // MARK: шаг 3

    func install() {
        if existing { attachExisting(); return }
        guard let ftp else { return }
        let chosen = chosenToken.trimmingCharacters(in: .whitespaces)
        run("Загружаю файлы на хостинг…") { [self] in
            uploadFraction = nil
            var params: [String: Any] = ["ftp": ftp, "url": siteURL]
            if !chosen.isEmpty { params["token"] = chosen }
            let poll = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    for p in PhpBridge.progress() { self?.onProgress(p) }
                }
            }
            defer { poll.cancel() }
            let done = try await PhpBridge.call("deploy", params) as? [String: Any] ?? [:]
            poll.cancel()
            let token = done["token"] as? String ?? ""
            installedToken = token
            tokenReused = done["token_reused"] as? Bool ?? false
            parserSkipped = !((done["skipped"] as? [Any]) ?? []).isEmpty
            if let sec = done["security"] as? String, !sec.isEmpty { securityLevel = sec }
            uploadFraction = 1
            busy = "Проверяю, что сайт отвечает…"
            try await waitForSite(token)
            try await startNode(token)
            step = .verify
            await verify()
        }
    }

    private func onProgress(_ p: [String: Any]) {
        let phase = p["phase"] as? String ?? ""
        let n = p["n"] as? Int ?? 0, of = p["of"] as? Int ?? 0
        let done = (p["bytes_done"] as? NSNumber)?.doubleValue ?? 0
        let total = (p["bytes_total"] as? NSNumber)?.doubleValue ?? 0
        if total > 0 { uploadFraction = min(1, done / total) }
        switch phase {
        case "upload" where of > 0: busy = "Загружаю файлы на хостинг: \(n) из \(of)…"
        case "retry": busy = "Хостинг оборвал передачу \(p["file"] as? String ?? ""), отправляю заново…"
        default: break
        }
    }

    private func attachExisting() {
        let token = tokenInput.trimmingCharacters(in: .whitespaces)
        run("Проверяю ноду на сайте…") { [self] in
            do {
                try await waitForSite(token)
            } catch let f as PhpFailure where f.code == "site_token" {
                throw PhpFailure(code: "site_token_given")
            }
            installedToken = token
            tokenReused = true
            try await startNode(token)
            step = .verify
            await verify()
        }
    }

    /// Свежий домен может заработать не сразу: ждём около минуты. Стоит ждать
    /// только «сайт не отвечает» / «это не нода»; остальное окончательно.
    private func waitForSite(_ token: String) async throws {
        var last: PhpFailure?
        for attempt in 1...12 {
            do {
                _ = try await PhpBridge.call("check", PhpBridge.siteParams(
                    site: siteURL, token: token, carrier: carrier.rawValue, target: target))
                return
            } catch let f as PhpFailure where f.code == "site_unreachable" || f.code == "site_not_phpbox" {
                last = f
                busy = "Жду, пока сайт заработает (\(attempt) из 12)…"
                try await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
        throw last ?? PhpFailure(code: "site_unreachable", param: siteURL)
    }

    private func startNode(_ token: String) async throws {
        busy = "Запускаю ноду…"
        var params = PhpBridge.siteParams(site: siteURL, token: token, carrier: carrier.rawValue, target: target)
        params["chain"] = true
        let state = try await PhpBridge.call("start", params) as? [String: Any] ?? [:]
        nodeStatus = PhpText.nodeStatus(state)
    }

    // MARK: шаг 4

    func retryVerify() {
        verifyFailed = nil
        step = .verify
        run("Подключаюсь через новую ноду…") { [self] in await verify() }
    }

    /// Проверка — настоящим запросом через новую ноду: stream-клиент в процессе
    /// приложения (SOCKS5 на локальном порту) и адрес, с которого сайт нас видит.
    private func verify() async {
        shareLink = (try? await PhpBridge.call("link", [
            "name": name.trimmingCharacters(in: .whitespaces), "carrier": carrier.rawValue, "target": target,
        ]) as? [String: Any])?["link"] as? String ?? ""
        verifiedIP = ""
        verifyFailed = nil
        busy = "Подключаюсь через новую ноду…"
        tunnel.stop()
        guard tunnel.startStream(transport: carrier, url: target, port: port) else {
            verifyFailed = "Не удалось запустить клиент: порт \(port) занят?"
            return
        }
        defer { tunnel.stop() }
        let deadline = Date().addingTimeInterval(150)
        var lastProblem = ""
        while Date() < deadline, !Task.isCancelled {
            busy = lastProblem.isEmpty ? "Открываю сайт через ноду…" : "Нода пока молчит, пробую ещё раз…"
            do {
                verifiedIP = try await Self.exitIP(socksPort: port)
                step = .done
                return
            } catch {
                lastProblem = error.localizedDescription
                try? await Task.sleep(nanoseconds: 4_000_000_000)
            }
        }
        if !Task.isCancelled {
            verifyFailed = "Запрос через ноду не прошёл" + (lastProblem.isEmpty ? "" : ": \(lastProblem)")
        }
    }

    private static func exitIP(socksPort: Int) async throws -> String {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.connectionProxyDictionary = ["SOCKSEnable": 1, "SOCKSProxy": "127.0.0.1", "SOCKSPort": socksPort]
        cfg.timeoutIntervalForRequest = 25
        let (data, _) = try await URLSession(configuration: cfg).data(from: URL(string: "https://api.ipify.org")!)
        let ip = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !ip.isEmpty, ip.count < 64 else { throw URLError(.badServerResponse) }
        return ip
    }

    func skipVerification() {
        verifyFailed = nil
        step = .done
    }

    // MARK: итог

    /// Профиль и ключ ноды для сохранения; nil — нечего сохранять.
    func makeProfile() -> (Profile, String)? {
        guard let token = installedToken, !target.isEmpty else { return nil }
        var p = Profile(name: name.trimmingCharacters(in: .whitespaces), transport: carrier.rawValue, url: target)
        p.stream = true
        p.phpSite = siteURL
        return (p, token)
    }

    func markSaved() { saved = true }

    func refreshNode() {
        guard let token = installedToken else { return }
        run("Спрашиваю ноду…") { [self] in
            let s = try await PhpBridge.call("node", PhpBridge.siteParams(
                site: siteURL, token: token, carrier: carrier.rawValue, target: target)) as? [String: Any] ?? [:]
            nodeStatus = PhpText.nodeStatus(s)
        }
    }

    func stopNode() {
        guard let token = installedToken else { return }
        run("Останавливаю ноду…") { [self] in
            _ = try await PhpBridge.call("stop", PhpBridge.siteParams(
                site: siteURL, token: token, carrier: carrier.rawValue, target: target))
            nodeStatus = "остановлена"
            notice = "Нода остановлена. Подключение через неё больше не заработает, пока её не запустят снова."
        }
    }

    func openPanel() {
        guard let token = installedToken else { return }
        run("Открываю панель ноды…") { [self] in
            let d = try await PhpBridge.call("page", PhpBridge.siteParams(
                site: siteURL, token: token, carrier: carrier.rawValue, target: target)) as? [String: Any]
            if let s = d?["url"] as? String, let u = URL(string: s) { _ = await UIApplication.shared.open(u) }
        }
    }

    func removeFromHosting() {
        guard let ftp else { return }
        let token = installedToken
        run("Удаляю ноду с хостинга…") { [self] in
            if let token {
                _ = try? await PhpBridge.call("stop", PhpBridge.siteParams(
                    site: siteURL, token: token, carrier: carrier.rawValue, target: target))
            }
            _ = try await PhpBridge.call("remove", ["ftp": ftp, "url": siteURL])
            installedToken = nil
            nodeStatus = nil
            notice = "Файлы ноды удалены с хостинга."
        }
    }

    /// Закрытие мастера: бросаем начатый шаг и забываем пароль FTP.
    func close() {
        work?.cancel()
        PhpBridge.cancel()
        if step == .verify { tunnel.stop() }
        ftpPassword = ""
        ftp = nil
    }

    /// Один шаг за раз; PhpFailure становится текстом ошибки, если onFailure
    /// его не разобрал сам.
    private func run(_ message: String, onFailure: ((PhpFailure) -> Bool)? = nil,
                     _ body: @escaping () async throws -> Void) {
        guard busy == nil else { return }
        busy = message
        error = nil
        notice = nil
        tunnel.appendExternal("[хостинг] \(message)")
        work = Task { [weak self] in
            do {
                try await body()
            } catch is CancellationError {
            } catch let f as PhpFailure {
                self?.tunnel.appendExternal("[хостинг] \(f.code): \(f.detail)")
                if onFailure?(f) != true { self?.error = f.message }
            } catch {
                self?.error = error.localizedDescription
            }
            self?.busy = nil
        }
    }
}

// MARK: - Экран

struct NoServerWizardView: View {
    @StateObject private var model: NoServerWizardModel
    let vpnActive: Bool
    /// Сохранить профиль и ключ ноды.
    let onSave: (Profile, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var advanced = false
    @State private var confirmClose = false

    init(tunnel: TunnelController, port: Int, vpnActive: Bool, onSave: @escaping (Profile, String) -> Void) {
        _model = StateObject(wrappedValue: NoServerWizardModel(tunnel: tunnel, port: port))
        self.vpnActive = vpnActive
        self.onSave = onSave
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Шаг \(model.step == .done ? 4 : model.step.rawValue) из 4")
                            .font(.caption).foregroundColor(.accentColor)
                        Text(subtitle).font(.footnote).foregroundColor(.secondary)
                    }
                }
                switch model.step {
                case .hosting: hostingStep
                case .channel: channelStep
                case .install: installStep
                case .verify: verifyStep
                case .done: doneStep
                }
                if let b = model.busy {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(b).font(.footnote)
                        }
                        if let f = model.uploadFraction, model.step == .install { ProgressView(value: f) }
                    }
                }
                if let e = model.error {
                    Section { Label(e, systemImage: "exclamationmark.triangle").foregroundColor(.orange).font(.footnote) }
                }
                if let n = model.notice {
                    Section { Text(n).font(.footnote) }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(model.step == .done && model.saved ? "Готово" : "Закрыть") {
                        if model.unsaved { confirmClose = true } else { close() }
                    }
                }
                if model.step == .channel || (model.step == .install && model.installedToken == nil) {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("Назад") { model.back() }.disabled(model.busy != nil)
                    }
                }
            }
            .alert("Нода на хостинге, но профиль не сохранён", isPresented: $confirmClose) {
                Button("Сохранить и закрыть") { save(); close() }
                Button("Закрыть без профиля", role: .destructive) { close() }
                Button("Остаться", role: .cancel) {}
            } message: {
                Text("Без профиля к ней с этого устройства не подключиться.")
            }
        }
        .interactiveDismissDisabled(model.busy != nil || model.unsaved)
    }

    private var title: String {
        switch model.step {
        case .hosting: return "Без своего сервера"
        case .channel: return "Канал связи"
        case .install: return model.existing ? "Подключение ноды" : "Установка на хостинг"
        case .verify: return "Проверка"
        case .done: return "Нода готова"
        }
    }

    private var subtitle: String {
        switch model.step {
        case .hosting: return "Нода встанет на любой хостинг с PHP и FTP."
        case .channel: return "Через что устройство и нода на хостинге будут находить друг друга."
        case .install: return model.existing ? "Мастер проверит ноду на сайте и запустит её."
            : "Мастер зальёт файлы ноды и запустит её."
        case .verify: return "Подключаюсь через новую ноду и открываю сайт."
        case .done: return model.verifiedIP.isEmpty ? "Нода установлена, но проверка не завершена."
            : "Трафик выходит в интернет с адреса \(model.verifiedIP)."
        }
    }

    private var idle: Bool { model.busy == nil }

    // MARK: шаги

    @ViewBuilder private var hostingStep: some View {
        Section {
            Picker("", selection: $model.existing) {
                Text("Залить по FTP").tag(false)
                Text("Нода уже залита").tag(true)
            }
            .pickerStyle(.segmented)
            .disabled(!idle)
        }
        if vpnActive {
            Section {
                Text("VPN включён: установка и проверка пойдут через него. Если хостинг не отвечает — отключите VPN на время мастера.")
                    .font(.caption).foregroundColor(.secondary)
            }
        }
        if model.existing {
            Section {
                TextField("Адрес сайта или страницы ноды", text: Binding(
                    get: { model.siteInput }, set: { model.onExistingAddress($0) }))
                    .keyboardType(.URL).textInputAutocapitalization(.never).disableAutocorrection(true)
                SecureField("Ключ доступа ноды", text: $model.tokenInput)
                    .textInputAutocapitalization(.never).disableAutocorrection(true)
            } header: { Text("Нода на хостинге") } footer: {
                Text("Файлы ноды уже лежат на хостинге (вы залили их сами или с другого устройства) — FTP не нужен. "
                     + "Можно вставить адрес страницы ноды целиком: ключ, канал и комната подставятся сами. "
                     + "Ключ стоит в адресе страницы после «k=» и в config.php (PHPBOX_TOKEN).")
            }
            Section {
                Button("Дальше") { model.useExisting() }.disabled(!idle)
            }
        } else {
            Section {
                TextField("Адрес FTP-сервера (ftp.example.com)", text: $model.ftpHost)
                    .keyboardType(.URL).textInputAutocapitalization(.never).disableAutocorrection(true)
                TextField("Логин FTP", text: $model.ftpUser)
                    .textInputAutocapitalization(.never).disableAutocorrection(true)
                SecureField("Пароль FTP", text: $model.ftpPassword)
                TextField("Адрес сайта (https://ваш-сайт.ru)", text: $model.siteInput)
                    .keyboardType(.URL).textInputAutocapitalization(.never).disableAutocorrection(true)
                if model.ftpHost.isEmpty {
                    Button("InfinityFree: ftpupload.net") { model.ftpHost = "ftpupload.net" }.font(.footnote)
                }
            } header: { Text("Ваш хостинг") } footer: {
                Text("Подойдёт любой хостинг, бесплатный или платный, лишь бы был PHP и доступ по FTP или FTPS "
                     + "(SFTP по SSH не подходит). FTP-данные — в панели хостинга. "
                     + "Пароль нужен только на время установки: OpenFlux его не сохраняет.")
            }
            if !model.folderChoices.isEmpty {
                Section("Какая папка отдаётся как сайт?") {
                    ForEach(model.folderChoices, id: \.self) { dir in
                        Button(dir.isEmpty ? "(корень)" : dir) { model.chooseFolder(dir) }
                    }
                }
            }
            Section {
                DisclosureGroup("Дополнительно", isExpanded: $advanced) {
                    TextField("Порт FTP", text: $model.ftpPort).keyboardType(.numberPad)
                    TextField("Папка сайта (пусто — найти самому)", text: $model.folder)
                        .textInputAutocapitalization(.never).disableAutocorrection(true)
                    TextField("Свой ключ доступа (необязательно)", text: $model.chosenToken)
                        .textInputAutocapitalization(.never).disableAutocorrection(true)
                    Toggle("Разрешить FTP без шифрования", isOn: $model.allowPlainFTP)
                    Text("Некоторые хостинги не умеют защищённый FTP — тогда пароль пойдёт открытым текстом, после установки смените его. "
                         + "Свой ключ: 8–64 знака, латиница, цифры, «-» и «_».")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }
            Section {
                Button("Проверить вход") { model.probeHosting() }.disabled(!idle)
            }
        }
    }

    @ViewBuilder private var channelStep: some View {
        Section {
            TextField("Название профиля", text: $model.name)
        } header: { Text("Профиль") }
        Section {
            Picker("Канал", selection: $model.carrier) {
                Text("cups.online").tag(TransportKind.cupsonline)
                Text("Mail.ru").tag(TransportKind.mail)
            }
            .pickerStyle(.segmented)
            .disabled(!idle)
            if model.carrier == .mail {
                TextField("https://cloud.mail.ru/public/…", text: $model.mailruInput)
                    .keyboardType(.URL).textInputAutocapitalization(.never).disableAutocorrection(true)
            }
        } header: { Text("Канал связи") } footer: {
            if model.carrier == .cupsonline {
                Text(model.knownRoom.isEmpty
                     ? "Комнату мастер создаст сам, вводить ничего не нужно. Это самый простой вариант."
                     : "Комната из адреса ноды: \(model.knownRoom.components(separatedBy: "room=").last ?? "")")
            } else {
                Text("Создайте в Облаке Mail.ru любой документ, откройте доступ по ссылке «Редактирование для всех» и вставьте ссылку.")
            }
        }
        Section {
            Button("Дальше") { model.prepareChannel() }.disabled(!idle)
        }
    }

    @ViewBuilder private var installStep: some View {
        Section {
            if model.existing {
                Text("Мастер проверит, что сайт отвечает как нода с этим ключом, запустит её и подключится через неё. Файлы на хостинге он не трогает.")
                    .font(.footnote)
            } else {
                Text("В папку сайта будет загружено около 2 МБ: файлы ноды и файл с её ключом доступа. Остальные файлы на хостинге мастер не трогает. "
                     + "Потом он проверит, что сайт отвечает, запустит ноду и подключится через неё.")
                    .font(.footnote)
            }
            if let s = model.securityNote { Text(s).font(.caption).foregroundColor(.orange) }
        }
        Section {
            Button(model.existing ? "Проверить и запустить" : "Установить") { model.install() }.disabled(!idle)
        }
    }

    @ViewBuilder private var verifyStep: some View {
        Section {
            Text("Первый запрос через новую ноду может занять до минуты: ей нужно присоединиться к каналу.")
                .font(.footnote)
            if let s = model.nodeStatus { Text("Нода: \(s)").font(.caption).foregroundColor(.secondary) }
        }
        if let f = model.verifyFailed {
            Section {
                Text(f).font(.footnote).foregroundColor(.orange)
                Text("Нода стоит на хостинге, но запрос через неё не прошёл. Подождите минуту и повторите. "
                     + "Если не помогает, откройте панель ноды: там её журнал.")
                    .font(.caption).foregroundColor(.secondary)
                Button("Повторить проверку") { model.retryVerify() }.disabled(!idle)
                Button("Открыть панель ноды") { model.openPanel() }.disabled(!idle)
                Button("Пропустить проверку") { model.skipVerification() }.disabled(!idle)
            }
        }
    }

    @ViewBuilder private var doneStep: some View {
        Section {
            if !model.verifiedIP.isEmpty {
                Label("Через ноду работает: выход \(model.verifiedIP)", systemImage: "checkmark.seal.fill")
                    .foregroundColor(.green)
            }
            if let s = model.nodeStatus { Text("Нода: \(s)").font(.footnote) }
            if model.tokenReused, !model.existing {
                Text("Нода на этом хостинге уже была: её ключ доступа сохранён, прежние адреса работают.")
                    .font(.caption).foregroundColor(.secondary)
            }
            if model.parserSkipped {
                Text("Хостинг не принял файл со считывателем ссылок для страницы ноды. Нода работает и без него.")
                    .font(.caption).foregroundColor(.secondary)
            }
            if model.saved {
                Label("Профиль сохранён и выбран", systemImage: "checkmark").foregroundColor(.green)
            } else {
                Button("Сохранить профиль на этом устройстве") { save() }
            }
        }
        if !model.shareLink.isEmpty {
            Section {
                if let img = makeQRImage(model.shareLink) {
                    Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                        .frame(maxWidth: 220).frame(maxWidth: .infinity)
                }
                Button("Скопировать ссылку") { UIPasteboard.general.string = model.shareLink }
            } header: { Text("Для других устройств") } footer: {
                Text("В ссылке и QR нет ключей, но любой, у кого они есть, сможет выходить в интернет через ваш хостинг. "
                     + "Управлять нодой по ним нельзя.")
            }
        }
        if let t = model.installedToken {
            Section {
                HStack {
                    Text("Ключ доступа"); Spacer()
                    Text(PhpText.mask(t)).font(.system(.footnote, design: .monospaced)).foregroundColor(.secondary)
                }
                Button("Скопировать ключ доступа") { UIPasteboard.general.string = t }
                Button("Спросить ноду") { model.refreshNode() }.disabled(!idle)
                Button("Открыть панель ноды") { model.openPanel() }.disabled(!idle)
                Button("Остановить ноду", role: .destructive) { model.stopNode() }.disabled(!idle)
                if model.canRemove {
                    Button("Удалить ноду с хостинга", role: .destructive) { model.removeFromHosting() }.disabled(!idle)
                }
            } header: { Text("Нода") } footer: {
                Text("Ключ нужен, чтобы добавить эту ноду на другом устройстве («Нода уже залита») или открыть её страницу — храните как пароль. "
                     + "Нода сама продлевает себя, пока вы ей пользуетесь; если она остановится, приложение разбудит её при подключении.")
            }
        }
    }

    private func save() {
        guard let made = model.makeProfile() else { return }
        onSave(made.0, made.1)
        model.markSaved()
    }

    private func close() {
        model.close()
        dismiss()
    }
}
