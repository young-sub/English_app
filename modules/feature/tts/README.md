# f5-tts

Subtree component repository for parallel implementation.

## Scope

- Offline sentence TTS flow
- Playback state synchronization
- Bundled on-device model contract (`modules/feature/tts/models/kokoro-en-v0_19`)

## Quality Gates

- Unit/contract tests green
- Parent integration gate green

## Standalone Local TTS Probe

Use the standalone probe when you want to test local Kokoro synthesis/playback
without going through app UI flow.

### 1) Run with Python launcher

```bash
python scripts/local_tts_probe.py --text "Hello from local TTS." --speed 1.0
```

Optional flags:

- `--speaker-id 0`
- `--timeout-ms 120000`
- `--skip-adb-check`

### 2) Run directly with Gradle

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.bookhelper.tts.LocalTtsStandaloneProbeInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.localTtsText="Hello from local TTS." \
  -Pandroid.testInstrumentationRunnerArguments.localTtsSpeed=1.0 \
  -Pandroid.testInstrumentationRunnerArguments.localTtsTimeoutMs=120000
```

### What this validates

- `OfflineTts.generate(...)` synthesis path
- `AudioTrack` playback path via telemetry progression and completion
- Async failure callback path (`onFailure`) when local playback fails

Note: `AndroidTtsManager.speak()` can return accepted for local route before
async failure fallback resolves. The standalone probe bypasses this manager path
and validates local engine behavior directly.

Note: `LocalModelTtsEngine` timeout is exposed as playback telemetry (`timedOut`)
instead of `onFailure`. The probe treats timeout as failure.

## Pure Python Local Kokoro Test (App-Independent)

Use this when you want to verify local model setup and playback without Android
app flow, instrumentation, or `AndroidTtsManager`.

### Setup

```bash
python -m pip install --user sherpa-onnx numpy scipy
```

### Run

```bash
python scripts/python_local_kokoro_tts.py \
  --text "This is a standalone local Kokoro TTS playback test in English." \
  --model-dir modules/feature/tts/models/kokoro-en-v0_19 \
  --speaker-id 0 \
  --speed 1.0
```

The script checks required files (`model.onnx`, `voices.bin`, `tokens.txt`,
`espeak-ng-data`), synthesizes audio with `sherpa-onnx` Kokoro, writes WAV,
and plays it on Windows.
