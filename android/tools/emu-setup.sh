#!/usr/bin/env bash
# One-time: install emulator + system image into the SDK from local.properties, create the AVD.
# Requires: /dev/kvm writable (user in `kvm` group), curl, unzip, ~3 GB disk.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="$(sed -n 's/^sdk.dir=//p' "$HERE/../local.properties")"
IMG="system-images;android-35;google_apis;x86_64"   # google_apis (not playstore): rootable, has Play Services for Google Identity
AVD="pes"

test -w /dev/kvm || { echo "!! /dev/kvm not writable: sudo usermod -aG kvm \$USER, then wsl --shutdown"; exit 1; }

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -sSLo "$tmp/ct.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -qo "$tmp/ct.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"; rm -rf "$SDK/cmdline-tools/latest"; mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SM" --licenses >/dev/null 2>&1 || true
"$SM" --install "emulator" "platform-tools" "$IMG"

AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"
if ! "$AVDM" list avd 2>/dev/null | grep -q "Name: $AVD\$"; then
  echo no | "$AVDM" create avd --force --name "$AVD" --package "$IMG" --device "pixel_6"
fi
cfg="$HOME/.android/avd/$AVD.avd/config.ini"
# Conservative, deterministic settings: keyboard on, modest RAM, GPU off (software), snapshot fast-boot on.
for kv in hw.keyboard=yes hw.ramSize=2048 vm.heapSize=256 hw.gpu.enabled=no hw.gpu.mode=off \
          disk.dataPartition.size=4G fastboot.forceColdBoot=no hw.audioInput=no hw.audioOutput=no; do
  k="${kv%%=*}"; grep -q "^$k=" "$cfg" && sed -i "s|^$k=.*|$kv|" "$cfg" || echo "$kv" >> "$cfg"
done
echo "OK: AVD '$AVD' ready. Next: android/tools/emu.sh start"
