<p align="center">
  <img src=".github/banner.png" width="100%" alt="Forever Launcher">
</p>

<h1 align="center">Forever Launcher</h1>

<p align="center">
  <a href="https://github.com/DeadmanDog/forever-launcher-on-android/releases/latest"><img src="https://img.shields.io/badge/Download-APK-d9b26a?logo=android&logoColor=white" alt="Download the APK"></a>
  <a href="https://ko-fi.com/foreverlauncher"><img src="https://img.shields.io/badge/Ko--fi-Support%20the%20project-FF5E5B?logo=ko-fi&logoColor=white" alt="Support on Ko-fi"></a>
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="GPL-3.0">
</p>

**An unofficial launcher for World of Warcraft: Forever on Snapdragon 8 Elite / Adreno 8xx Android phones.** Tap the icon, press Play, and Blizzard's own Windows ARM64 client runs natively on the phone: no PC, no streaming, no x86 emulation.

**[📥 Download the latest APK](https://github.com/DeadmanDog/forever-launcher-on-android/releases/latest)** · [Installation](#installation) · [Features](#features) · [Building](#building) · [Credits](#credits) · [☕ Support](#support)

### ✨ Highlights
- 🚀 **Native ARM64:** the Windows ARM64 WoW client on Proton 11.0 ARM64EC (Wine) with ntsync for Android.
- 🎮 **DirectX 11 or 12:** DXVK 3.1.1 or VKD3D-Proton 3.0.1, switchable in Settings.
- 🔥 **Tuned for Adreno 8xx:** the TurnRIP Turnip driver with GPU power modes, wait boost and ADPF.
- 🎯 **Smooth frame pacing:** an app-side limiter locked to the display's refresh, measured frame by frame on the device.
- 🪶 **Ultralight:** one game and one container. Everything WoW does not use is removed: OpenGL, x86 emulation, store backends, unused DirectX and media modules.
- 📲 **No PC needed:** download the whole game in the app at full speed (about 47 GB, written straight into the game's own storage), or upgrade a Classic beta install in place; game updates are in-app too.
- 🧰 **Built in:** performance HUD, a diagnostics recorder, an addon manager, Battle.net sign-in, voice chat repair.

> ⚠️ **Unofficial community project.** Not affiliated with or endorsed by Blizzard Entertainment. World of Warcraft is a trademark of Blizzard Entertainment. You need your own Battle.net account with World of Warcraft: Forever. This repository and its releases contain **no** Blizzard game files.

> 🛡️ **Your account, your responsibility.** Blizzard's terms of use do not cover third-party launchers or running the game on Android. Forever Launcher only starts Blizzard's own, unmodified game client, but you use it at your own risk.

> A modified version of [jaredgei/wow-forever-android](https://github.com/jaredgei/wow-forever-android). See [What this version changes](#what-this-version-changes) and [Credits](#credits).

---

## 📱 Devices

| Device | Status |
| :--- | :--- |
| RedMagic 11 Pro (Snapdragon 8 Elite Gen 5 / Adreno 840) | Tested, the main development device |
| Other Snapdragon 8 Elite / Adreno 8xx phones and handhelds | Expected to work, not tested |

Requirements:
- Snapdragon 8 Elite class SoC with an Adreno 8xx GPU. The bundled TurnRIP driver targets Adreno 8xx.
- Android 10 or newer, 64-bit.
- About 4 GB for the app and its Windows environment, plus room for the game data: about 47 GB for the full game (the same as a Battle.net install on a PC).

---

<a id="installation"></a>

## 📥 Installation

### 1. Install the app
Download `Forever-Launcher-v<version>.apk` from this repository's **Releases** page and install it. On the first start, allow **All files access**: the game folder is outside the app's own storage.

### 2. Get the game data
Pick one:
- **Download in the app (no PC):** press **Download the game**, choose your region (Europe, Americas, Korea, Taiwan) and the folder
  (by default `/storage/emulated/0/World of Warcraft`). The app first downloads the Windows ARM64 client and the file lists, about 250 MB.
  Then the **Game data** card downloads the rest of the game, about 47 GB, at the speed of your connection from Blizzard's CDN, straight
  into the game's own storage: with percent, speed and time left, with the screen off, and it can be stopped and continued. You can also
  play at any time: the download pauses, and while you play the game fetches only what it needs itself, at a lower speed.
- **Upgrade a Classic beta install:** the launcher offers **Upgrade to WoW Forever**. Both use the same `Data/` archives, so only the new
  client downloads and a `wowf` row is added to `.build.info`; your `Config.wtf` settings come across.
- **Copy from a PC:** copy `.build.info` (a hidden file) and the `Data/` folder of your Battle.net install to the phone, for example to
  `/storage/emulated/0/World of Warcraft/`. **Locate Game Files** picks any other folder, on internal storage or an SD card; its name does not
  matter, only that `.build.info` and `Data/` are inside.

### 3. Play
Open **Forever Launcher** and press **Play**. The first start installs the Windows environment, which takes a minute. In the game, Android's **Back** (gesture or button) opens the Quick Menu.

The launcher checks Blizzard's version server on start. When a new build is out, it offers **Update to <version>**, which downloads the new client files and indices and updates `.build.info` in place, without a PC.

---

<a id="features"></a>

## ✨ Features

- **DirectX 11 or DirectX 12:** DXVK 3.1.1 (tuned for Adreno) for DX11, VKD3D-Proton 3.0.1 for DX12, switchable in Settings.
- **Frame pacing:** an app-side frame limiter locked to the display's refresh, FIFO or Mailbox presentation, and a FIFO latency switch. All measured on the device; the best measured setup is the default.
- **Performance HUD:** FPS, 1% and 0.1% lows, frame time, CPU/GPU load, clocks and temperatures, VRAM, RAM, power and battery.
- **Game data download:** the whole game at full speed, written into WoW's own local storage (CASC) the way the game itself does; it
  continues after a stop, skips what is already there, and the downloaded data can be deleted before a copy from a PC.
- **Diagnostics:** a session recorder that saves performance and frame logs, driver and Wine statistics, and shares them as one ZIP.
- **Addon manager:** search and install addons from GitHub and WoWInterface. Only versions made for WoW Forever are offered.
- **Battle.net sign-in:** types your saved login into the game. The login is stored encrypted with the Android Keystore, on the device only.
- **Voice chat:** microphone support, and a repair of the voice helper files from Blizzard's CDN.
- **Display:** render resolution with a Full screen setting (720 pixels high in the phone's own aspect, never stretched), FSR 1 upscaling, and a full-colour game cursor.
- **Controls:** touch controls, a radial menu, controller support and the Android keyboard.
- **Languages:** English, Magyar, Deutsch, Français, Español, Português (BR), Русский, 한국어, 简体中文 and 繁體中文, following the phone or chosen in Settings. The languages other than English and Hungarian are machine translations; corrections are welcome.
- **Theme:** Blue or Red.

## ⚙️ Settings

Settings has four tabs:
- **Quick:** what happens when the app opens, DX11/DX12, FPS limit (30–144), GPU mode, presentation, theme, language.
- **Game:** the region (Europe, Americas, Korea, Taiwan), the game language and the WoW game account (WoW1 or WoW2) for the next launch, where the game is stored, voice chat, and the cache location and clearing.
- **Components:** the GPU driver, Wine/Proton, DXVK and VKD3D-Proton. Picking another one switches to it after a confirmation.
- **Advanced:** display experiments, hardware scaling, and the expert tools: environment variables, a developer shell, diagnostic logging and runtime information.

The **NEXT LAUNCH** card on the home screen shows what the next Play will use.

---

## 🩺 Troubleshooting

| Symptom | Fix |
| :--- | :--- |
| *CAS system was unable to initialize* | `.build.info` is missing or unreadable, or `_forever_/.flavor.info` is missing. Copy `.build.info` again. |
| *No realms available* | The client is out of date. Start the app with internet access and tap **Update**. |
| The game folder is not found | Check that All files access is on for Forever Launcher (Android Settings → Apps → Forever Launcher → Permissions), then pick the folder again with **Locate Game Files**. |
| Returns to the launcher after starting | Check `_forever_/Errors/` in the game folder, and record a session with **Settings → Advanced → Diagnostic logging**. |
| The keyboard doesn't appear | Force-stop Gboard (Android Settings → Apps → Gboard → Force stop) and open the keyboard again. |

---

<a id="building"></a>

## 🛠️ Building from source

Requirements: JDK 17, the Android SDK, and for native code the Android NDK.

```bash
tools/fetch_components.sh <folder>              # runtime packages (not in git), copied from a folder and checked
./gradlew :app:assembleModernRelease            # development build (forever.launcher.beta)
python3 -B tools/prepare_forever_release.py ../forever-launcher-release   # release tree with the release defaults
(cd ../forever-launcher-release && ./gradlew -PwowRelease=true :app:assembleModernRelease)   # forever.launcher
```

- Gradle does not build the native code. After a C/C++ change, run `tools/build_native_libs.sh`.
- The tests: `./gradlew :app:testModernDebugUnitTest`, `tools/native-renderer/tests/run_all.sh` and `python3 -B tools/wowexec-tests/run.py`.
- `tools/verify_bundled_runtime_versions.py` checks that the runtime packages match what the app expects.
- The app icon is generated by `tools/icon/make_forever_icon.py`.
- Release builds are signed with `app/keystores/keystore.properties` if present, otherwise with the debug key. Builds signed with different keys can't update each other.

## 📦 Runtime components and their source

| Component | License | Source |
| :--- | :--- | :--- |
| Proton 11.0 ARM64EC (Wine) | LGPL-2.1+ | source package `proton-11.0-arm64ec-r31-source.tar.xz` |
| ntsync-android (inside Proton) | LGPL-3.0 | [`third_party/ntsync-android`](third_party/ntsync-android) in this repository |
| VKD3D-Proton 3.0.1 | LGPL-2.1+ | source package `vkd3d-proton-arm64-r10-source.tar.xz` |
| TurnRIP 1.0 (Mesa Turnip) | MIT | source package `turnrip-a840-1.1.20-source.tar.xz` |
| DXVK 3.1.1 (Vegas) | zlib | based on VEGAS by isygold (zlib); zlib does not require the source |
| Vulkan wrapper | MIT | [leegao/mesa-wrapper-CI](https://github.com/leegao/mesa-wrapper-CI) `e03c3486fcda`, unmodified (debug info stripped) |
| Exec shim `libwowexec.so` | GPL-3.0 | [`app/src/main/cpp/wowexec`](app/src/main/cpp/wowexec) in this repository |

The source packages are provided on request: open an issue in this repository. VKD3D-Proton is the work of Hans-Kristian Arntzen and contributors. Details are in [`SOURCES.md`](SOURCES.md) and [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES).

---

<a id="what-this-version-changes"></a>

## 🔄 What this version changes

Compared with [jaredgei/wow-forever-android](https://github.com/jaredgei/wow-forever-android) (last upstream commit `aea245b`):

- **Target:** World of Warcraft: Forever (`wowf`) on Adreno 8xx phones.
- **Runtimes:**
  - Proton 11.0: ntsync for Android, CPU identity, request statistics, unused DirectX and media modules removed;
  - VKD3D-Proton 3.0.1 with DX12 support in the app;
  - DXVK 3.1.1 (Vegas) with hybrid pipeline compilation and frame-pacing hooks;
  - TurnRIP 1.0 with GPU power control and ADPF.
- **Display:** a rewritten Vulkan compositor with frame pacing, display-timing measurement, FSR and a colour cursor (X RENDER subset).
- **Game data:** a full in-app download that writes WoW's local storage (CASC: data files, bucket indexes and the free-space file)
  itself at line speed, instead of relying on the game's in-play streaming (about 2 MB/s on a phone).
- **Wine start-up:** an own open exec/W^X shim (`libwowexec.so`) instead of GameNative's closed `libredirect.so`.
- **App:** a new launcher with its own icon and logo, a four-tab Settings, the HUD and diagnostics, the addon manager, voice chat repair, game storage moves with recovery, Blue/Red themes, ten UI languages.
- **Leaner:** OpenGL/EGL, box64/FEX, x86 helpers, the Linux command-line tools of the Windows environment, Microsoft fonts and many inherited GameNative parts removed.
- **Tests:** about 900 JVM tests per build variant, plus native renderer, X server, exec shim and runtime regression suites.

<a id="credits"></a>

## 🙏 Credits

This project packages and extends other people's work:

- **[jaredgei/wow-forever-android](https://github.com/jaredgei/wow-forever-android)** by Jared, the WoW Forever launcher this version started from (GPL-3.0); its 2.3.0 release showed how to install without a PC and upgrade a beta install in place (CDN Servers, portal, Screen Narrator).
- **[GameNative](https://github.com/utkarshdalal/GameNative)** by Utkarsh Dalal and contributors (GPL-3.0). The Android app base, Wine container management and the X server. GameNative builds on **[Pluvia](https://github.com/oxters168/Pluvia)**, **[Winlator](https://github.com/brunodev85/winlator)**, **[Winlator Cmod](https://github.com/coffincolors/winlator)** and the **[Bionic Vulkan wrapper](https://github.com/leegao/bionic-vulkan-wrapper)**.
- **[Bliss-Bass/GameNative-x64](https://github.com/Bliss-Bass/GameNative-x64)** and **Droidtop** (GPL-3.0), whose open exec shims showed the approach of our own `libwowexec.so`.
- **The WoW Forever RP6 community bundle**, which first ran the game in GameNative. Its Proton 11 ARM64EC build from [The412Banner/proton-wine](https://github.com/The412Banner/proton-wine/tree/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2) is the base of our Proton.
- **[joshuatam/ntsync-android](https://github.com/joshuatam/ntsync-android)** by Joshua Tam (LGPL-3.0), the userspace ntsync our Proton uses.
- **[arusiasotto/wow-forever-a840](https://github.com/arusiasotto/wow-forever-a840)** and **[whitebelyash/mesa-unified](https://github.com/whitebelyash/mesa-unified)** for Turnip on Adreno 8xx, the base of TurnRIP.
- **[VEGAS](https://github.com/isygold/Vegas-Private)** by isygold (Israel Agboola), the Adreno-tuned DXVK fork (via GPLAsync, zlib/libpng), the base of our DXVK builds.
- **[WoWForeverywhere](https://github.com/YoruDev-Ryland/WoWForeverywhere)** by YoruDev-Ryland (GPL-3.0), the addon backend of the addon manager.
- **[VKD3D-Proton](https://github.com/HansKristian-Work/vkd3d-proton)**, **[DXVK](https://github.com/doitsujin/dxvk)**, **[Wine](https://www.winehq.org/)**, **[Proton](https://github.com/ValveSoftware/Proton)**, **[Mesa](https://mesa3d.org/)**, **[PulseAudio](https://www.freedesktop.org/wiki/Software/PulseAudio/)**, **[libadrenotools](https://github.com/bylaws/libadrenotools)**, and **AMD FidelityFX Super Resolution 1** (MIT).
- **Blizzard Entertainment** for World of Warcraft and its Windows ARM64 client. The app icon, logo and splash are our own artwork made for this project; no Blizzard artwork is used. The design tools in `tools/icon` use the Cinzel typeface (SIL OFL 1.1).

<a id="support"></a>

## ☕ Support

Forever Launcher is free and open source, made in spare time. If it brings WoW Forever to your phone and you would like to say thanks,
you can support the project on **[Ko-fi](https://ko-fi.com/foreverlauncher)**. Thank you! 💛

<a href="https://ko-fi.com/foreverlauncher"><img src="https://img.shields.io/badge/Ko--fi-Buy%20me%20a%20coffee-FF5E5B?logo=ko-fi&logoColor=white&style=for-the-badge" alt="Support on Ko-fi"></a>

## 📄 License

GPL-3.0, the same as GameNative and jaredgei/wow-forever-android. See [`LICENSE`](LICENSE). The bundled runtime components carry their own licenses (LGPL, MIT, zlib and others); see [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES) and [`SOURCES.md`](SOURCES.md).
