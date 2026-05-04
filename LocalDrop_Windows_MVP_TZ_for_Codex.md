# LocalDrop — Windows Application MVP v0.1

## Technical Specification for Codex

---

## 1. Goal

Create a Windows desktop application named **LocalDrop** for simple file transfer inside a local network.

The Windows application must support:

1. Sending files from this Windows PC to another available LocalDrop device.
2. Receiving files from another available LocalDrop device.
3. Discovery of available devices in the local network.
4. Drag-and-drop file/folder adding.
5. Manual file/folder selection.
6. Transfer status display.
7. Receiving files into a user-selected folder.
8. Minimizing to system tray while keeping the application running.

The MVP must not use cloud storage, Google Drive, external servers, internet APIs, user accounts, or hidden background services.

---

## 2. Product Name

- Application name: **LocalDrop**
- Java package: `com.localdrop`
- Main class: `com.localdrop.LocalDropApp`

---

## 3. Technology Stack

Use the following stack:

- Language: **Java**
- UI framework: **JavaFX**
- Build system: **Gradle**
- Networking:
  - UDP broadcast for device discovery
  - TCP for file transfer
- Storage:
  - JSON configuration file
  - In-memory transfer queue
  - Log files with automatic cleanup

Preferred Java version:

- Java 21 or newer

Do not use:

- Electron
- .NET / WPF
- Cloud APIs
- Embedded web UI
- External database
- Background Windows service

---

## 4. MVP Scope

### 4.1 In Scope

Implement the Windows desktop application only.

The Windows app must be able to:

- Discover other running LocalDrop devices on the same LAN.
- Display available devices in the UI.
- Accept files and folders through drag-and-drop.
- Accept files and folders through buttons.
- Send files to a selected available device.
- Receive files from another LocalDrop device.
- Save received files to the configured receive folder.
- Avoid overwriting existing files by adding suffixes like `(1)`, `(2)`, etc.
- Show transfer statuses in the central queue.
- Keep running in the system tray when minimized/closed to tray.
- Write logs and delete logs older than 24 hours.

### 4.2 Out of Scope for MVP

Do not implement:

- Android client.
- Phone-to-phone transfer.
- User accounts.
- Device registration or pairing.
- Encryption.
- Authentication.
- Internet-based transfer.
- Offline delivery queue after application restart.
- File transfer resume from byte offset.
- Transfer history screen.
- Separate settings screen.
- Autostart with Windows.
- Installer.
- Windows service.

The app may discover devices of type `PC` or `PHONE`, but the Android client itself is not part of this task.

---

## 5. Core Product Rules

1. A device is available only if LocalDrop is running on that device and it responds with `READY`.
2. The user must explicitly run LocalDrop to receive files.
3. If LocalDrop is not running on a target device, that device must not appear as available.
4. Transfers are initiated manually by the sender.
5. Successfully transferred files are removed from the send queue.
6. Failed or not-yet-transferred files remain in the queue until the user removes them or retries.
7. The send queue is stored only in memory and is lost when the application fully exits.
8. Received files are saved into the configured receive folder.
9. Name collisions must not overwrite existing files.
10. The app must remain simple: one main window, no navigation sidebar, no history tab, no settings tab.

---

## 6. UI Requirements

The UI must be visually close to the provided LocalDrop concept image, but simplified according to this specification.
## UI Reference

Use the image `windows-main-reference.png` as the visual reference for the main Windows screen.

Important:
- Reproduce the general layout, spacing, card-based structure, and clean Windows-style appearance.
- Do not implement removed navigation items: Settings, History, Transfer tab.
- Keep the interface as a single main screen.
- The final UI does not need to be pixel-perfect, but it should be visually close to the reference.

### 6.1 General Layout

Create a single-window JavaFX desktop UI with a clean Windows 11-like appearance:

- Light background.
- Rounded cards.
- Soft borders.
- Blue primary action button.
- Green ready/online states.
- Three-column layout.
- Compact header.
- No left navigation sidebar.

Main window title:

```text
LocalDrop
```

Minimum window size:

```text
1200 x 720
```

Recommended default size:

```text
1400 x 800
```

## Application Icon

A PNG icon file will be provided together with this specification.

Expected source icon file:
`localdrop-icon.png`

The main window must contain:

1. Header.
2. Left column: available devices.
3. Center column: files for sending and transfer queue.
4. Right column: receiving status and receive folder.
5. Bottom status bar.

---

## 6.2 Header

At the top of the window show:

- Small app icon placeholder.
- Text: `LocalDrop`
- Standard Windows window controls are provided by the OS.

No navigation tabs.
No sidebar.

---

## 6.3 Left Column — Available Devices

Panel title:

```text
Available devices
```

Below the title:

- Button: `Refresh list`
- Helper text:

```text
Only devices with LocalDrop running are shown.
```

Then display a vertical list of discovered devices.

Each device card must show:

- Device icon placeholder:
  - desktop icon for PC
  - phone icon for phone
- Device name
- Status text:
  - `Ready`
  - `Online`
- Connection indicator icon/text

Device selection:

- Single-select only.
- Only one target device can be selected at a time.
- Selected card must have a blue border/background accent.

Refresh behavior:

- The app must refresh the list automatically based on UDP discovery.
- The `Refresh list` button must force an immediate discovery refresh.
- Expired devices must disappear automatically after timeout.

Empty state text:

```text
No available devices found.
Open LocalDrop on another device and click Refresh list.
```

---

## 6.4 Center Column — Files for Sending

Panel title:

```text
Files for sending
```

At the top of the panel place a large drag-and-drop area.

Drag-and-drop area text:

```text
Drag files or folders here
```

Secondary text:

```text
or use the buttons below
```

Inside or below the drag-and-drop area add two buttons:

```text
Add files
Add folder
```

Behavior:

- `Add files` opens a standard file chooser and supports selecting multiple files.
- `Add folder` opens a directory chooser and recursively adds all files inside the selected folder.
- Drag-and-drop supports both files and folders.
- Folders must be added recursively.
- Folder structure must be preserved during transfer.
- Empty folders may be ignored in MVP unless directory transfer is simple to implement.

After files are added, show the transfer queue below.

---

## 6.5 Send Button

Primary button text when a device is selected:

```text
Send to <DEVICE_NAME>
```

If no device is selected:

```text
Select a device to send
```

Button behavior:

- Button is disabled if there are no files in the queue.
- If there are files but no device is selected, keep the button disabled and show a clear visual state.
- If there are files and a device is selected, enable the button.
- On click, start transfer to the selected device.

Do not implement offline sending.
Do not show a persistent message about unavailable devices.
If sending cannot start, show the error in file statuses and/or a short inline error near the queue.

---

## 6.6 Transfer Queue

Section title:

```text
Transfer queue (<COUNT>)
```

Add a small button/link:

```text
Clear
```

`Clear` removes all files that are not currently transferring.

Each queue item must show:

- File/folder icon placeholder.
- File name or relative path.
- File size.
- Status text.
- Progress bar when transferring.
- Remove button.

Required statuses:

- `Queued`
- `Sending`
- `Sent`
- `Failed`
- `Waiting for retry`

Status behavior:

- Newly added files: `Queued`
- Currently transferring file: `Sending`
- File acknowledged by receiver: remove from queue after successful ACK
- Failed file: `Failed`
- Files not transferred due to interrupted transfer: `Waiting for retry`

When all files are sent successfully:

- Queue becomes empty.
- The transfer queue section may remain visible with empty state.

Empty queue text:

```text
No files selected.
Drag files here or use Add files / Add folder.
```

---

## 6.7 Right Column — Receiving Panel

Panel title:

```text
Receiving
```

At the top show a green ready card.

Ready card content:

```text
This computer
I am ready to receive
Online
```

Also show:

- Current device name.
- Current receive folder.
- Button: `Change`
- Button: `Open`

Receive folder label:

```text
Save folder
```

Default receive folder:

- Use the standard Windows Downloads folder.
- If exact Windows Known Folder detection is unavailable in pure Java, use `%USERPROFILE%\Downloads` as fallback.

`Change` behavior:

- Opens directory chooser.
- Saves the selected folder in configuration.

`Open` behavior:

- Opens the current receive folder in Windows Explorer.

---

## 6.8 Recently Received Section

Below the receiving card, optionally show a compact section:

```text
Recently received
```

This is not a history screen.

Rules:

- Store only current-session received items in memory.
- Do not persist received history to disk.
- Show up to 5 latest received items.
- Each item shows file/folder name, size, and receive time.

If implementation time is limited, this section may be omitted in MVP.

---

## 6.9 Bottom Status Bar

At the bottom show a compact status bar with:

- Current network name if detectable, otherwise `Local network`
- Discovery status:

```text
Device discovery enabled
```

Do not show this text:

```text
If the device is unavailable, remaining files are saved in the queue.
```

Errors should be shown contextually in the queue, not as a permanent footer message.

---

## 6.10 Help and About

Add small secondary actions, not a sidebar:

- `Help`
- `About`

They may be placed in the top-right area, bottom-left area, or simple menu.

MVP behavior:

- `Help` opens a simple modal with short usage instructions.
- `About` opens a simple modal with app name, version, and short description.

Version text:

```text
LocalDrop v0.1
Local network file transfer
```

---

## 7. System Tray Behavior

Implement tray behavior on Windows.

Rules:

- Closing the window must minimize the app to the system tray instead of fully exiting.
- The app continues discovery and file receiving while in tray.
- Add tray menu items:
  - `Open LocalDrop`
  - `Exit`

`Exit` must fully stop:

- UDP discovery
- TCP server
- all background threads
- application process

Do not implement autostart with Windows in MVP.

Implementation note:

- JavaFX can use `java.awt.SystemTray`.
- Use `Platform.setImplicitExit(false)`.

---

## 8. Device Discovery Protocol

### 8.1 UDP

Use UDP broadcast for discovery.

Recommended UDP port:

```text
45454
```

Recommended TCP transfer port:

```text
45455
```

These ports may be constants.

### 8.2 Discovery Message

Broadcast JSON message periodically:

```json
{
  "type": "DISCOVERY",
  "protocolVersion": 1,
  "deviceId": "persistent-device-id",
  "deviceName": "DESKTOP-PAVEL",
  "deviceType": "PC",
  "status": "READY",
  "tcpPort": 45455,
  "timestamp": 1710000000000
}
```

Fields:

- `type`: always `DISCOVERY`
- `protocolVersion`: `1`
- `deviceId`: persistent unique device identifier
- `deviceName`: current computer name
- `deviceType`: `PC` for Windows app
- `status`: `READY`
- `tcpPort`: TCP port used for file receiving
- `timestamp`: sender timestamp in milliseconds

### 8.3 Broadcast Frequency

- Send discovery broadcast every 3 seconds.
- Remove devices not seen for 10 seconds.
- Ignore own discovery messages by comparing `deviceId`.

### 8.4 Manual Refresh

When user clicks `Refresh list`:

- Clear expired devices immediately.
- Send one immediate discovery broadcast.
- Continue normal periodic discovery.

---

## 9. Device Identity

Generate a persistent `deviceId` on first launch.

Recommended format:

```text
UUID
```

Store it in config.

Default device name:

- Use Windows computer name from environment variables:
  - `COMPUTERNAME`
  - fallback to Java host name
  - fallback to `This PC`

No user-facing rename UI is required in MVP.

---

## 10. TCP File Transfer Protocol

### 10.1 General Rules

- Use TCP for file transfer.
- Sender opens a TCP connection to the selected device IP and TCP port.
- Transfer files sequentially.
- Receiver sends ACK after each successfully saved file.
- Successfully ACKed files are removed from sender queue.
- If connection fails, already ACKed files remain sent; remaining files stay in sender queue.
- No byte-level resume in MVP.

### 10.2 Framing

Use a simple length-prefixed protocol.

Recommended frame format:

1. 4-byte integer: JSON header length
2. UTF-8 JSON header
3. Optional binary payload of exactly `size` bytes for file content

Use big-endian integer for the header length.

### 10.3 Session Start Message

Sender sends:

```json
{
  "type": "SESSION_START",
  "protocolVersion": 1,
  "sessionId": "uuid",
  "senderDeviceId": "uuid",
  "senderDeviceName": "DESKTOP-PAVEL",
  "fileCount": 3
}
```

Receiver responds:

```json
{
  "type": "SESSION_ACCEPTED",
  "sessionId": "uuid"
}
```

If receiver rejects:

```json
{
  "type": "SESSION_REJECTED",
  "sessionId": "uuid",
  "message": "Reason"
}
```

MVP may always accept if the receiver app is running and the save folder is writable.

### 10.4 File Metadata Message

Before each file payload, sender sends:

```json
{
  "type": "FILE_META",
  "sessionId": "uuid",
  "fileId": "uuid",
  "relativePath": "Folder/Subfolder/file.txt",
  "fileName": "file.txt",
  "size": 1234567,
  "lastModified": 1710000000000
}
```

Then sender streams exactly `size` bytes.

### 10.5 File ACK Message

Receiver responds after saving the file:

```json
{
  "type": "FILE_ACK",
  "sessionId": "uuid",
  "fileId": "uuid",
  "status": "OK",
  "savedAs": "file (1).txt"
}
```

On error:

```json
{
  "type": "FILE_ACK",
  "sessionId": "uuid",
  "fileId": "uuid",
  "status": "ERROR",
  "message": "Cannot write file"
}
```

### 10.6 Session End Message

After all available files are processed, sender sends:

```json
{
  "type": "SESSION_END",
  "sessionId": "uuid"
}
```

Receiver may respond:

```json
{
  "type": "SESSION_CLOSED",
  "sessionId": "uuid"
}
```

---

## 11. File and Folder Handling

### 11.1 Adding Files

When user adds files:

- Add each selected file as a queue item.
- Store absolute source path internally.
- Display only file name or relative path in the UI.

### 11.2 Adding Folders

When user adds a folder:

- Recursively scan the folder.
- Add all regular files.
- Preserve relative paths including the root folder name.

Example:

Source folder:

```text
C:\Users\Pavel\Desktop\ProjectAlpha\doc\readme.txt
```

If user added `ProjectAlpha`, receiver should save:

```text
<ReceiveFolder>\ProjectAlpha\doc\readme.txt
```

### 11.3 Drag and Drop

Drag-and-drop must support:

- files
- folders
- mixed file/folder selection

Unsupported items must be ignored and logged.

---

## 12. Receive File Saving Rules

### 12.1 Target Path

Receiver builds target path:

```text
<ReceiveFolder>/<relativePath>
```

Create missing directories automatically.

### 12.2 Name Collisions

Never overwrite existing files.

If target file already exists, add suffix before extension:

```text
file.txt
file (1).txt
file (2).txt
```

For files without extension:

```text
file
file (1)
file (2)
```

### 12.3 Temporary Files

While receiving, write into a temporary file:

```text
<target>.localdrop-part
```

After successful receive:

- close stream
- move temporary file to final target name
- send `FILE_ACK` with `OK`

On failure:

- delete incomplete temporary file
- send `FILE_ACK` with `ERROR` if possible

---

## 13. Transfer Queue Rules

### 13.1 In-Memory Only

The queue must exist only while the application process is alive.

- Do not persist queue to disk.
- On full app exit, queue is lost.
- When window is minimized to tray, queue remains because the process is still running.

### 13.2 Partial Transfer Failure

If connection breaks after some files were successfully ACKed:

- ACKed files are removed from the queue.
- Current failed file remains in the queue with status `Failed`.
- Not-yet-sent files remain in the queue with status `Waiting for retry`.
- User can retry manually when the target device is available again.

### 13.3 Removing Items

Each queue item has a remove button.

Rules:

- User can remove `Queued`, `Failed`, or `Waiting for retry` items.
- Current `Sending` item should not be removable unless cancel support is implemented.
- Cancel support is optional for MVP.

---

## 14. Error Handling

Show errors in the queue, not as permanent footer text.

Examples:

- Device disappeared before transfer started.
- TCP connection failed.
- Receiver rejected session.
- File read failed.
- File write failed.
- Network connection interrupted.

For MVP:

- No popup is required.
- Inline file statuses are enough.
- Serious startup errors may be shown in a modal dialog.

---

## 15. Logging

Implement application logging.

Log folder:

```text
%LOCALAPPDATA%\LocalDrop\logs
```

Log file naming:

```text
localdrop-YYYYMMDD-HHMMSS.log
```

At startup:

- Create log folder if missing.
- Delete log files older than 24 hours.

Log at least:

- Application start/stop.
- UDP discovery start/stop.
- TCP server start/stop.
- Discovered devices.
- Transfer start/end.
- Per-file send result.
- Per-file receive result.
- Errors and exceptions.

Do not create a UI log viewer in MVP.

---

## 16. Configuration

Config folder:

```text
%LOCALAPPDATA%\LocalDrop
```

Config file:

```text
config.json
```

Store:

```json
{
  "deviceId": "uuid",
  "receiveFolder": "C:\\Users\\Pavel\\Downloads",
  "windowWidth": 1400,
  "windowHeight": 800
}
```

Required config behavior:

- Create config on first launch.
- Persist `deviceId`.
- Persist receive folder.
- Persist window size if simple to implement.

Do not store transfer queue.
Do not store transfer history.

---

## 17. Threading and Responsiveness

The JavaFX UI must never block during network or file operations.

Use background threads or executor services for:

- UDP discovery loop.
- TCP server accept loop.
- TCP file sending.
- TCP file receiving.
- Recursive folder scanning if folder is large.

UI updates must be marshalled onto the JavaFX Application Thread using `Platform.runLater` or equivalent.

On full application exit:

- Stop discovery thread.
- Stop TCP server.
- Stop transfer executor.
- Close sockets.
- Flush logs.

---

## 18. Windows Firewall Note

The application uses local network sockets.

It is acceptable if Windows shows a firewall prompt on first run.

Do not attempt to programmatically modify Windows Firewall rules in MVP.

---

## 19. Suggested Project Structure

Use a clean modular structure.

```text
localdrop/
  build.gradle
  settings.gradle
  README.md
  src/
    main/
      java/
        com/localdrop/
          LocalDropApp.java
          config/
            AppConfig.java
            ConfigService.java
          discovery/
            DeviceInfo.java
            DiscoveryService.java
            DiscoveryMessage.java
          transfer/
            TransferServer.java
            TransferClient.java
            TransferSession.java
            TransferQueueItem.java
            TransferStatus.java
            ProtocolMessage.java
            FileNameResolver.java
          ui/
            MainController.java
            MainView.java
            TrayService.java
            Dialogs.java
          util/
            AppPaths.java
            FileUtils.java
            LogService.java
            JsonUtils.java
      resources/
        com/localdrop/
          styles.css
          icons/
            app.png
```

FXML is optional.

Preferred UI approach:

- Either JavaFX programmatic UI with clean components.
- Or FXML + controller.

Do not mix too many patterns.
Keep the structure simple and maintainable.

---

## 20. Visual Style Requirements

Use CSS for styling.

Approximate style:

- Background: very light gray.
- Cards: white with light border and rounded corners.
- Primary action: blue button.
- Ready state: green text/accent.
- Error state: red text/accent.
- Warning/waiting state: orange/yellow accent.
- Queue rows: white rows with separators.
- Selected device: blue border and subtle blue background.

Do not overcomplicate animations.
Do not use custom font files.
Use system font.

---

## 21. Required User Flows

### 21.1 Send Files from Windows to Another Device

1. User opens LocalDrop.
2. App discovers available LocalDrop devices in LAN.
3. User drags files/folders into the center drop area or uses `Add files` / `Add folder`.
4. Files appear in transfer queue as `Queued`.
5. User selects one available device.
6. User clicks `Send to <DEVICE_NAME>`.
7. App opens TCP connection.
8. Files are sent sequentially.
9. Receiver sends ACK for each file.
10. ACKed files disappear from queue.
11. Failed files remain with error status.

### 21.2 Receive Files on Windows

1. User opens LocalDrop.
2. App starts TCP server and UDP discovery.
3. This PC becomes visible to other LocalDrop devices as `READY`.
4. Another device sends files.
5. App saves files into the configured receive folder.
6. File name collisions are resolved with `(1)`, `(2)`, etc.
7. App sends ACK for each successfully saved file.
8. Optionally show received item in current-session `Recently received` section.

### 21.3 Minimize to Tray

1. User closes or minimizes the window.
2. App hides window and stays in tray.
3. Discovery and receiving continue.
4. User opens tray menu and selects `Open LocalDrop`.
5. Main window appears again.
6. User selects `Exit` to fully quit.

---

## 22. Acceptance Criteria

The implementation is acceptable when all criteria below pass.

### 22.1 UI

- App launches with title `LocalDrop`.
- Main UI has three columns and no left navigation sidebar.
- There is no History tab.
- There is no Settings tab.
- There is no permanent footer text about unavailable devices or saved queue.
- User can change receive folder from the right receiving panel.
- User can open receive folder from the right receiving panel.

### 22.2 File Adding

- User can add multiple files through `Add files`.
- User can add a folder through `Add folder`.
- Folder files are added recursively.
- User can drag files into the drop area.
- User can drag folders into the drop area.

### 22.3 Discovery

- Two running LocalDrop instances in the same LAN can discover each other.
- Own device is not shown in available devices.
- Device disappears if no discovery message is received for timeout period.
- `Refresh list` sends immediate discovery broadcast.

### 22.4 Transfer

- File transfer uses TCP.
- Receiver ACKs each file.
- Sender removes successfully ACKed files from queue.
- If transfer fails mid-session, already sent files remain sent and remaining files stay in queue.
- No existing file is overwritten on receiver side.

### 22.5 Receive Folder

- Default receive folder is Windows Downloads folder.
- Changed receive folder persists after restart.
- Received files are saved into configured folder.

### 22.6 Tray

- Closing the main window minimizes to tray.
- App continues to receive files while in tray.
- Tray menu has `Open LocalDrop` and `Exit`.
- `Exit` fully stops the app.

### 22.7 Logs

- Log file is created under `%LOCALAPPDATA%\LocalDrop\logs`.
- Logs older than 24 hours are deleted on startup.

---

## 23. Manual Test Plan

### Test 1 — Launch

1. Run the application.
2. Verify main window appears.
3. Verify receiving panel says `I am ready to receive`.
4. Verify default receive folder is Downloads.

Expected result:

- App is running and ready.

### Test 2 — Add Files

1. Click `Add files`.
2. Select several files.
3. Verify files appear in queue.

Expected result:

- Files are shown as `Queued`.

### Test 3 — Add Folder

1. Click `Add folder`.
2. Select a folder with nested files.
3. Verify nested files appear in queue.

Expected result:

- Files are added recursively.

### Test 4 — Drag and Drop

1. Drag files from Windows Explorer into the drop area.
2. Drag a folder from Windows Explorer into the drop area.

Expected result:

- Files are added to queue.

### Test 5 — Discovery with Two Instances

1. Run LocalDrop on two PCs in the same LAN.
2. Wait up to 10 seconds.

Expected result:

- Each app sees the other device.
- Each app does not show itself.

### Test 6 — Transfer File

1. Add a file on sender.
2. Select target device.
3. Click `Send to <DEVICE_NAME>`.

Expected result:

- File is transferred.
- File is saved on receiver.
- Sender queue item disappears after ACK.

### Test 7 — Name Collision

1. Transfer `test.txt` to receiver.
2. Transfer another `test.txt` again.

Expected result:

- Receiver saves second file as `test (1).txt`.

### Test 8 — Partial Failure

1. Start transferring several large files.
2. Stop receiver app or disconnect network during transfer.

Expected result:

- Already ACKed files are removed from queue.
- Current/remaining files remain in queue with error/retry status.

### Test 9 — Tray

1. Close main window.
2. Verify app remains in system tray.
3. Send file to this PC from another instance.
4. Open app from tray.
5. Exit using tray menu.

Expected result:

- App receives while in tray.
- App fully exits only through `Exit`.

### Test 10 — Log Cleanup

1. Create fake old log file older than 24 hours in log folder.
2. Start app.

Expected result:

- Old log file is deleted.

---

## 24. README Requirements

Create `README.md` with:

- Short description of LocalDrop.
- Requirements.
- How to build.
- How to run.
- Notes about Windows Firewall prompt.
- MVP limitations.

Include commands:

```bash
./gradlew run
./gradlew build
```

For Windows:

```bat
gradlew.bat run
gradlew.bat build
```

---

## 25. Implementation Priorities

Implement in this order:

1. Project skeleton with JavaFX window.
2. UI layout and styling.
3. Config service and receive folder handling.
4. Logging with 24-hour cleanup.
5. UDP discovery service.
6. TCP receiving server.
7. File/folder queue model.
8. TCP sending client with ACK handling.
9. Tray behavior.
10. Manual testing and cleanup.

---

## 26. Important Constraints for Codex

- Produce a complete working project, not isolated snippets.
- Prefer simple, reliable code over clever abstractions.
- Keep all user-facing text in English for now.
- Do not implement features that are explicitly out of scope.
- Do not add a settings screen.
- Do not add a history screen.
- Do not add Google Drive or cloud logic.
- Do not add autostart.
- Do not add account/login logic.
- Do not require external services.
- Do not block the JavaFX UI thread with network or file operations.
- Do not overwrite received files.
- Do not persist the transfer queue.

---

## 27. Final Expected Result

At the end of implementation there must be a runnable JavaFX Windows application named **LocalDrop**.

The user must be able to run two LocalDrop instances on two PCs in the same LAN, discover the other PC, add files/folders, send them, receive them into the configured Downloads folder, and keep the app available from the system tray.
