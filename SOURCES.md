# Sources of the bundled runtime components

## Classic Era fork (2026-11-03)

This fork uses the unmodified runtime assets extracted from DeadmanDog's v1.0.0 APK,
except that the A840 TurnRIP package is replaced with the standard
[`Turnip_v26.0.0_R8.zip`](https://github.com/K11MCH1/AdrenoToolsDrivers/releases/tag/v26.0.0-rc08)
from K11MCH1. That release identifies Mesa base commit `e9a83d530cce` and its applied
patches. Mesa's licenses and notices apply; the package is unmodified.
Use `python3 tools/fetch_classic_components.py` for this fork's pinned assets.

The remaining sections below are the original upstream source inventory. “Our changes”
refers to DeadmanDog's work, and requests for its custom runtime source archives go to
[DeadmanDog/forever-launcher-on-android](https://github.com/DeadmanDog/forever-launcher-on-android),
not this fork. The TurnRIP section documents the original driver, which this fork does not bundle.

The app itself is fully in this repository (GPL-3.0). The runtime packages it bundles are large binaries, so they are not in git:
`tools/fetch_components.sh <folder>` copies them from a folder and checks them against `tools/components.sha256`.

For every component that we build or modify, the corresponding source is kept as a `*-source.tar.xz` package and is provided
on request: open an issue in this repository. Each source package has a `SOURCE-README.md` with the upstream
base, our changes and the build steps. `tools/package_runtime_sources.py` makes these packages from the work trees.

## Proton 11 ARM64EC r31
- **License:** LGPL-2.1-or-later (Wine); `ntsync-android` LGPL-3.0.
- **Base:** the community Proton 11 ARM64EC build of the WoW Forever RP6 bundle, from
  [The412Banner/proton-wine@e5fa703](https://github.com/The412Banner/proton-wine/tree/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2)
  plus an ARM64 copied-syscall fix and an NLS fallback allocation patch, built for Android (bionic).
- **Our changes:**
  - Unix side (`ntdll.so`, `wineserver`) rebuilt with:
    - CPU identity variables;
    - an ntsync fast path backed by `ntsync-android` (a userspace ntsync for Android);
    - wineserver request statistics.
  - `mf.dll` rebuilt without the `evr` delay import.
  - Windows modules that WoW never loads removed, with their registry entries:
    - OpenGL and media chains;
    - Direct3D 9/10, DirectWrite, XAudio 2.8/2.9, DXVA2 and more.
- **Source:** `proton-11.0-arm64ec-r31-source.tar.xz`. The other Windows modules are the unmodified binaries of the base build.
- **ntsync-android:** our modified copy of [joshuatam/ntsync-android](https://github.com/joshuatam/ntsync-android)
  (LGPL-3.0-only) is in this repository, [`third_party/ntsync-android`](third_party/ntsync-android/MODIFIED-BY-WOW-FOREVER.md).

## VKD3D-Proton r10
- **License:** LGPL-2.1-or-later.
- **Base:** [HansKristian-Work/vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton) 3.0.1, built for Windows ARM64.
- **Our changes (r3–r10):**
  - pipeline fallback compiles;
  - fence, query and out-of-memory fixes;
  - disk-cache recovery;
  - the upstream WoW MSAA workaround extended to the ARM64 client names;
  - five upstream fixes ported (Turnip queue waits, fence order, depth/stencil barriers, copy regions, a sparse leak).
- **Source:** `vkd3d-proton-arm64-r10-source.tar.xz`.

## TurnRIP 1.1.20
- **License:** MIT (Mesa).
- **Base:** Mesa Turnip `turnip/gen8` for Adreno 8xx, as built by [arusiasotto](https://github.com/arusiasotto/wow-forever-a840)
  from [whitebelyash/mesa-unified](https://github.com/whitebelyash/mesa-unified).
- **Our changes:**
  - KGSL GPU power modes and frequency floor;
  - adaptive wait boost and an ADPF session;
  - frame statistics;
  - A8xx community fixes and later bug fixes.
- **Source:** `turnrip-a840-1.1.20-source.tar.xz`.

## DXVK Vegas r63 (DXVK 3.1.1)
- **License:** zlib/libpng (DXVK).
- **Base:** [VEGAS](https://github.com/isygold/Vegas-Private) by isygold, an Adreno-tuned DXVK fork (via GPLAsync) of
  [doitsujin/dxvk](https://github.com/doitsujin/dxvk).
- **Our changes:**
  - hybrid GPL/async pipeline work;
  - a GPU identity persona and a VRAM cap;
  - pacing hooks and fixes;
  - for r63, the same layer ported onto DXVK 3.1.1.
- **Source:** not published; the zlib license does not require it. VEGAS itself is released under zlib/libpng by its author
  (LICENSE: "Copyright (c) 2026 Israel Agboola"), so its use and redistribution need no separate permission; our builds are
  altered versions and are marked as such (Vegas r63, "wow-arm64-hybrid-gamenative").

## Vulkan wrapper
- **License:** MIT.
- [leegao/mesa-wrapper-CI](https://github.com/leegao/mesa-wrapper-CI) commit `e03c3486fcda`, unmodified apart from removed
  debug information.

## Other assets
- `pulseaudio-gamenative-20260919.tzst` and the PulseAudio libraries in `jniLibs` are GameNative's builds, used unmodified.
- `imagefs_bionic.txz` is GameNative's bionic image, slimmed by us (without command-line
  programs; what is left is listed in `THIRD_PARTY_NOTICES`). `container_pattern_common.txz` is only an empty folder skeleton (no
  Microsoft fonts, no Winlator helpers).
- `libwowexec.so`, the exec/W^X shim of the Wine processes, is built from `app/src/main/cpp/wowexec/wow_exec.c` in this
  repository (GPL-3.0). GameNative's closed `libredirect.so` is not shipped.
- The DX12 driver probe `wow-driver-probe.exe` is built from `tools/diagnostics/wow-driver-probe.c` in this repository.
