# LocalDrop

LocalDrop is a JavaFX-based Windows desktop app for local network file transfer without cloud services or accounts.

## Project Structure

- `localdrop` - Windows JavaFX desktop application
- `localdrop-protocol` - shared LAN discovery and transfer contract module for future cross-platform clients

## Requirements

- Windows 10/11
- Java 21 or newer
- Network access within the same LAN

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

## Build Windows Installer

The project includes Gradle tasks that create a self-contained Windows installer with:

- install directory chooser
- optional desktop shortcut prompt
- Start Menu entry
- bundled Java runtime

Build the app image:

```bat
gradlew.bat packageAppImage
```

Build the final Windows installer:

```bat
gradlew.bat packageInstaller
```

Installer output:

- `build\installer\LocalDrop-2.0.0.exe`

Notes:

- `packageInstaller` downloads portable WiX Toolset binaries into `build\tools\wix` automatically.
- The installer icon is generated from the project PNG into a Windows `.ico` file during the build.
- `jpackage` is included in JDK 21+, so the installer build must be run with a full JDK, not a JRE.

## Notes

- LocalDrop uses protocol v2-open only. Any visible LocalDrop device on the same LAN can receive files.
- The app uses UDP broadcast/unicast for discovery and TCP for file transfer inside the local network.
- Windows may show a firewall prompt on first launch. Allow LocalDrop on private networks so discovery and file transfer can work.
- The application minimizes to the system tray instead of exiting when the main window is closed.

## Limitations

- Windows desktop app only. Android code lives in a separate project.
- Protocol v2 is not wire-compatible with the old unsigned v1 flow.
- File payload bytes and control messages are not encrypted or authenticated yet; use LocalDrop only on trusted local networks.
- The transfer queue is stored in memory only and is lost after full exit.
- Transfer resume from byte offsets is not implemented.
- Recently received items are stored for the current session only.
