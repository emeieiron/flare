import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        let workerBaseUrl = Bundle.main.object(forInfoDictionaryKey: "FlareWorkerURL") as? String
            ?? "http://127.0.0.1:8787"
        return MainViewControllerKt.MainViewController(workerBaseUrl: workerBaseUrl)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
