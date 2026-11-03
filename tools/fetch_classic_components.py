#!/usr/bin/env python3
"""Recover pinned runtime assets from upstream's APK and add the A740 Turnip driver.

Also bundles the unmodified Classic-compatible ConsolePort release.
No Blizzard files are downloaded or bundled. Existing files must match their digest.
"""
import argparse
import hashlib
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APK_URL = 'https://github.com/DeadmanDog/forever-launcher-on-android/releases/download/v1.0.0/Forever-Launcher-v1.0.0.apk'
APK_SHA = '9d96b848f5f9fdc90fc5fb9753ac8a72e2c8c83596145f9232f37816f721ca7a'
DRIVER_URL = 'https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v26.0.0-rc08/Turnip_v26.0.0_R8.zip'
DRIVER_SHA = 'e634db0f929e2205e95511c769071817d0390180ec72c8e690bc76375e813715'
DRIVER = 'bundled_components/Turnip_v26.0.0_R8.zip'
ADDON_URL = 'https://github.com/seblindfors/ConsolePort/releases/download/3.3.13/ConsolePort-3.3.13.zip'
ADDON_SHA = '548994a59cc548f53c26e6530137049129141f138c98d699f78fabe9f07037d2'
ADDON = 'bundled_addons/ConsolePort-3.3.13.zip'

def verified(data, digest, name):
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'SHA-256 mismatch: {name}')
    return data

def retrieve(url, local):
    if local:
        return Path(local).read_bytes()
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', help='Use an already downloaded upstream v1.0.0 APK')
    parser.add_argument('--driver', help='Use an already downloaded standard R8 driver ZIP')
    parser.add_argument('--addon', help='Use an already downloaded ConsolePort 3.3.13 ZIP')
    args = parser.parse_args()
    assets = ROOT / 'app/src/main/assets'
    entries = [line.split() for line in (ROOT / 'tools/components.sha256').read_text().splitlines() if line.strip()]
    addon = assets / ADDON
    data = addon.read_bytes() if addon.exists() else retrieve(ADDON_URL, args.addon)
    verified(data, ADDON_SHA, ADDON)
    addon.parent.mkdir(parents=True, exist_ok=True)
    addon.write_bytes(data)
    print('Verified:', ADDON)
    with tempfile.TemporaryDirectory() as tmp:
        apk = Path(tmp) / 'upstream.apk'
        apk.write_bytes(verified(retrieve(APK_URL, args.apk), APK_SHA, 'upstream APK'))
        with zipfile.ZipFile(apk) as archive:
            for digest, relative in entries:
                path = assets / relative
                if path.exists():
                    verified(path.read_bytes(), digest, relative)
                    continue
                data = retrieve(DRIVER_URL, args.driver) if relative == DRIVER else archive.read('assets/' + relative)
                verified(data, digest, relative)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                print('Verified:', relative)

if __name__ == '__main__':
    main()
