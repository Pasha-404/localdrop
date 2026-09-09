# LocalDrop Windows — GPT-6 Code Review

Review date: 2026-09-08. Baseline: Windows `3203aa9` / `v2.3.0`, Android `e894fe1` / `v1.4.1`. Shared contract: Protocol v2-open, revision `2026-05-16-r2`.

This is an implementation handoff, not a patch or release approval. Source code and existing documentation were not changed. Cross-platform findings use `X-*` identifiers in both reports; those identifiers represent the same issue, not additional independent defects. P1 means possible data loss or a materially broken workflow; P2 means a correctness/contract issue; P3 means a nonessential improvement.

Companion documents:

- [Android review](../../android/docs/CODE_REVIEW_GPT-6.md)
- [UI, UX, and Windows sizing](../../docs/UI_UX_REVIEW_GPT-6.md)
- [New device-trust design](../../docs/DEVICE_TRUST_DESIGN_GPT-6.md)
- [Canonical installer standard](../../docs/Windows_Installer_Standard.md)

## Scope, architecture, and evidence

Windows is a Java 21/JavaFX desktop application with a controller-owned UI, UDP discovery, a TCP sender and receiver, per-user JSON configuration, tray/single-instance integration, and a jpackage/Inno release pipeline. Android independently implements the same protocol with Kotlin, Compose, application-owned coroutines, and SAF storage. There is no cloud intermediary. Windows-to-Windows is also supported.

The current transfer sequence is:

`UDP DISCOVERY → live/readiness lookup → TCP connect → SESSION_START/ACCEPTED → FILE_META + exact payload → FILE_ACK → SESSION_FINISH/ACK`

Discovery learns the source IP from the datagram, uses the advertised TCP port, expires peers, and supports legacy missing readiness status within v2. It is an availability hint, not authentication. Both senders recheck discovery before dispatch, but connection/session acceptance remains authoritative. Files commit individually, before the final session ACK; the batch is not an all-or-nothing filesystem transaction.

### Checks performed

| Check | Result | Meaning / limit |
| --- | --- | --- |
| Windows `gradlew.bat test --no-daemon --rerun-tasks` | PASS | 13 tests, zero failures/errors/skips, including one Windows-to-Windows socket integration test. |
| Android `lintDebug testDebugUnitTest assembleDebug --no-daemon` | PASS | Lint: zero errors, 37 warnings; debug APK built. |
| Android `testDebugUnitTest --no-daemon --rerun-tasks` | PASS | 9 tests, zero failures/errors/skips. |
| Windows `buildWindowsInstaller --no-daemon` | PASS | Inno Setup 6.7.1 produced the installer; existing Gradle verifier passed. This does not establish compliance with every standard requirement. |
| Synthetic Windows file/protocol probes | REPRODUCED | Actual compiled methods, disposable files and in-memory wire streams; no user receive folder involved. |
| JavaFX controller callback probe | REPRODUCED | Actual controller/listener, fake sender, no shown window or started networking services. |
| Physical Windows ↔ Android transfer; VPN/firewall/multi-adapter matrix | NOT TESTED | No inference of device interoperability from the unit tests or screenshots. |
| Clean install, live upgrade, MSI migration, uninstall, AppFleet invocation | NOT TESTED | No installation, process termination, registry mutation, or AppFleet execution. |

The regenerated local release contains exactly three files. The installer is 45,923,388 bytes; its computed SHA-256 matches the checksum file: `0eaaad6bd155c1b9ddb141916923481cd7b16d7127b0a98a6c343476e92cd376`. These are this review's local build bytes, not a claim about published GitHub assets. Build output was regenerated; no release was published.

### What is already sound / historically fixed

- The Android source-size problem was addressed with bounded snapshot preparation (`bab33b9`); do not reintroduce reliance on provider metadata as payload length.
- Protocol clarification and golden vectors were added in Android `ccc4b6c` / `d153129` and Windows `98e1bf7`. Current gaps below concern additional negative cases, not absence of framing or all correlation checks.
- Diagnostics improvements in Windows `b876928` and Android `af7cdc0` make unavailable peers, listener errors, and endpoint freshness inspectable.
- Progress throttles have deterministic tests and preserve the first/final notification. W-03 concerns state mutation by a callback, not a need to remove throttling.
- Windows `69891b0` centralizes restore and clears iconification before show/hide. The historical Windows 11 restore-order defect should not be reported as still present without new evidence.
- `3203aa9` moved to Inno and introduced copy-only legacy configuration migration. `AppPathsTest` covers preservation of an existing new configuration and of the old source.
- Removing pairing was intentional. Its absence, unencrypted v2 traffic, and lack of automatic byte-level resume are not newly discovered regressions.

## 1. Defects / Must Fix

### X-01 · P1 · Cleanup identifies ownership by suffix and can delete user files

**Evidence:** `src/main/java/com/localdrop/util/FileUtils.java:124`, `cleanupPartialFiles`; calls in `transfer/TransferServer.java:106` and `:640`. Android equivalent: `DocumentTreeStore.cleanupStalePartFilesRecursive`.

**Scenario and consequence:** A legitimate file named `archive.localdrop-part`, older than the 24-hour threshold, exists in the selected receive tree. Startup/cleanup deletes it without an operation record. The protocol permits this filename. A synthetic probe of the actual Windows method printed `USER_SUFFIX_FILE_PRESERVED=false`.

**Fix:** Use an app-owned staging namespace plus a durable operation/ownership record, or another design that proves artifact ownership. Name, suffix, age, and presence under Downloads are not sufficient. An unidentified legacy `.localdrop-part` must be left alone. Keep cleanup idempotent; validate each recorded target against its operation and allowed storage boundary.

**Regression tests:** Preserve old and fresh user files with the suffix, including nested paths; delete only registered abandoned staging objects; preserve completed outputs and active operations; repeat cleanup; handle missing/corrupt ownership records conservatively. Replace the existing test expectation that any old suffix match should be removed.

**Cross-platform dependency:** Implement the same ownership invariant on Android, using document identities rather than filesystem assumptions. Do not copy the Windows staging implementation into SAF.

### W-01 · P1 · Deterministic staging name can truncate an existing user file

**Evidence:** `transfer/TransferServer.java:507`, `receiveSingleFile`: `finalName + ".localdrop-part"`, followed by `Files.newOutputStream(tempPath)` without exclusive creation.

**Scenario and consequence:** `keep.txt` is available but `keep.txt.localdrop-part` already contains user data. Receiving `keep.txt` truncates that existing file, then moves or deletes it. The probe printed `STAGING_COLLISION_USER_PRESERVED=false` while the received file contained the new payload.

**Fix:** Create a distinct operation-owned staging object with exclusive `CREATE_NEW`; retry a generated staging identifier on collision. Never open a preexisting candidate with truncation. Record the exact successfully created object before permitting cleanup.

**Regression tests:** Preexisting deterministic temp name; concurrent staging creation; crash between reservation and write; output-open failure. All existing bytes must remain unchanged.

### W-02 · P1 · Finalization explicitly weakens no-clobber to replacement

**Evidence:** `util/FileUtils.java:179`, `moveAtomicallyOrReplace`; `transfer/TransferServer.java:491` and `:552`.

**Scenario and consequence:** A destination appears after unique-name selection. The helper tries `ATOMIC_MOVE`, then catches any `IOException` and falls back to `REPLACE_EXISTING`. A synthetic occupied-destination probe replaced `USER ORIGINAL` with `NEW`. The earlier existence check is not a reservation. `ATOMIC_MOVE` alone must not be assumed to guarantee no replacement.

**Fix:** Define and test a publication primitive that cannot replace an existing destination on the supported Windows filesystem. Handle collision by selecting another name and retrying safely; distinguish unsupported atomic operation from permission/disk errors. A fallback must retain no-clobber. Preserve verified staging on an uncertain finalization result until reconciled.

**Regression tests:** Destination created between selection and publication; forced atomic-move unsupported/error paths; destination directory; access denied; disk full; interrupted publication. Assert the preexisting destination bytes, not merely the returned filename.

### X-02 · P2 · Android accepts successful session completion with too few aggregate bytes

**Evidence:** Windows `TransferServer.validateSessionFinish` checks exact `receivedBytes == totalSize`; Android `TransferService.kt:567` and `validateSessionFinish:850` validate the finish envelope but omit this equality.

**Scenario and consequence:** A session announces one file and `totalSize=10`, supplies one valid four-byte file, and finishes. Android's per-file upper-bound check allows it and its finish path returns success; Windows rejects the equivalent session. This is a confirmed static branch difference, not a device-run result.

**Fix:** Preserve the Windows exact total/count check. Add the matching Android terminal guard using successfully received/committed counters. Clarify the canonical finish section and add identical negative vectors. Earlier completely committed files are a partial result, not objects to delete to simulate a batch rollback.

**Regression tests:** Exact total; zero-byte file; under-declared/over-declared aggregate; too few/many files; failed file followed by finish; duplicate file ID. Run both receivers against the same vector set.

### X-03 · P2 · Envelope validation and rejection identity diverge across clients

**Evidence:** Windows `localdrop-protocol/.../transfer/ProtocolMessage.java`, `read` and `sessionRejected`; `TransferServer.validateSessionStart`, `validateFileMeta`, `validateSessionFinish`, `writeSessionRejection`; `TransferClient.isExpectedResponse`. Android uses `hasCommonFields` and per-step version checks, but both clients branch on rejection before all expected response correlation is established.

**Scenario and consequence:** A valid Windows session can continue with `FILE_META` and `SESSION_FINISH` carrying `protocolVersion=99`, missing `messageId` and `timestamp`. The actual receive-loop probe accepted both (`OK`, then `SESSION_FINISH_ACK`). Windows rejection messages also omit the local `deviceId`; the synthetic factory probe returned null. This violates the common required envelope and prevents consistent strict validation.

**Fix:** Centralize structural/common-envelope validation in each implementation, then apply phase-specific type, session, peer and file correlation. Validate error replies as well as positive replies where correlation is available. Populate Windows rejection identity. Define a narrow initial-malformed-header exception when no valid session ID can be recovered; it must not authorize transfer. Align discovery's required-field rules as part of the same contract work: Android currently defaults missing `tcpPort`, whereas Windows rejects it and the specification requires a valid port.

**Regression tests:** Wrong/missing version at every phase; missing common fields; wrong peer/session/file on both success and rejection; malformed initial start; missing discovery port; overlong names; optional unknown fields remain forward-compatible.

### X-04 · P2 · A positive-looking FILE_ACK can contain an explicit error and still pass

**Evidence:** `TransferClient.java:276`, `validateFileAck`; Android `TransferService.validateFileAck`. Both reject `success=false`, `status=ERROR`, and `checksumOk=false`, but accept a positive indicator alongside a non-`NONE` error code. The Windows probe accepted `status=OK` with `errorCode=FILE_WRITE_ERROR`.

**Consequence:** The sender can remove an item from the queue although the reply reports a failure. Root `AGENTS.md` explicitly disallows this contradictory successful ACK. The canonical ACK prose should make precedence equally explicit.

**Fix:** After correlation, treat a nonempty explicit error other than `NONE` as failure even if a positive indicator is present; normalize unknown errors consistently. Keep the current documented compatibility among supported positive indicators, rather than arbitrarily requiring all of them.

**Regression tests:** Every positive indicator crossed with false/error/checksum-false; `NONE`, absent and unknown error codes; correlated negative ACK. Share vectors with Android.

### X-05 · P2 · Relative paths and maximum depth have different meanings

**Evidence:** Windows `FileUtils.sanitizeReceivedRelativePath`; Android `storage/ReceivePath.kt`, `normalize`, and `DocumentTreeStore.ensureDirectory`.

**Scenario and consequence:** Windows normalizes before segment validation: `album/../renamed.txt` becomes `renamed.txt` in the probe, whereas Android rejects `..`. If `relativePath` ends in a different name from `fileName`, Windows uses that last path component as the output name; Android treats it as another directory and appends `fileName`. Android counts directory depth after removing the filename; Windows counts all path components. Identical metadata can therefore be rejected, relocated, or saved under different names.

**Fix:** Specify one full-file-relative-path convention, the leaf/name relationship, and whether depth includes the leaf. Reject prohibited raw segments before normalization. Implement the same acceptance/output mapping on both platforms; retain separator and Windows-name protections.

**Regression tests:** Same-name and mismatched leaf; absent/empty path; `.`/`..`; slash/backslash; exact depth boundary and one beyond; reserved names, trailing dot/space, Unicode, and case-insensitive collisions. Compare the resulting relative output, not just successful parsing.

### W-03 · P1 · Failure progress resets restore SENDING and lock the queue item

**Evidence:** `TransferClient.markRemaining` emits a failed/retry status followed by zero progress. `ui/MainController.java:446`, `onItemProgress`, unconditionally sets `SENDING` and clears the error. `onTransferFinished` only clears the controller flag.

**Scenario and consequence:** A connection failure before the first payload produces a retryable status, immediately overwritten by the progress callback. An isolated JavaFX probe of the real controller printed `STATE_AFTER_FAILURE=SENDING`, `CAN_REMOVE=false`, `SEND_DISABLED=true`. No live server was required.

**Fix:** Make progress callbacks telemetry-only; phase/status changes belong to explicit state events. Associate callbacks with the active operation/item, and ignore stale callbacks after a terminal transition. Do not let throttling suppress terminal status/reset events.

**Regression tests:** Drive the real controller through connection refusal, receiver rejection, file error, lost ACK and success. Assert final status, visible message, retry action, remove action and no post-terminal state reversal. Existing transfer tests use a simpler listener and do not cover this binding.

### W-04 · P1 · Clear/remove can hide files that are still scheduled for transmission

**Evidence:** `MainController.java:396`, `sendQueue`, snapshots `pendingItems`; `:486`, `clearQueue`, and `TransferQueueItem.canRemove` only protect items currently marked `SENDING`.

**Scenario and consequence:** In a batch, later files remain `QUEUED` while the first is being sent. Clear/remove deletes them from the visible queue but not from the already captured sender list. They can still be transmitted after the user believes they were removed. During connection establishment even the whole batch can remain removable.

**Fix:** Reserve the complete batch synchronously at dispatch and protect all its members until terminal completion, or disable removal of that batch while active. A genuine cancel must close/cancel the operation; do not silently change an already announced session total.

**Regression tests:** Block a fake sender before connection and between files; clear/remove; assert that UI-visible removal never leaves a hidden scheduled send. Test adding unrelated new files during a batch separately.

### W-05 · P2 · Main-screen green readiness is disconnected from receiver state

**Evidence:** `MainView.applyTexts` sets `readyMessageLabel`, `onlineChipLabel`, and `discoveryLabel` to success text unconditionally. Controller errors update the smaller receiving activity/error labels but not those indicators.

**Scenario and consequence:** No TCP port can be bound, the receive folder becomes unwritable, or discovery fails; the large receiver card still says ready/online. The wire status can correctly be unavailable while the local screen contradicts it.

**Fix:** Render the card from the same explicit local availability state used by discovery. Separate listener readiness from actual network reachability; READY is not proof that a remote peer can connect. Reapply state after language changes.

**Regression tests:** Starting, ready, busy, unwritable folder, all receive ports occupied, discovery failure/recovery, stopped; switch language in every state. Assert text, color, and enabled actions together.

### W-06 · P2 · Release metadata and validation do not implement the current installer standard

**Evidence:** `build.gradle`: hardcoded `version` near line 18; JAR manifest; `generateAppFleetManifest`, `verifyReleaseArtifacts`; `installer/windows/inno/LocalDrop.iss`; generated `appfleet-manifest.json`; `DiagnosticsService.applicationVersion` falls back to `2.2.1`.

**Observed differences:** The current manifest has `minimumAppFleetVersion=1.0.0` and no `installer.desktopShortcutTask`. The standard calls for baseline `2.0.0` and `desktopicon` on the next standardized release. Version lives in `build.gradle`, not the required property source. Localized About text is filtered, but the final JAR lacks the required coherent version/repository/AppId metadata contract; diagnostics therefore report the stale fallback. The verifier checks only a subset of manifest fields and does not reject these differences.

**Consequence:** A build can pass while violating the standard or showing conflicting installed/running versions. Missing shortcut-task metadata prevents the new standard's external shortcut policy from being expressed. This does not prove that older AppFleet versions currently fail to install the existing release; the standard explicitly accommodates older manifests during migration.

**Fix:** For a new release, generate installer, final-JAR metadata, About/diagnostics and AppFleet manifest from one validated version/repository/AppId source; use the standard's property override rules. Add `desktopShortcutTask=desktopicon` and baseline `2.0.0`. Verify every required schema field and inspect the actual packaged JAR and executable metadata. Do not rewrite already published v2.3.0 assets to retrofit the standard.

**Regression tests:** Deliberately mismatch each metadata surface; missing/invalid manifest fields; tag/version mismatch; invalid override; inspect the JAR inside the app image, not only generated source resources. Read back the registry and running About/diagnostics in an installed-build test.

### W-07 · P2 · Release reruns overwrite published assets

**Evidence:** `.github/workflows/windows-installer.yml`, existing-release branch: `gh release upload ... --clobber`.

**Scenario and consequence:** Re-running a tag build replaces an already published EXE/checksum/manifest in separate upload operations. The canonical standard requires immutable published releases. Clients can observe changing bytes under an unchanged version, or a temporarily mismatched triplet.

**Fix:** Distinguish a draft from a published release. For published assets, identical verified bytes are a no-op; differences fail and require a new version/tag. For drafts, validate size/hash of existing assets and the complete final set before publication. Explicitly check native command exit codes, rather than assuming shell success after a failed GitHub command.

**Regression tests:** Missing release; matching draft; mismatched draft; published matching triplet; published differing artifact; upload failure after the first asset. Do not mutate a real release in these tests.

### W-08 · P2 · Installer icon input does not meet required multi-resolution contract

**Evidence:** `build.gradle`, `generateWindowsInstallerIcon`; root `localdrop-icon.ico`. Inspection found exactly one 256×256 image. The generated fallback also constructs a single 256×256 PNG-in-ICO image.

**Consequence:** The required 16/32/48/64/128/256 layers are absent; the build also does not verify RT_GROUP_ICON/RT_ICON in the final executable. This is a standard-compliance failure, not evidence that the supplied screenshot has a missing or broken icon.

**Fix:** Package all required sizes from the approved artwork, pass the ICO to jpackage, and inspect/extract the main EXE icon resources in verification. Keep installer artwork and runtime icon identity consistent.

**Regression tests:** Missing layer; malformed ICO; wrong artwork; final EXE without group/icon resources; native extraction of 256×256 plus taskbar/shortcut/Explorer checks at relevant scales.

## 2. Potential Problems

### X-06 · P2 · A stalled writer has no effective idle deadline; accepted connections are insufficiently bounded

**Evidence:** `TransferClient.sendSingleFile` writes through blocking `OutputStream`; `Socket.setSoTimeout` configures reads, not a write watchdog. Android `TransferService` uses the same pattern. Windows `TransferServer` submits accepted sockets to a fixed pool with an unbounded work queue; Android launches one child for every accepted connection before admission.

**Trigger and consequence:** A receiver accepts but stops reading, or many peers hold partial headers. A sender can remain blocked beyond the advertised 30-second payload idle budget; queued open sockets consume resources and delay useful work. No malicious LAN participant is required to trigger a hung receiver.

**Fix:** Own and register sockets per operation. Enforce a bounded accepted/handshaking connection budget before scheduling; close rejected sockets. Implement an idle/progress watchdog that closes the actual stalled socket, including writes, and retain separate connect/header/ACK budgets. Do not impose a fixed 30-second total duration on a valid large transfer.

**Regression tests:** Stop reading after the buffer fills; drip a header; exceed admission limit; cancel during write; recover resources and readiness within a bounded time. Verify both platforms and a large slow-but-progressing transfer.

### X-07 · P2 · Committed files and uncertain acknowledgments are not represented as a durable partial result

**Evidence:** Both receivers finalize before FILE_ACK. `TransferClient.handleFailure`/`markRemaining` have no committed-receipt reconciliation; Android error snapshots drop completed counters. A new send creates a new session/file identity rather than resuming a previous transaction.

**Trigger and consequence:** The receiver saves a file, then its ACK is lost. The sender cannot know whether it committed. A manual resend can create a correctly renamed duplicate; failure of FINISH_ACK can occur after all files were saved. Windows may already have removed acknowledged items from its UI and does not preserve a batch result.

**Fix:** First provide honest outcomes: confirmed completed, failed/not sent, and delivery unknown. Keep acknowledged files out of retries. Do not blindly add automatic retry. If stronger deduplication is later required, design stable logical file IDs and durable committed receipts with payload identity and retention rules on both clients; never deduplicate solely by filename/size.

**Regression tests:** Disconnect immediately before and after FILE_ACK; lose only FINISH_ACK; fail file 2 of 3; retry deliberately. Preserve committed files, show uncertainty, and verify intentional repeated delivery is still allowed.

### X-08 · P2 · Multi-homed peers collapse to one last-seen endpoint

**Evidence:** `DiscoveryService.handlePacket` stores one `DeviceInfo` per device ID with `devices.put`; Android `updateTrackedDevice` does likewise. Both enumerate broadcast destinations but use OS-selected routes for unbound outgoing sockets.

**Trigger and consequence:** Wi-Fi/Ethernet/VPN exposes several addresses for one device. A more recent advertisement can replace a working address with one whose TCP path is blocked. The peer appears live/ready, but transfer attempts use only that endpoint. The screenshots do not establish that this happened.

**Fix:** Retain a bounded, expiring endpoint set per device; prefer a recently successful eligible endpoint, then try other fresh candidates within one connect budget. Resolve again immediately before connecting, particularly after Android snapshot preparation. Fallback must stop before any payload commitment unless a shared retry protocol supports more. Add interface/route evidence to diagnostics; do not blanket-disable VPNs or promise broadcast discovery across routed subnets.

**Regression tests:** Two simultaneous addresses with one TCP-blocked; same device ID after DHCP change; Wi-Fi ↔ Ethernet transition; VPN split/full tunnel; overlapping interface routes; never connect solely from a saved stale endpoint.

### W-09 · P2 · Windows target containment is lexical, not filesystem-aware

**Evidence:** `TransferServer.receiveSingleFile` checks normalized `startsWith(root)` and then follows existing directory components through `createDirectories` and stream creation.

**Trigger and consequence:** A junction/symlink inside the chosen receive root points outside it. A syntactically safe incoming subpath can write outside the intended root. This was identified statically; a live junction race was not exercised.

**Fix:** Define the supported receive-root/link policy. Reject escaping linked/reparse ancestors and verify the actual destination boundary before creating/publishing; use a private controlled staging area. Account for replacement races rather than claiming one `toRealPath` call makes an adversarial mutable tree safe.

**Regression tests:** Existing junction to an outside disposable folder; symlink where privileges permit; ancestor replacement during publication; legitimate nested directories. No writes outside the allowed destination.

### W-10 · P2 · Shutdown and single-instance fallbacks can leave a hidden or unresponsive process

**Evidence:** `LocalDropApp` sets implicit exit false, but its close handler only has a tray-installed branch. `TransferServer.stop` closes the listener and interrupts the executor, without owning/closing accepted sockets. `SingleInstanceService` uses a fixed loopback port and considers a successful one-byte connection sufficient activation.

**Trigger and consequence:** If tray installation fails, closing the last window can leave the process alive without a tray route. Blocking accepted I/O can outlive stop. A different process, or another Windows user/session using the same fixed port, can cause a new launch to exit without a usable local instance. These conditions require native lifecycle testing.

**Fix:** Make close-without-tray invoke a real exit path. On exit, close all owned active sockets, reconcile operations, and await bounded shutdown. Scope single-instance identity to the intended Windows user/session and verify an application/version activation response; a bind collision alone is not an existing LocalDrop instance. Keep one restore path and the already fixed iconification ordering.

**Regression tests:** No tray support/install failure; exit while waiting for payload/write/ACK; second instance during shutdown; unrelated port owner; two Windows user sessions; installer-requested graceful close while receiving.

### W-11 · P2 · Persistent listener failure has no recovery path and may spin

**Evidence:** `DiscoveryService.listenLoop` catches `SocketException` and immediately retries the same socket. `MainController.serviceStartupScheduled` remains set after initial startup failure; refresh schedules broadcasts but does not rebuild a failed service.

**Trigger and consequence:** A permanently invalid UDP socket can repeatedly throw/log without backoff. If startup cannot bind, freeing the port and clicking Refresh need not recover the service. Android's UDP listener already has a bounded retry loop; do not regress it while aligning behavior.

**Fix:** Separate transient packet errors from listener failure. Close/rebind with bounded backoff, maintain one owner/generation, and expose recovery status. Give startup failures an explicit retry path. A refresh burst is not listener recovery.

**Regression tests:** Occupied UDP port then release; repeated terminal socket exceptions with bounded CPU/log rate; stop during backoff; one recovered listener; no duplicate schedulers.

### W-12 · P2 · Configuration writes are nontransactional and corrupt input is overwritten

**Evidence:** `ConfigService.save/load`; `JsonUtils.writePretty` calls `Files.writeString` directly on the persistent config. A parse failure creates defaults, then `load` saves them over the unreadable file.

**Trigger and consequence:** Interrupted write or transient storage failure can lose the device ID, selected receive folder, language and bounds. A partially damaged configuration is not retained for recovery. This can also undermine future stable device trust if identity is added to this file unchanged.

**Fix:** Serialize to a private sibling staging file, flush/close and safely replace the configuration with recovery/backup handling; serialize writers. Preserve unreadable original bytes under a diagnostic recovery name and distinguish I/O failure from invalid content. Keep identity secrets separate in the proposed trust feature.

**Regression tests:** Fault injection at serialization/write/replace; malformed/truncated JSON; concurrent saves; failed migration; existing configuration retained after failed update.

### W-15 · P2 · Same-size source edits can escape the sender's consistency checks

**Evidence:** `TransferClient.captureSourceMetadata`, the pre-file size check around line 143, and `streamFile` around line 240. The latter checks extra bytes and current path size; it does not establish immutable source content. Current ACK fields do not demonstrate an end-to-end computed digest.

**Trigger and consequence:** Another application modifies/replaces a queued source while it is streamed without changing its length. The receiver can get a mixed or different byte sequence with valid size and a successful ACK. This is a conditional source-consistency risk, not evidence of corruption of the original source or a claim that v2 promises cryptographic checksums.

**Fix:** Define the source consistency guarantee. At minimum detect changed file identity/metadata and report uncertainty; metadata comparison alone cannot prove unchanged bytes. If byte-stable sending is required, use a bounded snapshot or an appropriately protected read handle on supported Windows filesystems. A digest of the already mixed stream alone does not make it a snapshot of the intended original. Do not introduce unbounded 100-GiB batch copying by default.

**Regression tests:** Same-size in-place edit, source replacement, size growth/shrink and timestamp-only change during send; either transfer a defined stable version or fail honestly. Android's existing source snapshots should remain intact.

## 3. Suboptimal Solutions

### W-13 · P3 · Some displayed metadata describes a guess rather than the actual object

**Evidence:** `MainController.attachStage` calls `FileUtils.detectNetworkName` once; that method chooses the first eligible adapter. `TransferServer` reports original relative paths for recent files even after collision renaming.

**Scenario and consequence:** The screenshot's WAN Miniport string can be unrelated to the route used for transfer and becomes stale after network changes. A received `name (1).txt` can be displayed as `name.txt`. This makes troubleshooting/file lookup harder without proving a network failure.

**Fix:** Show a concise neutral LAN/discovery state on the main screen; put current interfaces/addresses in diagnostics or show the actual selected connection endpoint. Use the committed output name/path for recent-file UI.

**Regression tests:** Change adapters during runtime; zero/multiple eligible adapters; repeated filename collision; actual output opened from recent history.

### W-14 · P3 · Packaging input can retain stale dependency files

**Evidence:** `build.gradle`, `prepareJpackageInput`, uses `Copy` for a reusable input directory.

**Scenario and consequence:** Removing/upgrading a dependency does not necessarily remove its old JAR from an existing input directory. A developer's incremental app image can differ from a clean CI image and contain stale duplicate libraries.

**Fix:** Use an exact synchronized packaging input or an owned clean directory, then verify the packaged classpath/file set. Do not delete broad project/output roots.

**Regression tests:** Package dependency version A, switch to B/remove it, package without clean; assert A is absent and the output matches a clean build's logical dependency set.

## Installer / AppFleet acceptance matrix

This matrix applies the local standard, not assumptions about AppFleet internals.

| Requirement | Assessment |
| --- | --- |
| Stable AppId `a96d2beb-644f-4461-aaea-cc00c58c3917` | PASS, source consistency across current Gradle/Inno/manifest. |
| Per-user, lowest privilege, x64-compatible install | PASS, source configuration. Runtime elevation behavior NOT TESTED. |
| Preserve previous directory and tasks | PASS, Inno declarations. Upgrade persistence NOT TESTED. |
| Exact EXE + SHA-256 + manifest | PASS, regenerated local set and hash. |
| Current manifest baseline / shortcut task | FAIL for next-standardized-release requirements; W-06. Do not rewrite historical release. |
| Single version/metadata source and packaged read-back | FAIL, W-06. |
| Required multi-size ICO and native EXE verification | FAIL/incomplete, W-08. |
| All required HKCU application string values | PASS, source declarations; installed registry read-back NOT TESTED. |
| Start Menu / optional `desktopicon`; no silent auto-launch | PASS, source declarations. Native shortcut verification NOT TESTED. |
| Removal limited to app-owned installed payload | PASS, current `[InstallDelete]` targets are the app image's known entries. |
| User configuration outside installation; copy-only old-path migration | PASS in code/tests. Actual install/upgrade/uninstall preservation NOT TESTED. |
| Known legacy MSI UpgradeCode migration | Present in code; success/error/reboot-required/live-upgrade branches NOT TESTED. |
| Graceful close and locked-file failure with truthful exit/registry | NOT TESTED; W-10 is a related runtime risk. `CloseApplications=yes` alone is not end-to-end proof. |
| Immutable published release and exact draft checks | FAIL, W-07. |
| AppFleet detects update success only after actual successful install | NOT TESTED. No claim about AppFleet's own implementation. |

Before approving a release: use disposable Windows profiles/VMs for clean install; previous Inno upgrade; previous MSI migration; nondefault folder; custom receive folder/language; shortcut on/off; app running/minimized/receiving; locked files; canceled install; insufficient space; reboot-required migration; uninstall. Capture installer exit code and read back EXE/JAR version, application registry values, shortcut target/icon, process termination and preserved configuration. A failed install must not be accepted merely because a new Version registry value exists. Do not force-kill a receiver to make an update look successful.

## Implementation order and exit criteria

1. X-01, W-01, W-02 and Android publication safety: protect user bytes before adding features.
2. X-02 through X-05: define shared negative vectors and make both clients agree.
3. W-03/W-04 and Android operation ownership: make error/partial outcomes actionable.
4. Bound network I/O/lifecycle and verify actual LAN failure cases.
5. Correct the next release's standard contract; execute install/upgrade tests.
6. Apply the focused UI/sizing proposal. Device trust remains a separately approved future implementation.

Existing passing tests are a baseline, not proof of these failure paths. Done means the proposed regression cases pass, existing user files retain their bytes, both platform validators agree, and relevant manual checks have real evidence. Keep unsupported providers/network environments explicitly NOT TESTED.

### Historical confidence and Skills used

Git records establish the changes described above, but not every original incident or reason for abandoning pairing/MSI. The supplied screenshots are not simultaneous network captures. `PROJECT_LESSONS.md` describes staging/cleanup as a working solution; current code and probes show why those broad safety conclusions must not be treated as verification.

Applied Skills: `core-safe-file-transactions`, `net-versioned-protocol`, `net-lan-discovery`, `net-timeout-retry-budget`, `core-idempotency-concurrency`, `windows-desktop-lifecycle`, `windows-installer-update`, `core-release-verification`, and `docs-and-readme-writer`. They informed failure-path checks and evidence grading, not a demand to redesign working components or reintroduce pairing into v2.
