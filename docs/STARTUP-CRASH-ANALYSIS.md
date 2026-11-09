# AnimeGo startup crash analysis

## Baseline

This analysis uses the user-provided `AnimeGoNative.zip` as the source baseline. The archive contains no Logcat output, tombstone, or device crash report, so the exact observed exception cannot be confirmed from evidence. Two deterministic startup defects were found by tracing the Android launch path.

## Finding 1 — WorkManager was used before initialization (critical)

`AndroidManifest.xml` removes `androidx.work.WorkManagerInitializer` from AndroidX Startup metadata. `AnimeGoApplication` implements `Configuration.Provider`, but the previous `onCreate()` did not call `WorkManager.initialize()`. It then called `CloudSyncScheduler.schedulePeriodic()`, which immediately calls `WorkManager.getInstance(context)`. With the initializer intentionally removed and no explicit initialization, WorkManager can throw its not-initialized `IllegalStateException` during `Application.onCreate()`. An exception here terminates the process before `MainActivity` is displayed.

Fix: explicitly call `WorkManager.initialize(this, workManagerConfiguration)` as the first application-specific initialization in `AnimeGoApplication.onCreate()`, before constructing the graph or scheduling work.

## Finding 2 — AppCompatActivity used a non-AppCompat theme (critical)

`MainActivity` extends `androidx.appcompat.app.AppCompatActivity`, while the manifest theme previously inherited directly from `android:style/Theme.Material.Light.NoActionBar`. AppCompatActivity requires an AppCompat theme. This mismatch can throw `IllegalStateException` during Activity creation even when the APK compiles successfully.

Fix: make `AppTheme` inherit from `Theme.AppCompat.DayNight.NoActionBar` provided by the declared AppCompat dependency.

## Validation limits

The source archive documents Android Gradle Plugin 9.1.0, Gradle 9.3.1, JDK 17, and Android SDK Platform 37. This environment has Java but no installed Gradle distribution or Android SDK, and outbound network access is unavailable. Therefore `assembleDebug`, merged-manifest inspection, and device launch could not be executed here. GitHub Actions must still run the real build, and the resulting APK must be launched on the affected device to confirm the original crash is resolved.
