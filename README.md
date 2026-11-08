<img align="left" width="80" height="80"
src=".github/repo_icon.png" alt="App icon">

# Drift [![Drift CI](https://github.com/seshuthota/drift/actions/workflows/android.yml/badge.svg?event=push)](https://github.com/seshuthota/drift/actions/workflows/android.yml)

**Drift** is a free and open-source keyboard (IME) for Android 8.0+ devices. It aims at being modern,
user-friendly and customizable while fully respecting your privacy.

> [!NOTE]
> Drift is a fork of [FlorisBoard](https://github.com/florisboard/florisboard) by Patrick Goldinger and
> the FlorisBoard contributors, used under the Apache License 2.0. This fork changes the application ID to
> `io.github.seshuthota.drift`, so it installs alongside FlorisBoard and does not share its preferences,
> extensions or data. Upstream's `AI_POLICY.md` does not apply here.

## Installation

There is no store listing for this fork yet. Build and install from source:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Release builds are produced by CI/release tooling under `io.github.seshuthota.drift` (stable track) and
`io.github.seshuthota.drift.beta` (preview track). Once release APKs are published, you can import the
[Obtainium stable config][obtainium_stable] or the [preview config][obtainium_preview]. Because release
signing happens outside Gradle (see `utils/repr_build/`), APKs built locally are debug-signed unless you
provide the `FLSEC_*` signing environment variables.

## Highlighted features

- Integrated clipboard manager / history
- Advanced theming support and customization
- Integrated extension support (still evolving)
- Emoji keyboard / history / suggestions

> [!IMPORTANT]
> Word suggestions/spell checking are not included in the current releases
> and are a major upstream goal for the v0.6 milestone.

Feature roadmap: See [ROADMAP.md](ROADMAP.md)

## Contributing

Want to contribute to Drift? Please see the [contribution guidelines](CONTRIBUTING.md) and the
[code of conduct](CODE_OF_CONDUCT.md) first.

## Addons and permissions

The upstream [Addons Store](https://beta.addons.florisboard.org) and its
[publishing documentation](https://docs.florisboard.org/publishing) are operated by the FlorisBoard
project, not by this fork. Extension format compatibility is inherited from upstream.

The permissions Drift requests are documented upstream in the
[FlorisBoard docs](https://docs.florisboard.org/permissions).

## Used libraries, components and icons

- [AndroidX libraries](https://github.com/androidx/androidx) by [Android Jetpack](https://github.com/androidx)
- [AboutLibraries](https://github.com/mikepenz/AboutLibraries) by [mikepenz](https://github.com/mikepenz)
- [Google Material icons](https://github.com/google/material-design-icons) by [Google](https://github.com/google)
- [JetPref preference library](https://github.com/patrickgold/jetpref) by [patrickgold](https://github.com/patrickgold)
- [KotlinX coroutines library](https://github.com/Kotlin/kotlinx.coroutines) by [Kotlin](https://github.com/Kotlin)
- [KotlinX serialization library](https://github.com/Kotlin/kotlinx.serialization) by [Kotlin](https://github.com/Kotlin)

Many thanks to [Nikolay Anzarov](https://www.behance.net/nikolayanzarov) ([@BloodRaven0](https://github.com/BloodRaven0))
for designing and providing the main app icons inherited by this fork.

## License

Drift is distributed under the Apache License 2.0, the same license as upstream FlorisBoard.

```
Copyright 2020-2026 The FlorisBoard Contributors
Modifications Copyright 2026 The Drift Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

The full license text is in [LICENSE](LICENSE). Upstream source: <https://github.com/florisboard/florisboard>.

<!-- BEGIN SECTION: obtainium_links -->
<!-- auto-generated link templates, do NOT edit by hand -->
<!-- see fastlane/update-readme.sh -->
[obtainium_preview]: https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://app/%7B%22id%22%3A%22io.github.seshuthota.drift.beta%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fseshuthota%2Fdrift%22%2C%22author%22%3A%22drift%22%2C%22name%22%3A%22Drift%20Preview%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22preview%5C%22%7D%22%7D%0A
[obtainium_stable]: https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://app/%7B%22id%22%3A%22io.github.seshuthota.drift%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fseshuthota%2Fdrift%22%2C%22author%22%3A%22drift%22%2C%22name%22%3A%22Drift%20Stable%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22stable%5C%22%7D%22%7D%0A
<!-- END SECTION: obtainium_links -->
