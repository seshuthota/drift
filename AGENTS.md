# Repository Guidelines

## Project Overview

Drift — a free, open-source Android keyboard (IME) for Android 8.0+ (`minSdk 26`), currently
`0.6.0-alpha02` (`projectVersionCode=119`). Kotlin + Jetpack Compose UI, with a small Rust native library.
Package `io.github.seshuthota.drift`; build types produce `io.github.seshuthota.drift.debug`,
`.beta`, and the release id.

> **Fork notice.** This repository is a downstream fork of FlorisBoard (Apache-2.0). Application ID and
> Kotlin namespaces are now `io.github.seshuthota.drift` (was `dev.patrickgold.florisboard`), the library
> namespaces are `io.github.seshuthota.drift.lib*` (was `org.florisboard.lib*`), and the native library is
> `drift_native` (was `fl_native`). Upstream's `AI_POLICY.md` has been removed in this fork; AI-assisted
> changes are permitted here. Apache-2.0 still requires keeping the upstream `LICENSE`, copyright notices,
> and attribution — do not strip license headers or `Copyright (C) … The FlorisBoard Contributors` lines.

### Rebranding map (applied repo-wide)

| Upstream | Drift |
| --- | --- |
| `dev.patrickgold.florisboard` | `io.github.seshuthota.drift` |
| `org.florisboard.lib.*` | `io.github.seshuthota.drift.lib.*` |
| `org.florisboard.libnative` | `io.github.seshuthota.drift.libnative` |
| `org.florisboard.<component>` extension IDs | `io.github.seshuthota.drift.<component>` |
| `fl_native` / `libfl_native_rust.a` | `drift_native` / `libdrift_native_rust.a` |
| `Floris*` classes/files, `floris_*` resources and themes | `Drift*`, `drift_*` |
| `florisboard` URI scheme, `vnd.florisboard.…` MIME | `drift`, `vnd.drift.…` |
| preference store `florisboard-app-prefs` | `drift-app-prefs` |

Deliberately **not** renamed: the Snygg JSON schema namespace
`https://schemas.florisboard.org/snygg/v2/stylesheet` (data-format contract checked in `SnyggStylesheet`),
the upstream addons store host `beta.addons.florisboard.org`, the `Flog` logging API name, translated
strings under `values-*/`, and all `Copyright (C) … The FlorisBoard Contributors` notices. Renaming any of
these needs a coordinated change (and, for the schema URL, a format version bump).

## Architecture & Data Flow

One app module plus five libraries, assembled by `settings.gradle.kts` (typesafe project accessors on):

| Module | Package | Role |
| --- | --- | --- |
| `:app` | `io.github.seshuthota.drift` | IME service, keyboard UI, settings UI, extensions, clipboard, dictionary |
| `:lib:kotlin` | `io.github.seshuthota.drift.lib.kotlin` | JVM-only helpers: `Result.kt`, `Flow.kt`, `Strings.kt`, `FsFile.kt`, `GuardedByLock` |
| `:lib:android` | `io.github.seshuthota.drift.lib.android` | Android wrappers (settings, clipboard, versioning) |
| `:lib:compose` | `io.github.seshuthota.drift.lib.compose` | Compose primitives, `ProvideLocalizedResources`/`stringRes`, modifiers |
| `:lib:snygg` | `io.github.seshuthota.drift.lib.snygg` | KMP theming DSL/stylesheet engine used by the IME |
| `:lib:native` | `io.github.seshuthota.drift.libnative` | JNI stub + Rust static lib built via CMake → `drift_native.so` |

`:benchmark` is commented out of the build and stale — do not re-enable without fixing its plugin aliases.

Layout of `app/src/main/kotlin/io/github/seshuthota/drift/`:

- `ime/keyboard3/` — K3 input pipeline (`ImeController`, `ImeState`, `ImeEditor`, `ImeActions`), key UI in `ime/keyboard3/ui/`
- `ime/window/` — floating/split keyboard window, `ImeRootView` provides the composition locals
- `ime/theme/` — Snygg `ThemeController`; `ime/smartbar/` suggestion bar; `ime/nlp/`, `ime/dictionary/`
- `ime/clipboard/` (Room), `ime/extension/` (`.flex` bundles), `ime/io/` (okio `Storage` + `DriftRef`),
  `ime/media/{emoji,emoticon}`, `ime/text/key/` (`KeyCode`, `KeyVariation`), `ime/core/` (subtypes)
- `app/` — settings Compose UI and the `jetpref` preference models; `lib/` — crashutility, devtools, util

**Key event → text.** The main keyboard renders `ImeKeyboardKeyBox` (visual only) and funnels every
pointer through `PointerTracker`, which hit-tests the `TouchKeyboard` and owns tap, repeat, long-press
popups, peek layers and multi-tap before emitting via `imeController.updateState { emit(output) }`.
`ImeKeyButton` (which has its own `awaitEachGesture`) is only used by panels: clipboard, media, emoji
search and quick actions. `ImeController` extends k3lp's `K3InputMethod` and overrides
`emitText`/`emitBackspace`/`emitDescriptor`; `ImeEditor` implements `K3Editor` over
`WeakReference<InputConnection>` and performs all `commitText`/`setComposingRegion`/`setSelection` writes.

**Gesture recognition.** `ime/keyboard3/gesture/` is pure Kotlin (no Android or Compose imports):
`GestureRecognizer` consumes ordered samples and returns `Tap`, `Swipe` (eight-way direction plus
quarter-threshold unit counts) or `Glide` (decimated path), using thresholds from `GestureConfig`.
`PointerTracker` feeds it from `handleDown`/`handleMove`/`handleUp` and reports through
`GestureListener`; recognition only runs when a listener is supplied, so keys behave normally without
one. Precedence is popup > peek > glide > swipe > tap, and glide is suppressed on space, shift and the
delete family. Pointer events reach the tracker through an ordered channel in `trackPointerInput`, so
samples cannot be reordered by the scheduler. Thresholds are derived from `prefs.gestures.*` and
`prefs.glide.enabled` in `rememberPointerTracker`.

**Startup.** `DriftApplication.init` loads `drift_native` in `try/catch`, installs `Flog` + `CrashUtility`,
creates storage/extension/IME/theme controllers, then loads preferences after `ACTION_USER_UNLOCKED` when
the device is locked.

**State & persistence.** No Hilt/Koin/Dagger: application-scoped `lateinit` controllers plus `lazy {}`
managers, retrieved via `inferDriftApplication()`; Compose DI is `staticCompositionLocalOf`
(`LocalImeController`, `LocalInteractionController`, `LocalStorageController`, `LocalThemeController`,
`LocalWindowController`). IME state is immutable `ImeState` mutated through K3 `updateState {}`. Settings use
`jetpref` (`DriftPreferenceStore = jetprefDataStoreOf(DriftPreferenceModel::class)`), observed with
`.asFlow()` / `collectAsState`. Clipboard and user dictionary use Room (`app/schemas/` is committed).

**Assets.** Keyboard layouts, themes, extensions and `ime/dict/data.json` live under
`app/src/main/assets/ime/`; extensions are loadable from bundled assets and `files/extensions`.

## Key Directories

```
app/src/main/kotlin/...    production code (see map above)
app/src/main/assets/ime/   layouts, themes, emoji, dictionary, bundled extensions
app/src/main/res/          strings (values/strings.xml = Crowdin source only), xml/ configs
app/src/test|androidTest/  JVM and instrumented tests
lib/*/src/                 library sources; lib/snygg/src/commonTest for KMP tests
gradle/                    version catalogs (libs.versions.toml, tools.versions.toml) + wrapper
utils/                     data generators; utils/repr_build = Dockerized reproducible release builds
fastlane/                  store metadata, screenshots, changelogs, Obtainium manifests
```

## Development Commands

```bash
./gradlew clean assembleDebug            # what CI builds (.github/workflows/android.yml)
./gradlew :app:assembleBeta              # minified beta variant (.beta suffix)
./gradlew :app:testDebugUnitTest         # JVM unit tests for :app
./gradlew :lib:kotlin:test               # JVM tests for a JVM library
./gradlew :lib:snygg:allTests            # KMP common tests
./gradlew test                           # all unit tests
./gradlew :app:connectedDebugAndroidTest # instrumented (needs device/emulator)
./gradlew :app:lintDebug                 # Android Lint (config: app/lint.xml)
./gradlew :lib:snygg:generateJsonSchema  # writes lib/snygg/schemas/stylesheet.schema.json
python3 utils/update_codes.py FILE       # patch key codes in a layout JSON (rewrites file)
cd utils && python3 convert_fcitx5_sqlite.py   # fcitx5 tables -> han.sqlite3 + extension-draft.json
./utils/repr_build/run.sh assemble stable      # signed reproducible build (docker + FLSEC_* env)
```

Prerequisites (`CONTRIBUTING.md`): Linux/WSL2, ~16 GB RAM, JDK 17, Android SDK + NDK, CMake ≥ 3.22,
Clang ≥ 15, Rust (rustup). IntelliJ/Android Studio must enable "Future AGP Versions" for AGP 9.
First native build needs network: the CMake script runs `rustup target add` for every ABI. `assembleDebug`
additionally requires the exact CMake/NDK versions from `gradle/tools.versions.toml` (4.1.2 /
29.0.14206865) installed in the SDK; `compileDebugKotlin` and the unit tests do not.

## Code Conventions & Common Patterns

**Formatting.** `.editorconfig`: UTF-8, LF, 4-space indent, max 120 columns, final newline, no trailing
whitespace (2 spaces for `*.json`, `*yml`, `*.sh`). `kotlin.code.style=official`. No ktlint/detekt/Spotless
in the repo — style is review-enforced.

**Headers.** Every source file starts with the Apache-2.0 block and
`Copyright (C) <years> The FlorisBoard Contributors`, from `.idea/copyright/copyright.xml`.

**Naming.** `Drift`-prefixed app types (`DriftApplication`, `DriftImeService`, `DriftAppActivity`,
`DriftSpellCheckerService`); `*Controller` for long-lived controllers, `*Manager` for lazy managers,
`*EditorInstance`/`ImeEditor` for text mutation. Strings use `area__key`, `pref__group__title`,
`...__summary`, `...__label`, with `comment="…"` hints for translators; non-translatable strings go to
`values/strings_dont_translate.xml`.

**Errors.** Prefer `kotlin.Result` helpers from `lib/kotlin` (`resultOk`, `resultErr`, `resultErrStr`,
`runCatchingAsync`, `tryOrNull`); stage unexpected failures with `CrashUtility.stageException`.

**Logging.** `Flog` with `flogDebug/flogInfo/flogWarning/flogError { "…" }` lambdas and a `LogTopic`.

**Async.** Explicit `CoroutineScope(Dispatchers.Default + SupervisorJob())` for app/IME scopes,
`lifecycleScope` in services/activities; use `collectIn`/`collectLatestIn` helpers. Expose `StateFlow`,
never mutable state across threads without `GuardedByLock`.

**UI.** IME surface uses Snygg (`SnyggBox`, selectors, attributes), settings use Material3. Strings in
Compose come from `ProvideLocalizedResources` + `stringRes(...)` with `curlyFormat` placeholders, not
`stringResource`. Compose compiler flags include `-Xwhen-guards`, `-Xcontext-parameters`,
`-Xexplicit-backing-fields`, `-XXLanguage:+LocalTypeAliases` — code depends on them.

**JNI.** `lib/native/src/main/rust/src/lib.rs` exports `Java_io_github_seshuthota_drift_libnative_TestKt_dummyAdd`; the
Kotlin declaration must stay in the file named `test.kt`. `lib/native/src/lib.c` is a required stub.

## Important Files

- `gradle.properties` — SDK levels and `projectVersionCode`/`projectVersionName` (single source of truth)
- `gradle/libs.versions.toml`, `gradle/tools.versions.toml` — dependency and toolchain versions
- `app/build.gradle.kts` — variants, KSP/Room schema args, lint, test logging, kover
- `app/src/main/AndroidManifest.xml` — services, activities, providers, deep links
- `app/src/main/kotlin/.../DriftApplication.kt`, `DriftImeService.kt` — process and IME entry points
- `app/src/main/kotlin/.../ime/keyboard3/ImeController.kt`, `ImeEditor.kt` — input pipeline core
- `app/src/main/kotlin/.../app/AppPrefs.kt` — preference store definition
- `lib/native/src/main/rust/{CMakeLists.txt,Cargo.toml}` — native build wiring
- `.github/workflows/android.yml` — the only build gate (no tests, no lint)
- `CONTRIBUTING.md` — contribution constraints (upstream AI policy no longer applies)
- `app/proguard-rules.pro` — only `-dontobfuscate` + kotlinx.serialization keeps

## Runtime/Tooling Preferences

- Gradle wrapper **9.7.1**; AGP **9.2.1**; Kotlin **2.4.20**; KSP 2.3.11;
  Compose Multiplatform 1.12.1; Java **17** toolchain, but `jvmTarget = JVM_11`; buildTools 36.0.0,
  NDK 29.0.14206865, CMake 4.1.2, Rust **1.93.0**.
- AGP's built-in Kotlin support is used — do not add `org.jetbrains.kotlin.android`.
- `compileSdk`/`targetSdk` are 37 (preview platform); `TYPESAFE_PROJECT_ACCESSORS` and
  `NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS` are enabled.
- Release signing is external: there is no `signingConfigs`; `utils/repr_build/scripts/assemble.sh` signs
  with `apksigner`/`jarsigner` using `FLSEC_SIGNING_ENABLED`, `FLSEC_KEYFILE`, `FLSEC_KEYALIAS`,
  `FLSEC_KS_PASS`, `FLSEC_KEY_PASS`.
- `bundle { language { enableSplit = false } }` — keep it off.

## Testing & QA

- Frameworks: Kotest 6 (`FunSpec` + `kotest-property` `checkAll`/`Arb`), `kotlin-test-junit5`,
  `kotlinx-coroutines-test`, Turbine, JUnit 5 platform (`useJUnitPlatform()`). Instrumented tests use
  AndroidX `test.ext:junit` + Espresso with `AndroidJUnitRunner`.
- Locations: `app/src/test/kotlin/...` (window constraints/controller, quick actions, extensions),
  `ime/keyboard3/gesture/GestureRecognizerTest.kt` (tap/swipe/glide classification and direction
  invariants), `lib/kotlin/src/test/...` (`StringsTest`, `MimeTypeFilterTest`),
  `lib/snygg/src/commonTest/...` (stylesheet and value parsing). `app/src/androidTest` is configured but
  thin; `lib/android`, `lib/compose`, `lib/native` have no tests.
- Style: class-per-spec extending `FunSpec`, backtick/plain sentence test names, `context("…")` grouping,
  shared `Helpers.kt`/`Arbitraries.kt`/`Matchers.kt` fixtures, `assertSoftly` for multi-assertion property
  checks.
- Coverage: Kover with `useJacoco()` in `:app`, `:lib:kotlin`, `:lib:snygg`. No verification thresholds
  are configured, so a green build does **not** imply coverage.
- CI only runs `./gradlew clean assembleDebug` (which does compile the native module). Unit tests and lint
  are not run by CI — run the relevant test/lint task locally before claiming a change works.

## Gotchas

- **Never** edit `app/src/main/res/values-*/strings.xml`. `validate-strings-no-translations.yml` fails any
  PR touching those paths; translations come from Crowdin only (`crowdin.yml`, source
  `values/strings.xml`).
- Do not hand-edit generated artifacts: `app/src/main/res/xml/spellchecker.xml` (from
  `utils/generate_spellcheck_config.py`), the Obtainium block in `README.md` (from
  `fastlane/update-readme.sh`), or `lib/snygg/schemas/stylesheet.schema.json` (compiled task output).
- `app/lint.xml` is hand-written lint config passed as `baseline` — it is not a real baseline; new issues
  still surface.
- `:lib:android` disables five lint ids as a workaround for `:lib:snygg:generateJsonSchema`; do not
  "fix" those by re-enabling blind.
- `:benchmark` is excluded and unbuildable as written; `:lib:native` builds all ABIs (`abiFilters`
  commented out).
