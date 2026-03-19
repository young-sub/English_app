# Learnings

- Added per-utterance local TTS telemetry (queue wait, ensureLoaded, generate, AudioTrack create/write, playback head progression + timeout cause).
- Emulator logcat shows `AudioTrack.write(float[])` can fail with `Unable to retrieve AudioTrack pointer for write()` even when track is initialized; `PCM_FLOAT` playback needs a fallback path.

- Inference tuning: run synthesis/playback on separate executors; set `OfflineTtsModelConfig.numThreads` to `(availableProcessors / 2).coerceIn(1, 4)` and disable `debug` to reduce overhead.
