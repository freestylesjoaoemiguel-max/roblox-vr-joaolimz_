import SwiftUI
import AppKit
import CryptoKit

/// Credit line, stored encoded + hashed: the Patch button stays disabled if it was altered.
enum Credits {
    static let e: [UInt8] = [57,19,171,198,18,20,240,228,225,185,154,135,196,218,221,172,166,184,181,165,185,205,217,219,113,104,125,101,127,81]
    static let h = "1b8293f0fe3ffe07ab3fcbc439c38c86e1e42269bbe97bce7d95b994b23deb5a"
    static var text: String { String(decoding: e.enumerated().map { UInt8(truncatingIfNeeded: Int($0.element) ^ (0x5A + $0.offset * 7)) }, as: UTF8.self) }
    static var intact: Bool { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() == h }
}

let kitDir = Bundle.main.resourcePath.map { $0 + "/vrkit" } ?? (CommandLine.arguments[0] + "/../../../vrkit")
let exts = ["apk", "apkm", "xapk", "apks", "zip"]

struct ContentView: View {
    @State var input: URL?
    @State var output: String?
    @State var log = "Перетащи сюда Roblox (apk / apkm / xapk / apks / zip или папку со split-APK)."
    @State var step = 0
    @State var busy = false
    @State var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14).strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [8]))
                    .foregroundColor(hover ? .accentColor : .secondary)
                VStack(spacing: 6) {
                    Text(input?.lastPathComponent ?? "Перетащи файл Roblox сюда").font(.headline)
                    Button("Выбрать файл…") { choose() }.disabled(busy)
                }.padding()
            }.frame(height: 110)
            HStack {
                Button("Сделать VR-версию") { patch() }.disabled(input == nil || busy || !Credits.intact).keyboardShortcut(.defaultAction)
                Button("Установить на телефон (adb)") { install() }.disabled(output == nil || busy)
                Button("Показать в Finder") { if let o = output { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: o)]) } }.disabled(output == nil)
                Spacer()
            }
            if busy || step > 0 { ProgressView(value: Double(step), total: 9) }
            ScrollView { Text(log).font(.system(.caption, design: .monospaced)).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }
                .padding(8).background(Color.black.opacity(0.08)).cornerRadius(8)
        }
        .padding().frame(minWidth: 620, minHeight: 440)
        .overlay(alignment: .topTrailing) {
            Text(Credits.intact ? Credits.text : "créditos alterados").font(.caption.bold()).foregroundColor(.secondary).padding(8).allowsHitTesting(false)
        }
        .onDrop(of: [.fileURL], isTargeted: $hover) { items in
            items.first?.loadItem(forTypeIdentifier: "public.file-url") { d, _ in
                guard let d = d as? Data, let u = URL(dataRepresentation: d, relativeTo: nil) else { return }
                var isDir: ObjCBool = false
                FileManager.default.fileExists(atPath: u.path, isDirectory: &isDir)
                if isDir.boolValue || exts.contains(u.pathExtension.lowercased()) {
                    DispatchQueue.main.async { input = u; output = nil; step = 0; log = "Выбрано: \(u.lastPathComponent)" }
                } else {
                    DispatchQueue.main.async { log = "Не тот формат: \(u.lastPathComponent)" }
                }
            }
            return true
        }
    }

    func choose() {
        let p = NSOpenPanel()
        p.canChooseDirectories = true
        if p.runModal() == .OK { input = p.url; output = nil; step = 0; log = "Выбрано: \(p.url!.lastPathComponent)" }
    }

    func patch() {
        guard let i = input else { return }
        let base = i.deletingPathExtension().path
        let out = (i.pathExtension.isEmpty ? i.path : base) + "-vr.apk"
        exec(["java", "-jar", kitDir + "/RobloxVRPatcher.jar", "--kit", kitDir, i.path, out]) { ok in if ok { output = out } }
    }

    func install() {
        guard let o = output else { return }
        exec(["adb", "install", "-r", "-d", o]) { _ in }
    }

    func exec(_ args: [String], done: @escaping (Bool) -> Void) {
        busy = true; log = ""; step = 0
        DispatchQueue.global().async {
            let t = Process(); t.executableURL = URL(fileURLWithPath: "/bin/zsh")
            t.arguments = ["-lc", "export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH; " + args.map { "'\($0)'" }.joined(separator: " ")]
            let pipe = Pipe(); t.standardOutput = pipe; t.standardError = pipe
            pipe.fileHandleForReading.readabilityHandler = { h in
                let s = String(decoding: h.availableData, as: UTF8.self)
                if s.isEmpty { return }
                DispatchQueue.main.async {
                    log += s
                    if let r = s.range(of: #"\[(\d)/9\]"#, options: .regularExpression) { step = Int(s[r].dropFirst().prefix(1)) ?? step }
                }
            }
            try? t.run(); t.waitUntilExit()
            pipe.fileHandleForReading.readabilityHandler = nil
            DispatchQueue.main.async {
                busy = false
                let ok = t.terminationStatus == 0
                if ok { step = 9 }
                log += ok ? "\n✓ Готово" : "\n✗ Ошибка (код \(t.terminationStatus))"
                done(ok)
            }
        }
    }
}

struct PatcherApp: App {
    var body: some Scene {
        WindowGroup("Roblox VR Patcher") { ContentView().onAppear { NSApp.activate(ignoringOtherApps: true) } }
    }
}
NSApplication.shared.setActivationPolicy(.regular)
PatcherApp.main()
