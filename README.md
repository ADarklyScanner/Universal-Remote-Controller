# Universal-Remote-Controller

## Continuous Integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request and on pushes to `main`/`master`, `copilot/**` and `feature/**` branches. It sets up JDK 17, caches Gradle, and runs `./gradlew build`, which compiles all modules, runs the unit/integration tests and the detekt static analysis (configured in `config/detekt.yml`). Test and detekt reports are uploaded as artifacts on failure. The debug APK is uploaded as the `app-debug-apk` artifact on each run (Actions tab -> run -> Artifacts). Android instrumentation tests are not run.

Run the same checks locally:

```sh
./gradlew build :app:assembleDebug   # APK: app/build/outputs/apk/debug/
```

## App connections

The Android app connects over Wi-Fi (TCP), Bluetooth Classic (pair in system settings first), Bluetooth LE (scans for 5 s) and USB. USB supports CDC-ACM devices only (Arduino/ESP32 native USB, STM32, RP2040, most flight controllers); FTDI/CH340/CP210x adapters are not supported yet. After connecting, the app probes read-only, builds a control map and waits for ARM. If the protocol is not recognised the commands are guessed and the app warns you.
