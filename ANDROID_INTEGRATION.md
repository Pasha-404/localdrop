# Android Integration Guide

This file explains how the future Android client should integrate with the current Windows LocalDrop application.

## Purpose

The Windows app and the future Android app must share one LAN discovery and file-transfer contract.

The source of truth for that shared contract is:

- `localdrop-protocol`

The Android client should reuse the same protocol rules and message structure instead of redefining them independently.

## Shared Module

The `localdrop-protocol` module contains only protocol-level shared definitions:

- `com.localdrop.protocol.ProtocolConstants`
- `com.localdrop.protocol.ProtocolJson`
- `com.localdrop.protocol.discovery.DeviceInfo`
- `com.localdrop.protocol.discovery.DiscoveryMessage`
- `com.localdrop.protocol.transfer.ProtocolMessage`

This module is intended to be the shared contract between:

- Windows desktop app
- future Android app
- any future additional LocalDrop client

## What Is Shared vs Platform-Specific

Shared:

- UDP discovery payload format
- TCP transfer message format
- protocol constants
- JSON field names
- transfer session message order
- file metadata structure

Platform-specific:

- UI
- background execution model
- storage access
- file picker / share sheet integration
- socket lifecycle implementation
- notifications
- app permissions
- receive-folder selection UX

Do not move Android UI logic or Windows UI logic into `localdrop-protocol`.

## Protocol Version

Current protocol version:

- `protocolVersion = 1`

If protocol fields or message flow are changed in the future, both Windows and Android implementations must be checked together.

## Network Ports

Use these shared defaults:

- UDP discovery port: `45454`
- TCP transfer port: `45455`

These values are defined in `ProtocolConstants`.

## Discovery Contract

The Windows app broadcasts and listens for discovery messages over UDP.

Discovery payload model:

- `type = DISCOVERY`
- `protocolVersion = 1`
- `deviceId`
- `deviceName`
- `deviceType`
- `status`
- `tcpPort`
- `timestamp`

Important rules:

- each client must generate and persist its own `deviceId`
- a client must ignore its own discovery packets by comparing `deviceId`
- only running LocalDrop clients should appear as available devices
- expired devices should disappear after timeout

Expected device types:

- `PC`
- `PHONE`

For Android, the client should broadcast `deviceType = PHONE`.

## Transfer Contract

The current transfer flow is:

1. Sender opens TCP connection to target device.
2. Sender sends `SESSION_START`.
3. Receiver returns `SESSION_ACCEPTED` or `SESSION_REJECTED`.
4. For each file, sender sends `FILE_META`, then file bytes.
5. Receiver saves the file and returns `FILE_ACK`.
6. After all files are processed, sender sends `SESSION_END`.
7. Receiver may return `SESSION_CLOSED`.

## Current Compatibility Notes

Windows currently accepts these Android-side compatibility variants in addition to the canonical shared field names:

- `deviceId` as an alias of `senderDeviceId`
- `deviceName` as an alias of `senderDeviceName`
- `totalFiles` as an alias of `fileCount`
- `reason` as an alias of `message`

Windows sender logic is also tolerant of Android-style successful `FILE_ACK` messages that omit:

- `status`
- `savedAs`

and it treats a terminal `SESSION_CLOSED` on the last file as a compatibility success path.

## Framing Rules

Each protocol frame uses:

1. `4-byte big-endian integer` = JSON header length
2. `UTF-8 JSON header`
3. optional binary payload of exactly `size` bytes

The binary payload is used only after `FILE_META`.

## File Metadata Rules

`FILE_META` includes:

- `sessionId`
- `fileId`
- `relativePath`
- `fileName`
- `size`
- `lastModified`

Important rule:

- `relativePath` is the canonical path to reconstruct the folder tree on the receiver side

If the sender adds a folder, the root folder name must be preserved in `relativePath`.

## File Saving Rules

Receiver behavior must match Windows behavior:

- save to the configured receive location
- create missing directories automatically
- never overwrite existing files
- resolve collisions with suffixes like `(1)`, `(2)`
- write temporary `.localdrop-part` file first
- move to final filename only after successful receive

Android implementation should preserve these rules even if the storage APIs differ.

## Error Handling Expectations

If transfer fails:

- already acknowledged files remain successful
- current failed file remains failed
- remaining files should stay retryable

Android should follow the same logical behavior even if its UI representation is different.

## Android-Specific Guidance

When implementing Android:

- do not assume direct filesystem `Path` access like desktop Java
- expect to work with Android storage APIs and URI-based access
- discovery and transfer should run off the UI thread
- receiving while the app is backgrounded may require foreground-service style behavior depending on Android version and product decisions
- permissions and local network constraints must be handled explicitly in the Android app

The Android client does not need to copy the Windows implementation structure exactly. It only needs to respect the same protocol contract.

## Recommended Android Work Split

Suggested implementation order:

1. add the shared protocol module to the Android project
2. implement UDP discovery using `DiscoveryMessage`
3. implement TCP receiving using `ProtocolMessage`
4. implement TCP sending using `ProtocolMessage`
5. implement Android file selection and receive storage mapping
6. connect the Android UI to discovery and transfer state

## Rules for Future Changes

If changing any of the following, treat it as a shared protocol change:

- UDP/TCP ports
- message names
- JSON field names
- framing format
- `deviceType` values
- transfer session order
- meaning of `relativePath`

When making such changes:

1. update `localdrop-protocol`
2. update Windows app
3. update Android app
4. re-test Windows-to-Windows and Windows-to-Android compatibility

## Manual Compatibility Checklist

Before considering Android integration complete, verify:

1. Android discovers Windows.
2. Windows discovers Android.
3. Android can send file to Windows.
4. Windows can send file to Android.
5. Nested folder transfer preserves structure.
6. Filename collisions are resolved safely.
7. Interrupted transfers leave retryable state.
8. Device timeout/removal works correctly.

## Important Note for Future Work

If you are working on Android integration or changing discovery / transfer behavior, read this file first and treat it as integration guidance for the current Windows implementation.
