# maestro-logcat-sidecar

A lightweight HTTP sidecar that lets [Maestro](https://maestro.mobile.dev) UI test flows assert against Android `adb logcat` output.

Maestro drives an app on an Android emulator. This sidecar captures logcat in the background and exposes a REST API so flow scripts can check whether expected log lines appeared — without any polling or timing hacks.

## Prerequisites

- **Java 17+** on `PATH`
- **`adb`** on `PATH` — install [Android SDK platform-tools](https://developer.android.com/tools/releases/platform-tools) and add the directory to `PATH`. Maestro uses its own internal `dadb` library and does not provide `adb`.

  ```bash
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
  ```

## Quick start

### Using a released JAR

Download the fat JAR from [Releases](../../releases) and run it directly:

```bash
java -jar maestro-logcat-sidecar-1.0.0.jar
```

### Building from source

```bash
mvn package -DskipTests
java -jar target/maestro-logcat-sidecar-1.0-SNAPSHOT.jar
```

Or let the scripts do it:

```bash
# Before your test run — builds fat JAR, starts in background, polls /health
./scripts/start.sh

# After your test run
./scripts/stop.sh
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `17777` | HTTP port the sidecar listens on |
| `LOGCAT_TAGS` | `*:D *:S` | Tag filter passed to `adb logcat`. Space-separated. |

To capture only a specific app tag and suppress everything else:

```bash
export LOGCAT_TAGS="MyApp:* *:S"
./scripts/start.sh
```

## API

### `POST /session/start`

Clears the logcat buffer. Call this at the beginning of each test flow.

```json
{ "status": "started", "timestamp": "2024-01-01T00:00:00Z" }
```

### `GET /assert?pattern=<regex>`

Scans the buffer immediately (no blocking). Returns all lines matching the Java regex.

**200 OK** — one or more lines matched:
```json
{ "lines": ["I/MyApp: connected to server"] }
```

**404 Not Found** — no lines matched (no body).

**400 Bad Request** — missing or invalid pattern:
```json
{ "error": "pattern is required" }
```

Patterns use Java's `Pattern.find()` — substring match, case-sensitive by default. Use `(?i)` for case-insensitive matching.

| Pattern | Matches |
|---------|---------|
| `connected` | Any line containing "connected" |
| `(?i)tls handshake` | Case-insensitive TLS handshake messages |
| `MyApp.*error` | Lines from MyApp tag containing "error" |

### `GET /health`

Liveness probe. Always returns `200 { "status": "ok" }`. Used by `start.sh` to poll for readiness.

## Maestro integration

Copy the scripts from `maestro-scripts/` into your test project alongside your flow files. Call them in this order:

```yaml
appId: com.example.myapp
onFlowStart:
    - runScript: scripts/checkHealth.js
---

- runScript: scripts/startSession.js

# … UI actions …

- runScript:
    file: scripts/checkForLogs.js
    env:
      PATTERN: 'MyApp.*connected'
```

| Script | Purpose |
|--------|---------|
| `checkHealth.js` | Verifies the sidecar is reachable (3 retries, 500 ms delay). Run first so a misconfigured environment fails fast. |
| `startSession.js` | Calls `POST /session/start` to clear the buffer. Sets `output.timestamp`. |
| `checkForLogs.js` | Calls `GET /assert` with `PATTERN`. Throws if no match. Sets `output.matchedLines`. |

Matched lines are available in subsequent steps as `${checkForLogs.output.matchedLines}`.

## How it works

`LogcatBuffer` spawns `adb logcat` as a subprocess and reads its stdout into a fixed-capacity circular buffer (10,000 lines). When `GET /assert` is called, it scans the buffer at that instant — no retry, no wait. Android's logging subsystem is synchronous from the app's perspective, so by the time a Maestro flow step calls `/assert`, the relevant lines are already in the buffer.

## License

Apache 2.0
