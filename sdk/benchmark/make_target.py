#!/usr/bin/env python3
"""Generate the independent ArUco target used by the StableAR field benchmark."""

from __future__ import annotations

import argparse
from pathlib import Path

try:
    import cv2  # type: ignore
    import numpy as np  # type: ignore
except Exception as exc:  # pragma: no cover
    raise SystemExit("Install opencv-contrib-python and numpy first") from exc


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("stablear-aruco-23.png"))
    parser.add_argument("--dictionary", default="DICT_4X4_50")
    parser.add_argument("--marker-id", type=int, default=23)
    parser.add_argument("--marker-px", type=int, default=1200)
    parser.add_argument("--margin-px", type=int, default=160)
    args = parser.parse_args()

    aruco = cv2.aruco
    dictionary_id = getattr(aruco, args.dictionary, None)
    if dictionary_id is None:
        raise SystemExit(f"Unknown ArUco dictionary {args.dictionary}")
    dictionary = aruco.getPredefinedDictionary(dictionary_id)
    marker = aruco.generateImageMarker(dictionary, args.marker_id, args.marker_px)
    canvas = np.full(
        (args.marker_px + 2 * args.margin_px, args.marker_px + 2 * args.margin_px),
        255,
        dtype=np.uint8,
    )
    canvas[
        args.margin_px : args.margin_px + args.marker_px,
        args.margin_px : args.margin_px + args.marker_px,
    ] = marker
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(args.output), canvas):
        raise SystemExit(f"Could not write {args.output}")
    print(f"Wrote {args.output} ({args.dictionary} id {args.marker_id})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
