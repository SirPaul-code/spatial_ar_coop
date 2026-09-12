#!/usr/bin/env python3

import unittest

from aggregate_runs import ClaimGateConfig, aggregate_samples
from score_aruco import Sample


def make_run(run_index: int, count: int = 120, stable_available: bool = True):
    samples = []
    for frame in range(count):
        stock_error = 8.0 + ((frame + run_index) % 5) * 0.4
        stable_error = 2.0 + ((frame * 3 + run_index) % 4) * 0.2
        samples.append(
            Sample(
                timestamp_ns=run_index * 1_000_000 + frame,
                phase="orbit45",
                gt_x=100.0,
                gt_y=100.0,
                stock_x=100.0 + stock_error,
                stock_y=100.0,
                stable_x=(100.0 + stable_error) if stable_available else None,
                stable_y=100.0 if stable_available else None,
                ground_truth_mode="aruco_root_homography",
            )
        )
    return samples


class AggregateRunsTest(unittest.TestCase):
    def test_independent_run_gate_passes_clear_paired_improvement(self):
        runs = [(f"run-{index}", make_run(index)) for index in range(5)]
        config = ClaimGateConfig(
            required_phases=("orbit45",),
            min_runs_per_required_phase=5,
        )
        result = aggregate_samples(runs, threshold_px=12.0, config=config)
        self.assertEqual(result["claim_gate"]["status"], "PASS")
        self.assertTrue(result["claim_gate"]["commercial_comparative_claim_ready"])
        self.assertGreater(
            result["run_clustered_inference"]["mean_improvement_ci95_px"][0], 0.0
        )
        self.assertLess(
            result["pooled_descriptive_metrics"]["stablear"]["p95_px"],
            result["pooled_descriptive_metrics"]["stock_arcore"]["p95_px"],
        )

    def test_one_run_cannot_clear_commercial_claim_gate(self):
        result = aggregate_samples([("only-run", make_run(1))])
        self.assertEqual(result["claim_gate"]["status"], "NOT_CLEARED")
        self.assertFalse(result["claim_gate"]["criteria"]["independent_runs"]["pass"])
        self.assertIsNone(result["run_clustered_inference"]["mean_improvement_ci95_px"])

    def test_missing_predictions_are_not_rewarded(self):
        runs = [(f"run-{index}", make_run(index, stable_available=False)) for index in range(5)]
        result = aggregate_samples(runs)
        self.assertEqual(result["claim_gate"]["status"], "NOT_CLEARED")
        self.assertFalse(result["claim_gate"]["criteria"]["stablear_availability"]["pass"])
        self.assertFalse(result["claim_gate"]["criteria"]["total_paired_frames"]["pass"])


if __name__ == "__main__":
    unittest.main()
