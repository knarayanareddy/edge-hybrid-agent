# Edge Hybrid Agent (Android)

An intelligent on-device and cloud hybrid agent architecture for Android (optimized for Snapdragon 8 Gen 2 / Samsung Galaxy S23 Ultra and modern Android devices).

## Highlights & Features

1. **Dual-Tier Hybrid Inference**:
   - **Local Tier**: MediaPipe / LiteRT on-device inference harness for zero-latency, offline execution.
   - **Cloud Tier**: High-throughput SSE streaming inference engine connecting via OpenRouter, Anthropic, or direct Gemini API.
2. **TypeSafe JEV (System 1) Intelligent Routing & Guardrails**:
   - Pre-dispatch classification: evaluates prompt complexity, semantic risk, and token budget in under 50ms.
   - Safety guardrail: blocks destructive tool actions or data loss triggers before execution.
   - Post-generation reflection: detects hallucinations, unresolved TODOs, and false positives.
3. **Autonomous Self-Correcting Learning Ledger (On-Device Room DB)**:
   - Stores learned rules and failure patterns in a persistent SQLite database (`lessons_ledger`).
   - Automatically injects known constraints into subsequent prompts so the model never repeats mistakes.
4. **Extensible Tooling Ecosystem**:
   - **SKILL.md Bundles**: Parses Antigravity / Hermetic skill manifests and registers callable tools dynamically.
   - **Model Context Protocol (MCP)**: JSON-RPC 2.0 client for connecting to remote or local MCP tool servers.
5. **Modern Android Jetpack Compose UI**:
   - Dark/Light Material 3 adaptive UI.
   - Real-time token streaming with live cursor indicators.
   - Latency, token count, and active model telemetry.
   - EncryptedSharedPreferences (AES-256 GCM) for secure on-device credential storage.

## Project Structure

```
edge-hybrid-agent/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/edgehybrid/agent/
│   │       ├── EdgeHybridApp.kt
│   │       ├── MainActivity.kt
│   │       ├── core/
│   │       │   ├── inference/
│   │       │   │   ├── InferenceEngine.kt
│   │       │   │   └── CloudInferenceEngine.kt
│   │       │   ├── jev/
│   │       │   │   ├── JevClient.kt
│   │       │   │   └── JevDispatcher.kt
│   │       │   ├── mcp/
│   │       │   │   └── McpClient.kt
│   │       │   └── tools/
│   │       │       └── SkillLoader.kt
│   │       ├── data/
│   │       │   └── local/
│   │       │       ├── SecureKeyStore.kt
│   │       │       ├── ChatEntities.kt
│   │       │       ├── ChatDao.kt
│   │       │       └── ChatDatabase.kt
│   │       ├── di/
│   │       │   └── DatabaseModule.kt
│   │       └── ui/
│   │           ├── theme/Theme.kt
│   │           ├── chat/{ChatScreen.kt, ChatViewModel.kt}
│   │           ├── settings/SettingsScreen.kt
│   │           └── skills/SkillsScreen.kt
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Getting Started

1. Open this directory in **Android Studio Hedgehog / Ladybug** or newer.
2. Build and run on a connected Android device running API 28+ (Android 9.0+) or an emulator.
3. In the Settings tab, enter your OpenRouter or Gemini API Key and TypeSafe JEV key.
