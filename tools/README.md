# Developer tools

Scripts that are not part of the app build. Each is safe to run directly.

## `device_test.sh`

Full on-device verification loop: JVM unit tests, then instrumentation tests on a headless
API 34 emulator, with an optional install-and-launch smoke test.

```bash
tools/device_test.sh            # unit + instrumentation tests
tools/device_test.sh --install  # also build, install, launch, screenshot
```

Prerequisites (one-time):

```bash
sdkmanager --install 'system-images;android-34;google_apis;arm64-v8a'
echo no | avdmanager create avd -n edge_test \
  -k 'system-images;android-34;google_apis;arm64-v8a' -d pixel_7
```

The emulator is started headless (`-no-window`), so this is CI-safe.

### The hosts file

This machine's DNS does not resolve for libc callers, which breaks the JVM inside Gradle.
`/tmp/gradle-hosts.txt` supplies static mappings and is passed via
`JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=...`. Add a line when a new host is needed:

```bash
printf '%s api.typesafe.ai\n' 104.18.24.46 >> /tmp/gradle-hosts.txt
```

## `probe_mcp.sh`

Performs a real MCP JSON-RPC `initialize` handshake against a remote endpoint, then reports
whether it speaks MCP. A bare `GET` returning 405 only proves an HTTP server exists; this
proves the protocol.

```bash
tools/probe_mcp.sh https://mcp.deepwiki.com/mcp
# -> mcp.deepwiki.com -> MCP_OK http=200 server=DeepWiki
```

## `verify_sandbox.js`

Runs the bundled skill scripts (`calculator.js`, `device_info.js`, `web_extract.js`) in Node
with a mock bridge, asserting that hostile inputs cannot escape the expression grammar or the
host document.

```bash
node tools/verify_sandbox.js
```

## `secrets.properties` (repo root, git-ignored)

Build-time credentials. Read by `app/build.gradle.kts` and compiled into `BuildConfig`,
so they ship inside the APK. Copy `secrets.properties.example` and fill it in. Prefer
per-app revocable keys, never a shared production credential.
