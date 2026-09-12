#!/usr/bin/env python3

import unittest

from score_aruco import Sample, score_with_phases


class ScoreArucoTest(unittest.TestCase):
    def test_stablear_improvement_is_paired_and_positive(self):
        samples = [
            Sample(i, "front", 100.0, 100.0, 100.0 + e, 100.0, 100.0 + e / 4.0, 100.0)
            for i, e in enumerate([8.0, 4.0, 12.0, 6.0, 10.0, 2.0], start=1)
        ]
        result = score_with_phases(samples, threshold_px=7.0)
        self.assertEqual(result["marker_visible_frames"], 6)
        self.assertGreater(result["paired"]["mean_improvement_px"], 0.0)
        self.assertEqual(result["paired"]["win_rate"], 1.0)
        self.assertLess(result["stablear"]["p95_px"], result["stock_arcore"]["p95_px"])
        self.assertEqual(result["stablear"]["false_lock_rate"], 0.0)
        self.assertIn("front", result["phases"])

    def test_invalid_prediction_reduces_availability_not_error(self):
        samples = [
            Sample(1, "front", 10.0, 10.0, 11.0, 10.0, None, None),
            Sample(2, "front", 10.0, 10.0, 12.0, 10.0, 10.5, 10.0),
        ]
        result = score_with_phases(samples)
        self.assertEqual(result["stock_arcore"]["availability"], 1.0)
        self.assertEqual(result["stablear"]["availability"], 0.5)
        self.assertEqual(result["paired"]["frames"], 1)


if __name__ == "__main__":
    unittest.main()
