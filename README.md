# Classic Era Launcher — Pocket 6 test fork

Experimental Android launcher for **official World of Warcraft Classic Era** on the **Retroid Pocket 6 (Snapdragon 8 Gen 2 / Adreno 740, 12 GB)**. This is a fork of [DeadmanDog/forever-launcher-on-android](https://github.com/DeadmanDog/forever-launcher-on-android), retaining its GPL-3.0 license and upstream credits.

**Status:** development build; no on-device gameplay validation yet. A successful APK build does not establish that login, rendering, audio or controller input work on a Pocket 6. It uses Blizzard's unmodified Windows ARM64 client and requires your own eligible Battle.net account. It is not affiliated with Blizzard or Retroid. No Blizzard game files or account credentials are included.

## What changed

- Target `wow_classic_era`, folder `_classic_era_`, executable `WowClassic-arm64.exe`.
- Use the selected install region for version checks and CDN selection; Europe is the fallback for a new install.
- Replace the A840-specific TurnRIP driver with standard Mesa Turnip v26.0.0 R8.
- Start with DX11, 720p and a 30 FPS cap. Settings can change these after testing.
- Separate Android app ID `com.bryanengle.classicera.launcher`, label **Classic Era Test**, and default game folder **WoW Classic Era**. The original Forever app can stay installed.
- Remove the beta-to-Forever upgrade flow and beta settings import. Preserve other product rows when updating a shared `.build.info`.
- Bundle unmodified ConsolePort 3.3.13 (Classic Era interface 11509). Install it offline on first Play, unless any ConsolePort folder already exists. The one-time marker lives beside AddOns, so removal stays removed; existing bindings and SavedVariables are untouched. Updates and removal use the Addons page.
- Bound CDN reads to the requested byte range even when a server returns the whole archive. Limit game-data downloads to four workers to reduce Android heap pressure.
- Select Classic addon releases for browsing, install, updates and dependencies. Direct unlabelled ZIP links are rejected; use a GitHub repository link with Classic release metadata, or copy known-compatible addons manually.

Blizzard's EU install manifest was checked on 2026-11-03: Classic Era **1.15.9.70576** includes **WowClassic-arm64.exe**. The launcher checks for newer builds; this version is evidence, not a permanent pin.

## First device test

1. Install the **Classic Era Test** APK. Keep the Forever app if you use it.
2. Open the app and allow access to the game files when Android asks.
3. Choose **Download the game**, select **Europe**, and use the separate **WoW Classic Era** folder on internal storage. Keep sufficient space for the runtime and game data; the app calculates the game download size from Blizzard's manifest.
4. Let runtime setup and the client download finish. Use the game-data card to download assets before judging performance.
5. Confirm DX11, 1280 × 720 and 30 FPS in Settings. Press Play and sign into your own Battle.net account; select **WoW1 (EU)** if prompted.
6. Follow ConsolePort’s in-game controller setup. Test character selection, entering a quiet zone, movement/camera, opening bags and a short combat encounter. Then try a populated area. Note crashes, missing graphics and control problems before raising resolution or the FPS cap.

An authenticator prompt may require switching to the authenticator app. Never share account passwords or authenticator codes in bug reports.

If startup fails, enable **Settings → Advanced → Diagnostic logging**, reproduce once, and export diagnostics. Reports should include the device model, Android version, game build and selected graphics settings; inspect logs before sharing them.

## Build

Use JDK 17 and an Android SDK with platform 36. Native libraries are inherited unchanged from upstream; Gradle does not rebuild them.

```sh
python3 tools/fetch_classic_components.py
python3 tools/verify_bundled_runtime_versions.py
./gradlew :app:assembleModernDebug :app:testModernDebugUnitTest
```

The preparation script extracts runtime assets from the pinned upstream v1.0.0 APK and downloads the standard R8 driver and ConsolePort 3.3.13. The addon ZIP is checked against its pinned SHA-256 at build time and before installation. It checks the APK SHA-256 and every extracted/downloaded runtime asset. Optional `--apk`, `--driver` and `--addon` arguments allow already downloaded files. The game itself downloads from Blizzard at runtime.

The **Classic Era test build** GitHub Actions workflow runs the build and unit tests and uploads an APK only when both succeed. Test APKs use a development signing key. Builds made with different keys cannot update one another.

## Credits and licenses

Original launcher: **DeadmanDog**, based on **jaredgei/wow-forever-android**, **GameNative**, Pluvia and Winlator. Standard Android Turnip package: **K11MCH1/AdrenoToolsDrivers**, based on Mesa (`v26.0.0-rc08`, base commit `e9a83d530cce`). The downloaded driver has not been gameplay-tested by this fork.

ConsolePort: **Sebastian Lindfors / seblindfors**, [version 3.3.13](https://github.com/seblindfors/ConsolePort/releases/tag/3.3.13), distributed unmodified under the Artistic License 2.0. Its original license and notices are preserved inside `ConsolePort/LICENSE.md` in the bundled ZIP and installed addon. Source: [seblindfors/ConsolePort](https://github.com/seblindfors/ConsolePort/tree/3.3.13).

See [UPSTREAM_README.md](UPSTREAM_README.md) for original documentation and full credits, [LICENSE](LICENSE), [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES), and [SOURCES.md](SOURCES.md). Upstream documentation describes Forever and the original A840 configuration, not this test fork. Source for upstream custom runtime packages is available through the upstream maintainer as described in SOURCES.md.
