# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

- **Build:** `mvn package`
- **Build (skip tests):** `mvn -q package -DskipTests`
- **Test:** `mvn test`
- **Verify (tests + verify phase):** `mvn verify`
- **Start server:** `./scripts/start.sh` (builds fat JAR, starts in background, polls `/health`)
- **Stop server:** `./scripts/stop.sh`

No linting is configured.

## Architecture

This is a lightweight HTTP sidecar server that captures Android `adb logcat` output and exposes REST endpoints so Maestro test flows can assert on application logs.

**Main components** (all in `src/main/java/org/igniterealtime/logcat/`):

- **`LogcatBuffer`** — Thread-safe circular buffer (10,000 lines, `ReentrantLock`). Spawns an `adb logcat` subprocess and reads its stdout into the queue. Provides `findMatchingLines(String regex)` using `Pattern.find()` (substring match, not full-line). Tag filter is configurable via the `LOGCAT_TAGS` env var.

- **`SidecarServer`** — Javalin HTTP server on port 17777 (configurable via `PORT` env var). Three endpoints:
  - `POST /session/start` — clears the buffer, returns timestamp
  - `GET /assert?pattern=<regex>` — searches buffer, returns matches or 404
  - `GET /health` — liveness probe

- **`Main`** — Entry point; wires the two components together, reads env config, registers a shutdown hook.

**Data flow:** `adb logcat` process → `LogcatBuffer` → Javalin endpoints → HTTP clients (Maestro flows)

**Maestro integration scripts** live in `flows/scripts/` (JS): `startSession.js`, `checkForLogs.js`, `checkHealth.js`. See `flows/README.md` for usage.

## Key details

- Java 17, Maven, fat JAR via Maven Shade plugin (main class: `net.fishbowler.logcat.Main`)
- Group ID `net.fishbowler`, artifact ID `maestro-logcat-sidecar`
- `adb` must be on `PATH` at runtime; the server fails fast with a clear error if it isn't
- Regex matching uses Java's `Pattern.find()` — substring match, case-sensitive
- Default `LOGCAT_TAGS` is `*:D *:S` (debug and above for all tags)
- CI runs `mvn verify` on push/PR; releases are triggered by `v*` tags

## Coding standards

- All public classes and methods must have Javadoc
- Use `java.util.logging` — no SLF4J, no Log4j
- No checked exceptions in public API; wrap in `RuntimeException` where needed

## Testing

`LogcatBufferTest` uses reflection to call the private `appendLine` method directly, so tests never need a live `adb` process. Use the same pattern for any new `LogcatBuffer` tests.

## Releasing

Push a `v`-prefixed tag (e.g. `v1.2.0`). The `release.yml` workflow runs `mvn versions:set` to match the tag, builds the fat JAR, and attaches it as a GitHub Release asset named `maestro-logcat-sidecar-1.2.0.jar`.
