# Zwift controller emulator for Android

This Kotlin Android app emulates a controller for Zwift on macOS or Windows. Stage 1 paired successfully as `Zwift Ride 4242` with version 0.1.1. Stage 2 uses the same advertising and GATT profile. In version 0.2.3, Zwift's tutorial recognized **Click +** and **Click −** using the Click V1 button format. Acceptance requires working shifts in both directions during a ride on the rider's setup.

Android 6.0 (API 23) or newer and a phone with BLE peripheral advertising support are required. On Android 12 and newer, grant **Nearby devices** when you tap **Start Advertising**.

## Build and install

Open this directory in Android Studio. Use JDK 17 for Gradle and install Android SDK Platform 34 and Build Tools 33.0.1. The wrapper pins Gradle 8.0.1. For a command-line build, set `JAVA_HOME` and `ANDROID_HOME` to the JDK and SDK locations if your environment needs them.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On macOS or Linux, run the same Gradle tasks with `sh ./gradlew`. You can also transfer the APK to the phone and open it with Android's package installer.

## Project layout

| File | Purpose |
| --- | --- |
| `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/wrapper/` | Reproducible Android Studio build |
| `app/src/main/AndroidManifest.xml` | App entry point and Bluetooth permissions |
| `MainActivity.kt` | Native Android views, shift controls, permissions and diagnostic log |
| `ClickPeripheral.kt` | Advertising, GATT callbacks, subscriptions and transmission order |
| `ClickProfile.kt` | Discovery advertisement, service definition and RideOn bytes |
| `RideButtons.kt` | Ride gear masks and Protobuf button-state encoding |
| `ClickButtons.kt` | Click V1 plus, minus and release messages for the tutorial test |
| [Protocol research](docs/protocol.md) | Reference code, Android limitations and assumptions |
| [Hardware testing](docs/testing.md) | Install, scan, pair and collect evidence |

This is an independent project licensed under [MIT](LICENSE).
