import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Kotlin/Native exports `initKoin()` as `doInitKoin()`: Objective-C reserves the
        // `init` prefix for initializers.
        KoinInitKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
