import Foundation

/// The `openflux://v1/…` share standard.
///
/// Encoding and decoding deliberately go through the Go core rather than being
/// reimplemented here: the format is flate + base64url + JSON plus a set of
/// validation rules, and a second implementation would drift from the CLI and
/// from Android sooner or later. Swift only maps the resulting JSON onto a
/// Profile.
///
/// The link carries the encryption secret — whoever sees it can join the exit.
/// Treat it like the key file.
struct ShareConfig: Decodable {
    struct Transport: Decodable {
        let type: String
        let name: String?
        let url: String?
        let priority: Int?
        let dial: String?
    }

    let name: String?
    let negotiate: Bool?
    let codec: String?
    let secret: String?
    let context: String?
    let transports: [Transport]

    /// The carrier this app should actually run: the highest-priority one we
    /// support. `direct` is skipped here — we carry it separately as the
    /// profile's bootstrap channel rather than as the main transport.
    var primary: Transport? {
        let usable = transports.filter { TransportKind(rawValue: normalized($0.type)) != nil && $0.type != "direct" }
        if let best = usable.max(by: { ($0.priority ?? 0) < ($1.priority ?? 0) }) { return best }
        // Ссылка только с direct — тогда он и есть основной носитель.
        return transports.first { $0.type == "direct" }
    }

    /// A `direct` entry alongside the main one: the channel used to pass an
    /// interactive captcha from the exit's address.
    var directDial: String? {
        transports.first { $0.type == "direct" }?.dial
    }

    /// Стандарт зовёт VOLGA "vyandex", у нас это "volga".
    private func normalized(_ t: String) -> String { t == "vyandex" ? "volga" : t }

    var primaryKind: TransportKind? {
        guard let p = primary else { return nil }
        return TransportKind(rawValue: normalized(p.type))
    }
}

/// Раскладывает конфигурацию из ссылки в наши профили.
///
/// Модель стандарта богаче нашей: он описывает ОДНУ сессию из нескольких
/// носителей с приоритетами, а у нас один носитель на профиль плюс мультиплекс
/// по нескольким документам одного типа. Отображение получается такое:
///
///  * носители одного типа  -> один профиль, URL через запятую (наш мультиплекс);
///  * разные типы           -> несколько профилей;
///  * direct рядом с доками -> не отдельный профиль, а прямой канал внутри них
///    (он у нас ровно для прохождения капчи и нужен); direct в одиночку -> свой профиль.
enum ShareImporter {
    struct Imported {
        var profiles: [Profile]
        /// Секреты по id профиля: основной и ключ прямого канала.
        var secrets: [UUID: (main: String, direct: String)]
    }

    static func build(from cfg: ShareConfig) -> Imported {
        let secret = cfg.secret ?? ""
        let directDial = cfg.transports.first { $0.type == "direct" }?.dial ?? ""

        // Группируем по нашему виду транспорта, сохраняя порядок появления.
        var order: [TransportKind] = []
        var byKind: [TransportKind: [String]] = [:]
        for t in cfg.transports where t.type != "direct" {
            guard let k = kind(of: t.type) else { continue }
            let value = (t.url ?? "").trimmingCharacters(in: .whitespaces)
            guard !value.isEmpty else { continue }
            if byKind[k] == nil { order.append(k); byKind[k] = [] }
            byKind[k]?.append(value)
        }

        var out = Imported(profiles: [], secrets: [:])

        for k in order {
            let urls = byKind[k] ?? []
            let p = Profile(name: profileName(cfg, k, multi: order.count > 1),
                            transport: k.rawValue,
                            url: urls.joined(separator: ","),
                            nodeAddr: directDial.isEmpty ? nil : directDial)
            out.profiles.append(p)
            // Для доковых профилей секрет из ссылки относится к прямому каналу,
            // если он там есть: контекст "direct" и ключ у них общий.
            let mainSecret = directDial.isEmpty ? secret : ""
            out.secrets[p.id] = (main: mainSecret, direct: directDial.isEmpty ? "" : secret)
        }

        // Ссылка вообще без доковых носителей — значит это чистый direct.
        if out.profiles.isEmpty, !directDial.isEmpty {
            let p = Profile(name: cfg.name ?? "Прямой TCP",
                            transport: TransportKind.direct.rawValue,
                            url: directDial)
            out.profiles.append(p)
            out.secrets[p.id] = (main: secret, direct: "")
        }
        return out
    }

    /// Записывает профили и их секреты. Секреты идут в Keychain, а не в профиль:
    /// сам профиль лежит в UserDefaults открытым текстом.
    @MainActor
    static func apply(_ imported: Imported, to store: ProfileStore) {
        for p in imported.profiles {
            if let s = imported.secrets[p.id] {
                Secrets.setEncryptionKey(s.main, for: p.id)
                Secrets.setDirectKey(s.direct, for: p.id)
            }
            store.upsert(p)
        }
    }

    private static func kind(of type: String) -> TransportKind? {
        TransportKind(rawValue: type == "vyandex" ? "volga" : type)
    }

    private static func profileName(_ cfg: ShareConfig, _ k: TransportKind, multi: Bool) -> String {
        let base = cfg.name?.trimmingCharacters(in: .whitespaces) ?? ""
        if base.isEmpty { return k.title }
        return multi ? "\(base) — \(k.title)" : base
    }
}

/// Что видит пользователь для каждой причины, по которой ядро не приняло
/// ссылку (share.Code* в ядре). Причину определяет ядро, слова — приложения.
enum ShareLinkMessages {
    static func text(code: String, param: String, detail: String) -> String {
        switch code {
        case "not_link": return "Это не ссылка openflux://"
        case "unsupported_version": return "Неподдерживаемая версия ссылки, обновите OpenFlux"
        case "case_changed": return "Буквы в ссылке поменяли регистр по дороге: скопируйте её ещё раз"
        case "damaged": return "Ссылка повреждена или обрезана: скопируйте её целиком ещё раз"
        case "too_large": return "Ссылка слишком большая"
        case "bad_payload": return "Ссылка повреждена: внутри не настройки OpenFlux"
        case "bad_config": return "Не удалось собрать ссылку из профиля"
        case "no_transports": return "В ссылке нет транспортов"
        case "several_need_session": return "Несколько транспортов требуют режима Session"
        case "session_secret": return "Для режима Session нужен ключ не короче \(param) символов"
        case "short_secret": return "Ключ должен быть не короче \(param) символов"
        case "unknown_codec": return "Неизвестный кодек «\(param)»"
        case "not_shareable": return "\(param == "oneme" ? "MAX" : param) нельзя передать ссылкой: токен привязан к аккаунту"
        case "unknown_transport": return "Неизвестный транспорт «\(param)»: возможно, нужно обновить OpenFlux"
        case "direct_no_dial": return "У direct нет адреса ноды"
        case "direct_needs_session": return "Direct работает только в режиме Session"
        default: return detail.isEmpty ? "Не удалось обработать ссылку" : "Не удалось обработать ссылку: \(detail)"
        }
    }
}

enum ShareLink {
    private struct Result: Decodable {
        let error: String?
        let code: String?
        let param: String?
        let config: ShareConfig?
        let link: String?
    }

    /// Any case: a link whose letters changed case on the way still goes to
    /// the core, which says so.
    static func looksLikeLink(_ s: String) -> Bool {
        s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("openflux://")
    }

    /// Returns the parsed config, or the user's words for why the core
    /// refused it.
    static func decode(_ link: String) -> (config: ShareConfig?, error: String?) {
        let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let c = trimmed.withCString({ OpenFluxShareDecode(UnsafeMutablePointer(mutating: $0)) })
        else { return (nil, "нет ответа от ядра") }
        let json = String(cString: c)
        OpenFluxFreeString(c)
        guard let data = json.data(using: .utf8),
              let r = try? JSONDecoder().decode(Result.self, from: data)
        else { return (nil, "не разобрать ответ ядра") }
        if let e = r.error, !e.isEmpty {
            return (nil, ShareLinkMessages.text(code: r.code ?? "", param: r.param ?? "", detail: e))
        }
        return (r.config, nil)
    }

    /// Builds a link from a profile. The core checks it and names the
    /// encryption context, so the link is the one every client makes and an
    /// invalid combination never leaves the app.
    static func encode(profile p: Profile) -> String? {
        var transports: [[String: Any]] = []
        var cfg: [String: Any] = ["name": p.name]

        let secret = Secrets.encryptionKey(for: p.id) ?? ""
        let directSecret = Secrets.directKey(for: p.id) ?? ""
        let dial = (p.nodeAddr ?? "").trimmingCharacters(in: .whitespaces)

        switch p.transportKind {
        case .max:
            return nil  // токен MAX принадлежит аккаунту узла, делиться им нельзя
        case .direct:
            guard !dialOrURL(p).isEmpty, !secret.isEmpty else { return nil }
            transports.append(["type": "direct", "dial": dialOrURL(p)])
            cfg["negotiate"] = true          // стандарт требует для direct
            cfg["secret"] = secret
        default:
            // ВСЕ документы профиля, а не только первый: мультиплекс у нас
            // выражается списком через запятую, а в стандарте — несколькими
            // носителями одного типа. Раньше терялись все, кроме первого.
            let urls = p.url.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            guard !urls.isEmpty else { return nil }
            // Priorities as the node wizard and the other apps give them:
            // documents first, the direct channel as the backup.
            for u in urls {
                transports.append(["type": standardType(p.transportKind), "url": u, "priority": 100])
            }
            if !secret.isEmpty {
                cfg["secret"] = secret
            }
            // Прямой канал профиля едет вторым носителем — так принимающая
            // сторона получает и способ пройти капчу, а не только документ.
            if !dial.isEmpty, !directSecret.isEmpty, secret.isEmpty || secret == directSecret {
                transports.append(["type": "direct", "dial": dial, "priority": 50])
                cfg["negotiate"] = true
                cfg["secret"] = directSecret
            }
        }

        // Правило стандарта: несколько носителей = согласованная сессия, а она
        // требует секрета от 16 символов. Без него валидная ссылка невозможна —
        // лучше честно ничего не выпустить, чем молча выбросить документы.
        if transports.count > 1 {
            cfg["negotiate"] = true
            if (cfg["secret"] as? String)?.isEmpty ?? true { return nil }
        }
        cfg["transports"] = transports
        guard let data = try? JSONSerialization.data(withJSONObject: cfg),
              let json = String(data: data, encoding: .utf8),
              let c = json.withCString({ OpenFluxShareEncode(UnsafeMutablePointer(mutating: $0)) })
        else { return nil }
        let out = String(cString: c)
        OpenFluxFreeString(c)
        guard let d = out.data(using: .utf8),
              let r = try? JSONDecoder().decode(Result.self, from: d),
              let link = r.link, !link.isEmpty
        else { return nil }
        return link
    }

    private static func dialOrURL(_ p: Profile) -> String {
        let n = (p.nodeAddr ?? "").trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? p.url.trimmingCharacters(in: .whitespaces) : n
    }

    /// Наше имя транспорта → имя в стандарте.
    private static func standardType(_ k: TransportKind) -> String {
        k == .volga ? "vyandex" : k.rawValue
    }
}
