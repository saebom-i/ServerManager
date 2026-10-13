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
