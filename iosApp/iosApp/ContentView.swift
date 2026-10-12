import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        // Every Flare* Info.plist key, filled in by the scheme's xcconfig. The venue's
        // MainViewController reads the ones it needs and applies its own defaults.
        let infoPlist = (Bundle.main.infoDictionary ?? [:]).reduce(into: [String: String]()) { keys, entry in
            if entry.key.hasPrefix("Flare"), let value = entry.value as? String {
                keys[entry.key] = value
            }
        }
        return MainViewControllerKt.MainViewController(infoPlist: infoPlist)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
