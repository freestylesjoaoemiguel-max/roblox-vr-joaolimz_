#!/bin/sh
# Builds dist/RobloxVRPatcher.app and .dmg, installs the app into /Applications.
set -eu
cd "$(dirname "$0")/.."
swift build -c release 2>&1 | tail -1
A=dist/RobloxVRPatcher.app; rm -rf $A dist/RobloxVRPatcher.dmg; mkdir -p dist $A/Contents/MacOS $A/Contents/Resources
cp .build/release/RobloxVRPatcher $A/Contents/MacOS/
rsync -a --exclude '__pycache__' --exclude build_payload.sh --exclude package_app.sh --exclude "gen_profile_*.py" vrkit $A/Contents/Resources/
cat > $A/Contents/Info.plist <<'P'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>
<key>CFBundleName</key><string>Roblox VR Patcher</string><key>CFBundleDisplayName</key><string>Roblox VR Patcher</string><key>CFBundleExecutable</key><string>RobloxVRPatcher</string>
<key>CFBundleIdentifier</key><string>local.robloxvr.patcher</string><key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>2.1</string><key>LSMinimumSystemVersion</key><string>13.0</string><key>NSHighResolutionCapable</key><true/>
</dict></plist>
P
codesign --force --deep -s - $A 2>/dev/null
R=$(mktemp -d); cp -R $A $R/; ln -s /Applications $R/Applications
hdiutil create -quiet -volname "Roblox VR Patcher" -srcfolder $R -ov dist/RobloxVRPatcher.dmg; rm -rf $R
pkill RobloxVRPatcher 2>/dev/null || true
rm -rf /Applications/RobloxVRPatcher.app; cp -R $A /Applications/
echo done
