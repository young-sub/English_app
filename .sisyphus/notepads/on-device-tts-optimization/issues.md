# Issues

- Local on-device playback fails on emulator with `IllegalStateException: Failed to stream generated float samples to AudioTrack` and root error `Unable to retrieve AudioTrack pointer for write()`; likely need PCM_16BIT fallback or alternate AudioTrack construction.
- Cleanup note: a stray untracked `components/f5-tts/src/main/kotlin/com/example/bookhelper/tts/LocalTtsRuntimeStatus.kt` existed only inside the worktree and duplicated `TtsRoute`/`LocalTtsRuntimeStatus` already defined in `app/src/main/kotlin/com/example/bookhelper/tts/AndroidTtsManager.kt`; it was deleted to avoid duplicate-class/dex conflicts.
