#!/usr/bin/env python3
"""Record a secret-free CPU/dependency audit for the laboratory report."""

import importlib.metadata as metadata
import json
import platform
from pathlib import Path

import imageio_ffmpeg
import onnxruntime
import tensorflow as tf

ROOT = Path(__file__).resolve().parent


def main() -> int:
    installed = {dist.metadata["Name"]: dist.version for dist in metadata.distributions()}
    wanted = (
        "inaSpeechSegmenter", "silero-vad", "tensorflow-cpu", "onnxruntime",
        "numpy", "scikit-image", "soundfile", "imageio-ffmpeg"
    )
    forbidden = sorted(
        name for name in installed
        if any(marker in name.lower() for marker in ("cuda", "cudnn", "nvidia", "onnxruntime-gpu"))
    )
    ina = metadata.distribution("inaSpeechSegmenter")
    audit = {
        "platform": platform.platform(),
        "python": platform.python_version(),
        "packages": {name: metadata.version(name) for name in wanted},
        "ina_upstream_requires": ina.requires,
        "cpu_substitutions": {
            "tensorflow[and-cuda]": "tensorflow-cpu==2.21.0",
            "onnxruntime-gpu": "onnxruntime==1.31.0",
        },
        "forbidden_gpu_distributions_installed": forbidden,
        "tensorflow_build": tf.sysconfig.get_build_info(),
        "tensorflow_gpu_devices": [str(x) for x in tf.config.list_physical_devices("GPU")],
        "onnxruntime_available_providers": onnxruntime.get_available_providers(),
        "onnxruntime_forced_provider": "CPUExecutionProvider",
        "ffmpeg_binary": imageio_ffmpeg.get_ffmpeg_exe(),
    }
    (ROOT / "results").mkdir(exist_ok=True)
    (ROOT / "results" / "environment-audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(audit, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
