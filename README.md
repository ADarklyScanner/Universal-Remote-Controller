# Universal-Remote-Controller

## Continuous Integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every pull request and on pushes to `main`/`master`, `copilot/**` and `feature/**` branches. It sets up JDK 17, caches Gradle, and runs `./gradlew build`, which compiles all modules, runs the unit/integration tests and the detekt static analysis (configured in `config/detekt.yml`). Test and detekt reports are uploaded as artifacts on failure. The debug APK is uploaded as the `app-debug-apk` artifact on each run (Actions tab -> run -> Artifacts). Android instrumentation tests are not run.

Run the same checks locally:

```sh
./gradlew build :app:assembleDebug   # APK: app/build/outputs/apk/debug/
```
