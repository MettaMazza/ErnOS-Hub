# ErnOS Mobile Hub — Milestone 1: Android Project + Local Inference

A native Android app that runs Qwen 3.5 (or any GGUF-format model) fully
on-device using llama.cpp compiled via the NDK.

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android NDK | r27 or newer |
| CMake | 3.22.1+ |
| Gradle | 8.9 (downloaded automatically by wrapper) |
| JDK | 17+ |
| Device / Emulator | arm64-v8a, Android 8.0 (API 26)+ |

## One-time Setup

### 1. Initialise the llama.cpp submodule

```bash
cd android
git submodule update --init --recursive
# Submodule path: app/src/main/cpp/llama.cpp
```

### 3. Configure local Android SDK path

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
# e.g. sdk.dir=/Users/you/Library/Android/sdk
```

### 4. Obtain a Qwen 3.5 GGUF model

Download a quantised Qwen 3.5 GGUF (Q4_K_M recommended for most devices):

```
https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF
```

Copy the `.gguf` file to the device:

```bash
adb push qwen2.5-3b-instruct-q4_k_m.gguf /sdcard/Download/model.gguf
```

## Build & Run

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ernos.mobile/.MainActivity
```

## Project Structure

```
android/
├── app/
│   ├── build.gradle.kts           # App module build config (NDK, Compose, Room)
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions: INTERNET, STORAGE, BLUETOOTH, FOREGROUND_SERVICE
│       ├── cpp/
│       │   ├── CMakeLists.txt     # NDK build: llama.cpp subdirectory + llama_bridge
│       │   └── llama_bridge.cpp   # Full JNI: initModel, freeModel, tokenize, decode, streamGenerate
│       └── kotlin/com/ernos/mobile/
│           ├── LlamaRuntime.kt    # Kotlin wrapper — Flow<String> streaming tokens
│           ├── ChatViewModel.kt   # AndroidViewModel managing model state + generation
│           ├── ChatScreen.kt      # Compose UI: TopAppBar, message list, streaming input
│           ├── MainActivity.kt    # Entry point
│           ├── ErnOSApplication.kt
│           └── theme/Theme.kt     # Material3 dark/light + dynamic colour
├── build.gradle.kts               # Root project plugin declarations
├── settings.gradle.kts            # Module include + repository config
├── gradle/
│   ├── libs.versions.toml         # Version catalog (AGP, Kotlin, Compose BOM, Room, OkHttp…)
│   └── wrapper/
│       └── gradle-wrapper.properties
└── .gitmodules                    # llama.cpp submodule declaration
```

## How Inference Works

```
User input (Kotlin)
      │
      ▼
ChatViewModel.sendMessage()
      │  builds Qwen chat-template prompt
      ▼
LlamaRuntime.streamGenerate()   ← callbackFlow on Dispatchers.IO
      │  calls nativeStreamGenerate() via JNI
      ▼
llama_bridge.cpp
  tokenize prompt → llama_tokenize()
  decode prompt   → llama_decode()
  sampler chain   → temp + top-p + dist
  generation loop → llama_sampler_sample()
                    llama_token_to_piece()
                    callback.onToken(piece)  → JNI → Kotlin Flow emit
      │
      ▼
ChatScreen.kt collects Flow, appends token to last message in real time
```

## Verification Checklist

- [ ] `./gradlew assembleDebug` exits 0
- [ ] APK installs via `adb install`
- [ ] App launches and shows the ErnOS chat screen
- [ ] Tap the folder icon, enter the model path, tap Load — status turns green
- [ ] Type a prompt, tap Send — tokens stream in token-by-token
- [ ] Tap Stop — generation halts, partial response is kept
- [ ] No network traffic during inference (verify with Android Network Profiler)

## Next Steps (Milestone 2)

- `SystemPrompter.kt` — dynamic HUD + tool schema
- `ReActLoopManager.kt` — multi-turn tool execution
- `ToolRegistry.kt` — web_search, file_read/write, reply_to_request
