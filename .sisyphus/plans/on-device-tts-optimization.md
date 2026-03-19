# On-device TTS Optimization & Hot-swap

- [x] P0: Determine why on-device TTS is silent (audio path vs inference vs scheduling)
- [x] P0: Add lightweight timing/telemetry for local TTS (load/synth/playback/queue wait)
- [x] P0: Fix local playback when AudioTrack PCM_FLOAT write fails (fallback path)
- [x] P1: Decouple inference and playback execution (avoid single-queue starvation)
- [x] P1: Reduce inference overhead (disable debug in release, tune numThreads safely)
- [x] P2: Implement low-latency model switching (2-slot cache + background preload + safe eviction)

## Final Verification Wave

- [x] F1: LSP diagnostics clean for touched files
- [x] F2: Unit/instrumentation tests relevant to TTS pass
- [x] F3: Manual QA on emulator/device: tap word -> audio audible; switch models -> no full restart; no console errors
- [x] F4: Regression: system TTS still works; fallback behavior unchanged
