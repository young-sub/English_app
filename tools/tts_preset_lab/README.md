# TTS Preset Lab

Developer-only standalone web tool for auditioning the local English TTS models
in this repository.

What it does:

- Compare `lessac-low` and `libritts_r-medium` outside the Android app
- Preview many LibriTTS-R speakers quickly
- Switch between `auto`, `cuda`, and `cpu`
- Batch-generate multiple speakers with the same sample text
- Download generated WAV files for later listening

## Models

- `단일 모델` -> `modules/feature/tts/models/lessac-low`
- `다화자 모델` -> `modules/feature/tts/models/piper-en_US-libritts_r-medium`

The server prepares a small runtime cache under `tools/tts_preset_lab/.cache/`
so raw Piper assets can be used directly with `sherpa_onnx`.

## Dependencies

Minimum:

```bash
python -m pip install --user sherpa-onnx numpy
```

Optional GPU build for NVIDIA CUDA:

```bash
pip install sherpa-onnx==1.12.31+cuda -f https://k2-fsa.github.io/sherpa/onnx/cuda.html
```

## Run

```bash
python tools/tts_preset_lab/server.py --host 127.0.0.1 --port 8765
```

Then open:

```text
http://127.0.0.1:8765
```

## Smoke Test

```bash
python tools/tts_preset_lab/server.py --smoke-test --provider auto
```

This generates one WAV file and exits without starting the web server.
