# Yggdrasil Mobile

[Yggdrasil](https://github.com/SebbeJohansson/yggdrasil), rebuilt as an
Android app that runs only on the phone. No server, no PC.

The model runs on the phone too: Gemma 4 through
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Claude can be
added as a second option, through Claude Code running on the phone
([docs/claude-bridge.md](docs/claude-bridge.md)).

## Parts

The parts keep their Yggdrasil names and jobs. On the phone they are
packages in one app, not processes talking HTTP.

| Part | On the phone | Status |
| --- | --- | --- |
| **Norn** | chat, memory, the front desk | chat with Gemma 4 |
| **Gjallar** | alerts, as Android notifications | planned |
| **Hermóðr** | messages to you, as notifications | planned |
| **Dellingr** | scheduled jobs, on WorkManager | planned |
| **Huginn** | reacts to what just happened (incoming notifications) | planned |
| **Veðrfölnir** | the phone itself: battery, storage, heat, network | planned |

Sindri, Heimdall, Munin and Ratatoskr are desktop jobs and have no phone part.

## Models: `llm/`

Parts never pick a model. They make an `LlmRequest` with:

- a **tier**: `SMALL` (names, classifying, one-liners) or `LARGE` (chat, briefs)
- a **privacy** level: `ON_DEVICE_ONLY` (the default) or `MAY_LEAVE_DEVICE`

`LlmRouter` goes through the providers in order and uses the first one that
is allowed, fits the tier, and is ready. When none can answer,
`NoProviderException` says why for each.

| Provider | Where | Status |
| --- | --- | --- |
| `LiteRtLmProvider` | on the phone, GPU with CPU fallback | working: Gemma 4 E2B (default), E4B |
| `ClaudeBridgeProvider` | Claude Code CLI on the phone, your subscription | app side done; bridge not written |

`LiteRtLmProvider` keeps the last conversation open, so a follow-up
message doesn't feed the whole chat to the model again.

## Run it

1. Build and install:

   ```bash
   ./gradlew :app:installDebug
   ```

   Or download the APK from the latest `build` run in GitHub Actions.

2. Open the app and tap **Download model**. Gemma 4 E2B is 2.6 GB, downloaded
   from Hugging Face and checked against its SHA-256. A stopped download
   continues where it left off, but only while the app is open.

   Or push a file you already have:

   ```bash
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/io.github.sebbejohansson.yggdrasil/files/models/
   ```

## Develop

```bash
./gradlew :app:testDebugUnitTest   # router and conversation-reuse tests
./gradlew :app:assembleDebug
```

Needs JDK 21 and the Android SDK with `platforms;android-37.2`.
