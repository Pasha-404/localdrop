# LocalDrop

LocalDrop is a JavaFX-based Windows desktop app for local network file transfer without cloud services or accounts.

## Project Structure

- `src` - Windows JavaFX desktop application
- `localdrop-protocol` - shared LAN discovery and transfer contract module used by the Windows app
- `../android` - Android client when this repository is opened inside the combined LocalDrop workspace

## Development Requirements

- Windows 10/11
- JDK 21 or newer
- Network access within the same LAN

Installed LocalDrop does not require Java: the app image includes its own Java runtime.

## Build

Linux/macOS:

```bash
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

## Run

Linux/macOS:

```bash
./gradlew run
```

Windows:

```bat
gradlew.bat run
```

## Build Windows Release

The Windows release is a self-contained, per-user Inno Setup installer with:

- install directory chooser
- optional desktop shortcut
- Start Menu entry
- bundled Java runtime
- AppFleet manifest and SHA-256 checksum

Install Inno Setup 6 once on the build machine:

```bat
choco install innosetup -y
```

Then build and verify the complete release set:

```bat
gradlew.bat clean buildWindowsInstaller --no-daemon
```

Release output:

- `dist\release\<version>\LocalDrop-Setup-<version>-x64.exe`
- `dist\release\<version>\LocalDrop-Setup-<version>-x64.exe.sha256`
- `dist\release\<version>\appfleet-manifest.json`

Notes:

- The project version in `build.gradle` is the single source for the application, installer, manifest, and release tag version. A GitHub tag must be named `v<version>`.
- `jpackage` creates the app image; Inno Setup 6 creates the final EXE. The current `.ico` is used for both.
- The installer defaults to `%LOCALAPPDATA%\Programs\PashaApps\LocalDrop`, never needs permanent administrator rights, and offers a final-screen option to launch LocalDrop.
- Existing WiX/MSI installations are detected only through the known LocalDrop MSI UpgradeCode. The installer asks before removing that legacy version and does not delete user data.
- `packageInstaller` remains as an alias for `buildWindowsInstaller` for local compatibility.

## Application Data

- Configuration: `%APPDATA%\PashaApps\LocalDrop\config.json`
- Logs: `%LOCALAPPDATA%\PashaApps\LocalDrop\logs`

On first start after upgrading from a pre-2.3.0 version, LocalDrop copies the old configuration from `%LOCALAPPDATA%\LocalDrop\config.json` only if no new configuration exists. The old file is retained.

## Notes

- LocalDrop uses protocol v2-open only. Any visible LocalDrop device on the same LAN can receive files.
- The app uses UDP broadcast/unicast for discovery and TCP for file transfer inside the local network.
- Windows may show a firewall prompt on first launch. Allow LocalDrop on private networks so discovery and file transfer can work.
- The application minimizes to the system tray instead of exiting when the main window is closed.

## Limitations

- Windows desktop app only. Android code lives in the sibling `../android` client when using the combined workspace.
- Protocol v2 is not wire-compatible with the old unsigned v1 flow.
- File payload bytes and control messages are not encrypted or authenticated yet; use LocalDrop only on trusted local networks.
- The transfer queue is stored in memory only and is lost after full exit.
- Transfer resume from byte offsets is not implemented.
- Recently received items are stored for the current session only.
