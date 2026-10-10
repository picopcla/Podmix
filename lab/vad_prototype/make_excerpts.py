#!/usr/bin/env python3
"""Create small local verification clips; never writes beside source media."""

import json
from pathlib import Path
import subprocess

import imageio_ffmpeg

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "output" / "excerpts"


def main() -> int:
    config = json.loads((ROOT / "episodes.json").read_text())
    OUTPUT.mkdir(parents=True, exist_ok=True)
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    count = 0
    for episode in config["episodes"]:
        for track in episode["tracks"]:
            if track.get("split") not in {"tuning", "evaluation"}:
                continue
            ident = f'{episode["id"]}-t{track["number"]:02d}'
            original = float(track["time"])
            for suffix, start in (("before", max(0, original - 12)), ("after", original)):
                target = OUTPUT / f"{ident}-{suffix}.ogg"
                if target.exists():
                    continue
                cmd = [
                    ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-ss", f"{start:.3f}", "-t", "12", "-i", episode["local_media"],
                    "-vn", "-ac", "1", "-c:a", "libopus", "-b:a", "40k", "-y", str(target),
                ]
                subprocess.run(cmd, check=True)
                count += 1
    print(f"extraits_crees={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
