# Windows Release Candidate Evidence

## Purpose and safety boundary

This checklist is the evidence record for `W-REL-05`. It is intentionally
separate from the release build: a successful Gradle build does not prove that
Windows install, upgrade, migration, or uninstall behavior is correct.

Run these scenarios only in a disposable Windows VM or a separate disposable
Windows user profile. Do not use the active development profile and do not
replace an already published GitHub Release. A failed scenario is evidence to
record, not a reason to overwrite an existing release asset.

Build the candidate from the Windows repository root:

```powershell
.\gradlew.bat buildWindowsInstaller --no-daemon
```

The candidate directory is `dist/release/<version>` and must contain exactly:

```text
LocalDrop-Setup-<version>-x64.exe
LocalDrop-Setup-<version>-x64.exe.sha256
appfleet-manifest.json
```

Before installing, record the SHA-256 of the EXE and compare it with the
checksum file. Record the candidate version, Windows version, test profile or
VM identifier, and the final test result for every scenario below.

## Required evidence

| Scenario | Required observations | Current status |
| --- | --- | --- |
| Clean per-user install | Installer exits with code `0` without elevation; custom install directory is honored; Start Menu entry works; desktop shortcut remains opt-in and is created only when selected. | NOT TESTED |
| Installed application metadata | About and diagnostics show the Gradle candidate version; `HKCU\Software\PashaApps\<AppId>` contains the expected Version, Executable, ProcessName, RepositoryUrl, InstallerType, and InstalledBy values. | NOT TESTED |
| App icon | `LocalDrop.exe`, Start Menu shortcut, desktop shortcut when selected, and Windows installed-app entry show the approved icon. | NOT TESTED |
| Standard Inno upgrade | Upgrade from the previous standard Inno release keeps the chosen install directory and existing `desktopicon` choice; there is one installed-app entry and one AppFleet registry key. | NOT TESTED |
| Legacy MSI migration | When a real legacy MSI is available, the candidate detects it, completes migration without elevation, and leaves configuration, logs, and receive files intact. Record `N/A` only when no legacy MSI artifact exists. | NOT TESTED |
| Running/tray application | With LocalDrop open, minimized to tray, and receiving a test transfer, installer behavior is explicit and safe: it either closes the app through the normal lifecycle or stops with a clear error. It must not silently corrupt a transfer. | NOT TESTED |
| Locked program file | A deliberately locked program file produces a clear installer failure and does not claim a successful upgrade. | NOT TESTED |
| Uninstall | Program files, shortcuts, Windows uninstall entry, and AppFleet registry key are removed. `%APPDATA%\PashaApps\LocalDrop` and `%LOCALAPPDATA%\PashaApps\LocalDrop` are preserved. | NOT TESTED |

## Evidence commands

Use the actual candidate file name and expected `AppId` from `gradle.properties`.
These commands are for the disposable test environment only.

```powershell
Get-FileHash .\dist\release\<version>\LocalDrop-Setup-<version>-x64.exe -Algorithm SHA256
Get-Content .\dist\release\<version>\LocalDrop-Setup-<version>-x64.exe.sha256
Get-Content .\dist\release\<version>\appfleet-manifest.json
```

After a successful installation, inspect the AppFleet record:

```powershell
Get-ItemProperty 'HKCU:\Software\PashaApps\<AppId>' |
    Select-Object AppId, Version, Executable, ProcessName, RepositoryUrl, InstallerType, InstalledBy
```

For the baseline quiet-installer contract, use only the manifest arguments:

```text
/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS
```

Do not append `/TASKS=desktopicon` to an upgrade test. On a first quiet install,
test the desktop shortcut both with and without that one additional task flag;
an upgrade must retain the already selected task state.

## Completion rule

`W-REL-05` becomes `DONE` only after every applicable row has evidence marked
`PASS`, `FAIL`, or `N/A` with an explanation. Any `FAIL`, `BLOCKED`, or missing
read-back is a release blocker. GitHub publication remains a separate final
step and requires a new version/tag when an immutable published asset differs.
