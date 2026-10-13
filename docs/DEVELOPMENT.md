# Development continuation

## 2026-10-13 — 0.4.0-beta.1 Discord batch

Implemented:
- Owner-only Discord settings with fresh-passkey verification, CSRF and optimistic revision checks.
- AES-GCM token storage in a private profile directory, masked API output, replacement/deletion and opt-in connection.
- Outbound Gateway integration via JDA 6.7; `/mc start` and `/mc status`, role-scoped server autocomplete and fresh membership verification.
- Guild/role/instance/action permissions, deny by default, no console/admin escalation, idempotency and start cooldown.
- Server exit/error alerts, requested vs unexpected exits, per-server error coalescing and disabled mentions. No raw log forwarding.
- BETA semantic version validation/comparison in app and bootstrap, prerelease publishing, stable-channel isolation.
- Earlier runtime log parsing/file picker and input/render performance improvements are included in this first BETA batch.

Validation:
- Backend: 37 tests passed (Discord storage/access/command/aggregation and BETA comparison included).
- Browser: 6 tests passed including Discord settings drafts/token removal and large-log/input performance.
- Actual authenticated API + production CSP browser test passed against candidate and final BETA JAR: owner settings, encrypted on-disk token, CSRF, viewer denial.
- BETA bootstrap signed upgrade, tamper rejection, rollback and profile selection passed.
- Universal macOS PKG built; bundled-Java launcher passed with isolated data/config.
- Real Discord connection/role commands/message delivery need the owner's bot token; none was available. No test messages were sent to friends.

Local application status and recovery gates are recorded separately in the ignored `LOCAL-TEST-RESULTS.md`; inspect that file before replacing a running local app.

Remaining scheduled work:
1. Player-free shutdown: opt-in per instance, 30–60 minutes continuously empty, unknown count resets timer, reconnect/start/error/diagnostic safe handling, graceful save/stop and Discord/UI status.
2. Modrinth link download, compatibility and required-dependency verification, dedicated mod management page.
3. Dependency-preserving binary conflict search for selected suspects or all candidates, manual/automatic judgments, history/pause/restore and isolated test worlds.
4. Continue UI improvements backed by evidence. Keep no-new-log parsing skip, runtime-only worker parsing and 500ms overlay rules.

Publish only validated batches as BETA prereleases. The user explicitly authorized this scope's BETA publishing and local application. Default application is graceful Minecraft stop first, no forced stop. Preserve accounts, proxy credentials, settings and worlds. Record actual release/application outcome below.

## 0.4.0-beta.2 — automatic-update follow-up

- Discord server control now uses the same maintenance read lock as web mutations; it refuses commands once the updater enters its exclusive restart phase. Regression assertion included in DiscordTest.
- `scripts/test-update-protocol.py` exercises the actual scheduled update check, GitHub downloads, signature/hash verification, bootstrap application and passkey login with isolated profile data. Only its initial fixture version metadata is lowered; updater and release public key are unchanged.
- The BETA 1 real-GitHub integration run passed in about 80 seconds, including unchanged account/config files and login with the same passkey after restart.
- Production was moved to a persistent installed-app supervisor with unchanged account/proxy/config data; local deployment details remain in LOCAL-TEST-RESULTS.md.

## 0.4.0-beta.4 — runner discovery and navigation

- Added a dedicated Server runners sidebar entry with per-platform downloads, passkey enrollment steps, manager URL, and an explicit empty state. Invited users retain access to enrollment.
- Moved the execution-location selector to the main server screen, with a direct registration link. Assignment remains restricted to owner/instance ADMIN and disabled until the server is fully stopped.
- Clicking a server from account/security or runner settings now opens that server's console. The sidebar version uses the actual session version.
- Fresh interactive runner installations continue directly into pairing; entering the manager address opens `/#runners`. Existing background standby remains noninteractive, and unattended installation can skip pairing.
- Added browser regression tests for empty runner discovery, invited-user enrollment/consent/assignment, running-server assignment protection, viewer separation, and global-settings navigation.
- Validation: 47 backend tests, the existing 6 performance/log/Discord browser tests plus 3 runner/navigation tests passed. Signed bootstrap upgrade/tamper/rollback checks passed. Packaged Mac runner now confirms a visible UTF-8 enrollment prompt, no stored identity after failed enrollment, and unattended standby. Windows package contents were checked; actual Windows execution remains untested.
- The user's game SRV hostname is `playground.enclody.sarl`, targeting external TCP 6399. The existing instance is to use port 6399. Game DNS display/configuration is a remaining UI follow-up; the web origin is distinct from the game endpoint.


## 0.4.0-beta.5 — launcher types and shared-port game domains

- Added official Vanilla, Paper, Fabric, Forge and NeoForge catalogs/installers with Minecraft compatibility, bounded allowlisted HTTPS downloads, official hashes/sizes, Java requirement verification and runtime metadata transferred with the server. Existing Instance JSON schema and legacy NeoForge 1.21.1 remain compatible. mrpack supports NeoForge/Forge/Fabric.
- Added isolated create-dialog state, type/version selectors, direct-input fallback and stale-response guards. Re-selecting the same value no longer clears the selected version.
- Added owner-only Game addresses / TCP relay settings, fresh-passkey/CSRF/revision guards, private AES-GCM Cloudflare token storage with masked responses and replacement/removal, instance ADMIN domain labels and authenticated address views. DNS only per-server A plus _minecraft._tcp SRV uses each server's own hostname as target, protecting routing even when a client sends its SRV target. Foreign records are preserved; only installation-owned stale A/SRV records are pruned.
- Shared relay reads a bounded first Minecraft handshake and selects a runner by exact configured hostname. Forge/FML bytes are preserved and subsequent traffic is opaque. Unknown hosts are rejected. A single configurable central TCP port serves different runner computers; stopping one instance closes only that instance's streams. Legacy per-port mode remains available; shared mode requires an enrolled standalone runner.
- Real integration testing found and fixed two earlier runner defects: MVC wildcard DELETE claimed GET /api/runners/connect (405), and periodic resource reports marked unready Minecraft processes RUNNING. Runner deletion now matches UUIDs only and readiness follows the actual Done log. Busy internal ports fail before spawning and safely release only the matching pre-start lease.
- The user narrowed same-host multiple Minecraft process expansion to future work: GitHub issue #1. Do not expand that scope automatically.

Validation completed:
- 59 backend tests passed; the opt-in real-launcher suite is skipped in ordinary runs. Its separate enabled run passed all five official installs, actual RUNNING/list/normal save-stop in fresh flat worlds.
- 11 browser regression tests passed including all five software choices, network token masking/domain editing, runner enrollment/navigation/permission UI and 7,000-line log/input performance.
- scripts/test-runners-live.sh passed against an isolated HTTPS manager, actual passkey registration and two actual signed WSS runner JVMs. Vanilla and Fabric each launched a new flat world, direct and shared-port status returned distinct MOTDs, unknown host rejection and stop isolation held, busy-port recovery and console command worked, and remote file/world handoff completed after normal stop. This simulates separate runner computers with separate processes/profiles on one Mac; no production accounts/worlds or Discord credentials are used.
- Final Mac manager/runner package smoke tests passed. Bootstrap signed BETA upgrade, tamper rejection, startup rollback/profile selection passed; release signature matches pinned public key and all asset hashes match. Windows command layouts and package are validated; actual Windows execution remains untested.
- Actual Cloudflare DNS edits require the owner's token/zone and were not performed; provider ownership/upsert/cleanup tests use a fake API. Read-only nginx inspection confirms existing HTTP/1.1 and Upgrade/Connection directives. Existing web routing was not changed.

Release/local application details and protected-file checks are recorded in ignored LOCAL-TEST-RESULTS.md. Continue prior Modrinth, conflict-search and idle-shutdown work after this validated release batch; do not repeat completed DNS/runner work.


## 0.4.0-beta.6 — player-free graceful shutdown

- Per-instance opt-in OFF/30-minute defaults, 30–60-minute settings, instance ADMIN writes/VIEWER reads using existing CSRF/permission guards. Policy and decision history are separate from Instance JSON, preserving old worker deserialization. Recent stop cause survives app restart; timers intentionally restart from zero.
- Contiguous distinct fresh zero samples drive a monotonic timer. Nonzero/unknown/stale/gap/PID/device/activity changes, STARTING/UNKNOWN and diagnostic flags reset continuity. Local and remote final checks query the actual executing computer; join activity during the check aborts it. Diagnostics/settings cannot race a pending final check.
- New correlated idle-stop/idle-result protocol requires worker >=beta.6, checks selected device plus request ID, refuses old/unready/unknown/occupied states and never falls back to an unchecked or forced stop. Starts still require normal Discord/web permissions and pending data/lease guards. UI and Discord panel/status include settings/countdown/cause; idle stop alerts distinguish their normal reason.
- Actual Vanilla test exposed an older MinecraftQuery defect: challenge identifiers pass through UTF-8 formatting in official QueryThreadGs4$RequestChallenge. Arbitrary random high-bit identifiers corrupt their echo; low-nibble identifiers and connected loopback UDP fix actual player counts. Regression responder now mimics official behavior.
- Validation: 67 ordinary backend tests passed; five additional opt-in official launcher cases passed separately; 12 browser tests passed including opt-in/range/running-server drafts and existing 7,000-line log/worker loading rules. Worker final check uses mock process plus actual loopback UDP for zero/occupied/unknown/join/unready cases and verifies no destroyForcibly call. Clock simulation verifies full 30-minute continuity without waiting in real time.
- Real isolated HTTPS/passkey/two signed WSS worker integration passed in 1.5m: actual Vanilla/Fabric empty counts and EMPTY countdown, invalid duration rejection, OFF change, shared TCP isolation, console, normal world save/file handoff. Windows runtime remains untested on Windows; no real Discord test messages sent.
- Claude's beta.4 review patch was not merged: default forced shutdown and partial-line flush regression remain review findings. Its enum/locking changes are not part of this batch.
- Release/application results remain in LOCAL-TEST-RESULTS.md. Next scheduled scope is Modrinth link install plus dedicated mod management, then dependency-preserving isolated conflict search. Do not repeat finished Discord/runner/DNS/idle features. Same-host simultaneous servers remain issue #1 future-only.

Final freshness validation: uncached basic-stat is used before stop; all five actual official launchers verified both basic and full empty counts after their Query listener became available.

## 0.4.0-beta.7 candidate — separate friend runner installers

- The latest human request narrows installer separation to friend-side runners; manager PKG remains unchanged. The existing CLI asset names remain compatible; separate `ServerManager-Runner-UI-<version>-<platform>.zip` assets provide a Mac installer app / Windows Setup.cmd opening a native Swing wizard.
- Shared Java installer: bundled Java, HTTPS-origin validation, masked one-time-code entry via stdin only, optional login standby, staged copies with rollback, active supervisor lock rejection, preserved identity/world profile and retry after registration failure. Windows uses javaw and a limited per-user task; Mac uses a per-user LaunchAgent. Custom runner home persists for scheduled tasks. No forced game termination.
- Web runner settings recommend the UI downloads and expose CLI downloads/options separately. Both variants use the same runner identity and signed JAR updater. OS Developer ID/Authenticode signing remains unavailable; no claim of notarization.
- Validated so far: 70 backend tests (1 opt-in suite skipped), 13 browser checks (one timing-sensitive log overlay case passed in an isolated rerun after concurrent Minecraft startup load), shared setup filesystem/process tests, rendered installer controls, actual isolated Mac LaunchAgent ON/standby/active-reinstall rejection/OFF, packaged manager/runner startup, signed bootstrap upgrade/tamper/rollback. A Windows CI matrix also tests the shared engine and actual limited logon-task registration/removal; inspect its result before claiming Windows execution tested.
- User explicitly chose direct execution from the original existing folder. Added local-only per-instance path grants in the installer/CLI, optional basename-only JAR and Java requirement, server ID discovery in the web UI, NEW-profile remote start without reinstall, first-run local authority, world-lock/symlink/overlap/dirty-transfer guards, portable runtime entryJar metadata, same-volume staged pulls/backups at the original path, and external-edit fingerprint conflict refusal. No manager API accepts an arbitrary remote filesystem path. Three additional backend tests passed; actual HTTPS/passkey/two signed WSS runner integration passed in 2.7m: existing folder/custom Vanilla JAR direct execution, original-path restart and same-volume backup, central-edit pull, original marker preservation, custom JAR/world migration to the other normal runner, shared TCP isolation, busy-port recovery and normal save/stop. Test fixture uses canonical macOS paths; a raw /tmp alias was deliberately rejected in the first fixture and corrected. Modrinth compatibility remains pending after this batch.
- Release/local apply results are recorded in LOCAL-TEST-RESULTS.md. Do not claim release/application completed until verified.

Windows CI found and fixed a real first-install/OFF idempotency bug: a missing Get-ScheduledTask left PowerShell with failure status even though OFF was already achieved. Setup now exits successfully after completed operations; exceptions still fail. Isolated Windows task registration, limited user, windowless bundled Java, login trigger, removal and repeated OFF are verified in the CI matrix. Final release awaits the final source CI result.
