import numpy as np
from alignment import S4, solve_pnp_correspondences, transform_point


def rot_y(a):
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float64)


def test_metric_pnp_recovers_world_transform():
    rng = np.random.default_rng(7)
    K = np.array([[900.0, 0.0, 640.0], [0.0, 910.0, 360.0], [0.0, 0.0, 1.0]])
    R_cv = rot_y(0.13)
    t_cv = np.array([0.45, -0.08, 1.1])
    T_cv_b_wa = np.eye(4)
    T_cv_b_wa[:3, :3] = R_cv
    T_cv_b_wa[:3, 3] = t_cv
    pts = np.column_stack([rng.uniform(-2, 2, 80), rng.uniform(-1.2, 1.2, 80), rng.uniform(3.5, 8.0, 80)])
    cam = (R_cv @ pts.T).T + t_cv
    keep = cam[:, 2] > 0.5
    pts, cam = pts[keep], cam[keep]
    uv = np.column_stack([K[0, 0] * cam[:, 0] / cam[:, 2] + K[0, 2], K[1, 1] * cam[:, 1] / cam[:, 2] + K[1, 2]])
    uv += rng.normal(0, 0.25, uv.shape)
    result = solve_pnp_correspondences(pts, uv, K, np.eye(4))
    assert result is not None
    expected = S4 @ T_cv_b_wa
    np.testing.assert_allclose(result.T_wb_wa[:3, :3], expected[:3, :3], atol=0.01)
    np.testing.assert_allclose(result.T_wb_wa[:3, 3], expected[:3, 3], atol=0.03)
    assert result.inliers >= 50
    assert result.median_reprojection_px < 1.0


def test_transform_point():
    T = np.eye(4)
    T[:3, 3] = [1, 2, 3]
    np.testing.assert_allclose(transform_point(T, [4, 5, 6]), [5, 7, 9])
