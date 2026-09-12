#!/usr/bin/env python3
"""Aggregate independent physical StableAR benchmark runs and evaluate a claim gate.

Frame-level measurements inside one phone run are temporally correlated. Commercial
confidence is therefore bootstrapped over independent runs, not individual frames.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

from score_aruco import Sample, load_samples, score


@dataclass(frozen=True)
class ClaimGateConfig:
    min_runs: int = 5
    min_total_paired_frames: int = 500
    min_run_paired_frames: int = 30
    min_stable_availability: float = 0.90
    max_availability_drop: float = 0.02
    max_false_lock_rate: float = 0.02
    max_false_lock_delta: float = 0.005
    min_positive_run_fraction: float = 0.80
    allowed_ground_truth_modes: tuple[str, ...] = ("aruco_root_homography",)
    required_phases: tuple[str, ...] = ()
    min_runs_per_required_phase: int = 5


def _error(x: float | None, y: float | None, sample: Sample) -> float | None:
    if x is None or y is None:
        return None
    return math.hypot(x - sample.gt_x, y - sample.gt_y)


def _paired_deltas(samples: Sequence[Sample]) -> list[float]:
    result: list[float] = []
    for sample in samples:
        stock = _error(sample.stock_x, sample.stock_y, sample)
        stable = _error(sample.stable_x, sample.stable_y, sample)
        if stock is not None and stable is not None:
            result.append(stock - stable)
    return result


def _percentile(values: Sequence[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    position = fraction * (len(ordered) - 1)
    lo = int(math.floor(position))
    hi = int(math.ceil(position))
    if lo == hi:
        return float(ordered[lo])
    t = position - lo
    return float(ordered[lo] * (1.0 - t) + ordered[hi] * t)


def _bootstrap_run_mean_ci(
    run_means: Sequence[float], seed: int = 20260912, rounds: int = 10_000
) -> list[float] | None:
    """Cluster bootstrap: one resampled unit is one independently captured run."""
    if len(run_means) < 2:
        return None
    rng = random.Random(seed)
    n = len(run_means)
    means = [
        statistics.fmean(run_means[rng.randrange(n)] for _ in range(n))
        for _ in range(rounds)
    ]
    lo = _percentile(means, 0.025)
    hi = _percentile(means, 0.975)
    return [lo, hi] if lo is not None and hi is not None else None


def _criterion(passed: bool, observed, requirement: str) -> dict:
    return {"pass": bool(passed), "observed": observed, "requirement": requirement}


def aggregate_samples(
    runs: Sequence[tuple[str, Sequence[Sample]]],
    threshold_px: float = 12.0,
    config: ClaimGateConfig = ClaimGateConfig(),
) -> dict:
    if not runs:
        raise ValueError("At least one benchmark run is required")

    run_results = []
    all_samples: list[Sample] = []
    run_means: list[float] = []
    phase_run_counts: dict[str, int] = {}
    all_modes: set[str] = set()

    for name, samples in runs:
        values = list(samples)
        if not values:
            continue
        all_samples.extend(values)
        metrics = score(values, threshold_px)
        deltas = _paired_deltas(values)
        mean_delta = statistics.fmean(deltas) if deltas else None
        modes = sorted({sample.ground_truth_mode for sample in values})
        phases = sorted({sample.phase for sample in values})
        all_modes.update(modes)
        for phase in phases:
            phase_run_counts[phase] = phase_run_counts.get(phase, 0) + 1
        eligible = len(deltas) >= config.min_run_paired_frames
        if eligible and mean_delta is not None:
            run_means.append(mean_delta)
        run_results.append(
            {
                "name": name,
                "paired_frames": len(deltas),
                "eligible_for_cluster_ci": eligible,
                "mean_paired_improvement_px": mean_delta,
                "ground_truth_modes": modes,
                "phases": phases,
                "metrics": metrics,
            }
        )

    if not all_samples:
        raise ValueError("All supplied benchmark runs were empty")

    pooled = score(all_samples, threshold_px)
    run_ci = _bootstrap_run_mean_ci(run_means)
    positive_run_fraction = (
        sum(value > 0.0 for value in run_means) / len(run_means) if run_means else None
    )

    stock = pooled["stock_arcore"]
    stable = pooled["stablear"]
    paired = pooled["paired"]
    stable_p95 = stable["p95_px"]
    stock_p95 = stock["p95_px"]
    stable_availability = stable["availability"]
    stock_availability = stock["availability"]
    stable_false_lock = stable["false_lock_rate"]
    stock_false_lock = stock["false_lock_rate"]

    allowed_modes = set(config.allowed_ground_truth_modes)
    ground_truth_ok = bool(all_modes) and all_modes.issubset(allowed_modes)
    phase_checks = {
        phase: _criterion(
            phase_run_counts.get(phase, 0) >= config.min_runs_per_required_phase,
            phase_run_counts.get(phase, 0),
            f">= {config.min_runs_per_required_phase} independent runs containing phase '{phase}'",
        )
        for phase in config.required_phases
    }

    criteria = {
        "independent_runs": _criterion(
            len(run_means) >= config.min_runs,
            len(run_means),
            f">= {config.min_runs} runs with >= {config.min_run_paired_frames} paired frames each",
        ),
        "total_paired_frames": _criterion(
            paired["frames"] >= config.min_total_paired_frames,
            paired["frames"],
            f">= {config.min_total_paired_frames}",
        ),
        "ground_truth": _criterion(
            ground_truth_ok,
            sorted(all_modes),
            f"all modes in {sorted(allowed_modes)}",
        ),
        "stablear_p95_better": _criterion(
            stable_p95 is not None and stock_p95 is not None and stable_p95 < stock_p95,
            {"stablear_px": stable_p95, "stock_arcore_px": stock_p95},
            "StableAR pooled p95 < stock ARCore pooled p95",
        ),
        "run_cluster_ci_positive": _criterion(
            run_ci is not None and run_ci[0] > 0.0,
            run_ci,
            "95% cluster-bootstrap CI lower bound for mean per-run paired improvement > 0 px",
        ),
        "positive_run_fraction": _criterion(
            positive_run_fraction is not None
            and positive_run_fraction >= config.min_positive_run_fraction,
            positive_run_fraction,
            f">= {config.min_positive_run_fraction:.3f}",
        ),
        "stablear_availability": _criterion(
            stable_availability >= config.min_stable_availability,
            stable_availability,
            f">= {config.min_stable_availability:.3f}",
        ),
        "availability_not_hidden_by_dropouts": _criterion(
            stable_availability >= stock_availability - config.max_availability_drop,
            {"stablear": stable_availability, "stock_arcore": stock_availability},
            f"StableAR no more than {config.max_availability_drop:.3f} below stock ARCore",
        ),
        "stablear_false_lock_cap": _criterion(
            stable_false_lock is not None and stable_false_lock <= config.max_false_lock_rate,
            stable_false_lock,
            f"<= {config.max_false_lock_rate:.4f}",
        ),
        "false_lock_not_worse_than_stock": _criterion(
            stable_false_lock is not None
            and stock_false_lock is not None
            and stable_false_lock <= stock_false_lock + config.max_false_lock_delta,
            {"stablear": stable_false_lock, "stock_arcore": stock_false_lock},
            f"StableAR <= stock ARCore + {config.max_false_lock_delta:.4f}",
        ),
    }
    criteria.update({f"phase:{name}": value for name, value in phase_checks.items()})
    cleared = all(value["pass"] for value in criteria.values())

    return {
        "schema": "stablear-benchmark-aggregate-v1",
        "runs_supplied": len(run_results),
        "eligible_independent_runs": len(run_means),
        "phase_run_counts": dict(sorted(phase_run_counts.items())),
        "pooled_descriptive_metrics": pooled,
        "run_clustered_inference": {
            "mean_of_run_mean_improvements_px": statistics.fmean(run_means) if run_means else None,
            "mean_improvement_ci95_px": run_ci,
            "positive_run_fraction": positive_run_fraction,
            "unit_of_resampling": "independent_run",
            "bootstrap_rounds": 10_000,
        },
        "runs": run_results,
        "claim_gate": {
            "status": "PASS" if cleared else "NOT_CLEARED",
            "commercial_comparative_claim_ready": cleared,
            "criteria": criteria,
            "note": (
                "PASS is an engineering evidence gate for the supplied physical dataset, not legal "
                "clearance and not a universal ARCore superiority claim. Claim wording must remain "
                "scoped to tested devices, conditions and protocol."
            ),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Aggregate independent StableAR-vs-ARCore physical benchmark sessions"
    )
    parser.add_argument("sessions", nargs="+", type=Path)
    parser.add_argument("--dictionary", default="DICT_4X4_50")
    parser.add_argument("--marker-id", type=int, default=23)
    parser.add_argument("--false-lock-threshold-px", type=float, default=12.0)
    parser.add_argument("--min-runs", type=int, default=5)
    parser.add_argument("--min-total-paired-frames", type=int, default=500)
    parser.add_argument("--min-run-paired-frames", type=int, default=30)
    parser.add_argument("--min-stable-availability", type=float, default=0.90)
    parser.add_argument("--max-availability-drop", type=float, default=0.02)
    parser.add_argument("--max-false-lock-rate", type=float, default=0.02)
    parser.add_argument("--max-false-lock-delta", type=float, default=0.005)
    parser.add_argument("--min-positive-run-fraction", type=float, default=0.80)
    parser.add_argument("--allowed-ground-truth-mode", action="append", default=[])
    parser.add_argument("--require-phase", action="append", default=[])
    parser.add_argument("--min-runs-per-required-phase", type=int, default=5)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument(
        "--claim-exit-code",
        action="store_true",
        help="exit 2 when the supplied dataset does not clear the configured claim gate",
    )
    args = parser.parse_args()

    allowed_modes = tuple(args.allowed_ground_truth_mode or ["aruco_root_homography"])
    config = ClaimGateConfig(
        min_runs=args.min_runs,
        min_total_paired_frames=args.min_total_paired_frames,
        min_run_paired_frames=args.min_run_paired_frames,
        min_stable_availability=args.min_stable_availability,
        max_availability_drop=args.max_availability_drop,
        max_false_lock_rate=args.max_false_lock_rate,
        max_false_lock_delta=args.max_false_lock_delta,
        min_positive_run_fraction=args.min_positive_run_fraction,
        allowed_ground_truth_modes=allowed_modes,
        required_phases=tuple(args.require_phase),
        min_runs_per_required_phase=args.min_runs_per_required_phase,
    )

    runs: list[tuple[str, Sequence[Sample]]] = []
    for session in args.sessions:
        samples = load_samples(session, args.dictionary, args.marker_id)
        if not samples:
            raise SystemExit(f"No independently visible/evaluable marker frames in {session}")
        runs.append((session.name, samples))

    result = aggregate_samples(runs, args.false_lock_threshold_px, config)
    encoded = json.dumps(result, indent=2, sort_keys=True)
    print(encoded)
    if args.json_out:
        args.json_out.write_text(encoded + "\n", encoding="utf-8")
    if args.claim_exit_code and not result["claim_gate"]["commercial_comparative_claim_ready"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
