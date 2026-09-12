#!/usr/bin/env python3
"""Score StableAR and an unrefined ARCore baseline against an independent ArUco image target."""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

try:
    import cv2  # type: ignore
except Exception:  # pragma: no cover - optional for pre-labelled datasets
    cv2 = None


@dataclass(frozen=True)
class Sample:
    timestamp_ns: int
    phase: str
    gt_x: float
    gt_y: float
    stock_x: float | None
    stock_y: float | None
    stable_x: float | None
    stable_y: float | None
    fx: float | None = None
    fy: float | None = None


def _bool(value: str | None, default: bool = True) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _number(value: str | None) -> float | None:
    if value is None or value == "":
        return None
    try:
        result = float(value)
    except ValueError:
        return None
    return result if math.isfinite(result) else None


def _aruco_center(path: Path, dictionary_name: str, marker_id: int) -> tuple[float, float] | None:
    if cv2 is None:
        raise RuntimeError(
            "OpenCV is required when gt_x/gt_y are absent. Install opencv-contrib-python."
        )
    image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if image is None:
        return None
    aruco = cv2.aruco
    dictionary_id = getattr(aruco, dictionary_name, None)
    if dictionary_id is None:
        raise ValueError(f"Unknown ArUco dictionary: {dictionary_name}")
    dictionary = aruco.getPredefinedDictionary(dictionary_id)
    if hasattr(aruco, "ArucoDetector"):
        detector = aruco.ArucoDetector(dictionary, aruco.DetectorParameters())
        corners, ids, _ = detector.detectMarkers(image)
    else:  # older OpenCV contrib
        corners, ids, _ = aruco.detectMarkers(image, dictionary)
    if ids is None:
        return None
    flat_ids = [int(v) for v in ids.flatten()]
    for index, value in enumerate(flat_ids):
        if value != marker_id:
            continue
        points = corners[index].reshape(-1, 2)
        return float(points[:, 0].mean()), float(points[:, 1].mean())
    return None


def load_samples(session: Path, dictionary_name: str, marker_id: int) -> list[Sample]:
    csv_path = session / "frames.csv"
    if not csv_path.is_file():
        raise FileNotFoundError(f"Missing {csv_path}")
    result: list[Sample] = []
    with csv_path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            gt_x = _number(row.get("gt_x"))
            gt_y = _number(row.get("gt_y"))
            if gt_x is None or gt_y is None:
                image_path = row.get("image_path", "").strip()
                if not image_path:
                    continue
                detected = _aruco_center(session / image_path, dictionary_name, marker_id)
                if detected is None:
                    continue
                gt_x, gt_y = detected

            stock_valid = _bool(row.get("stock_valid"), True)
            stable_valid = _bool(row.get("stable_valid"), True)
            stock_x = _number(row.get("stock_x")) if stock_valid else None
            stock_y = _number(row.get("stock_y")) if stock_valid else None
            stable_x = _number(row.get("stable_x")) if stable_valid else None
            stable_y = _number(row.get("stable_y")) if stable_valid else None
            if stock_x is None or stock_y is None:
                stock_x = stock_y = None
            if stable_x is None or stable_y is None:
                stable_x = stable_y = None
            result.append(
                Sample(
                    timestamp_ns=int(float(row.get("timestamp_ns", "0") or 0)),
                    phase=(row.get("phase") or "unlabelled").strip() or "unlabelled",
                    gt_x=gt_x,
                    gt_y=gt_y,
                    stock_x=stock_x,
                    stock_y=stock_y,
                    stable_x=stable_x,
                    stable_y=stable_y,
                    fx=_number(row.get("fx")),
                    fy=_number(row.get("fy")),
                )
            )
    return result


def _error(x: float | None, y: float | None, sample: Sample) -> float | None:
    if x is None or y is None:
        return None
    return math.hypot(x - sample.gt_x, y - sample.gt_y)


def _percentile(values: Sequence[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = fraction * (len(ordered) - 1)
    low = int(math.floor(position))
    high = int(math.ceil(position))
    if low == high:
        return ordered[low]
    t = position - low
    return ordered[low] * (1.0 - t) + ordered[high] * t


def _candidate_metrics(errors: list[float], visible_frames: int, threshold_px: float) -> dict:
    valid = len(errors)
    return {
        "valid_frames": valid,
        "availability": valid / visible_frames if visible_frames else 0.0,
        "mean_px": statistics.fmean(errors) if errors else None,
        "rmse_px": math.sqrt(statistics.fmean([v * v for v in errors])) if errors else None,
        "p50_px": _percentile(errors, 0.50),
        "p95_px": _percentile(errors, 0.95),
        "max_px": max(errors) if errors else None,
        "false_lock_rate": (sum(v > threshold_px for v in errors) / valid) if valid else None,
    }


def _bootstrap_mean_ci(values: Sequence[float], seed: int = 20260912, rounds: int = 5000) -> list[float] | None:
    if len(values) < 2:
        return None
    rng = random.Random(seed)
    n = len(values)
    means = []
    for _ in range(rounds):
        means.append(statistics.fmean(values[rng.randrange(n)] for _ in range(n)))
    lo = _percentile(means, 0.025)
    hi = _percentile(means, 0.975)
    return [float(lo), float(hi)] if lo is not None and hi is not None else None


def score(samples: Sequence[Sample], threshold_px: float = 12.0) -> dict:
    stock_errors: list[float] = []
    stable_errors: list[float] = []
    paired_delta: list[float] = []
    paired_wins = 0

    for sample in samples:
        stock = _error(sample.stock_x, sample.stock_y, sample)
        stable = _error(sample.stable_x, sample.stable_y, sample)
        if stock is not None:
            stock_errors.append(stock)
        if stable is not None:
            stable_errors.append(stable)
        if stock is not None and stable is not None:
            delta = stock - stable
            paired_delta.append(delta)
            paired_wins += int(delta > 0)

    paired_count = len(paired_delta)
    return {
        "marker_visible_frames": len(samples),
        "threshold_px": threshold_px,
        "stock_arcore": _candidate_metrics(stock_errors, len(samples), threshold_px),
        "stablear": _candidate_metrics(stable_errors, len(samples), threshold_px),
        "paired": {
            "frames": paired_count,
            "mean_improvement_px": statistics.fmean(paired_delta) if paired_delta else None,
            "p50_improvement_px": _percentile(paired_delta, 0.50),
            "win_rate": paired_wins / paired_count if paired_count else None,
            "mean_improvement_ci95_px": _bootstrap_mean_ci(paired_delta),
        },
    }


def score_with_phases(samples: Sequence[Sample], threshold_px: float = 12.0) -> dict:
    overall = score(samples, threshold_px)
    phases: dict[str, list[Sample]] = {}
    for sample in samples:
        phases.setdefault(sample.phase, []).append(sample)
    overall["phases"] = {name: score(values, threshold_px) for name, values in sorted(phases.items())}
    return overall


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", type=Path)
    parser.add_argument("--dictionary", default="DICT_4X4_50")
    parser.add_argument("--marker-id", type=int, default=23)
    parser.add_argument("--false-lock-threshold-px", type=float, default=12.0)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()

    samples = load_samples(args.session, args.dictionary, args.marker_id)
    if not samples:
        raise SystemExit("No independently visible/evaluable marker frames found")
    result = score_with_phases(samples, args.false_lock_threshold_px)
    encoded = json.dumps(result, indent=2, sort_keys=True)
    print(encoded)
    if args.json_out:
        args.json_out.write_text(encoded + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
