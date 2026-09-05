from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Iterable, Optional
import math

import cv2
import numpy as np

S3 = np.diag([1.0, -1.0, -1.0]).astype(np.float64)
S4 = np.eye(4, dtype=np.float64)
S4[:3, :3] = S3


@dataclass
class AlignmentResult:
    method: str
    T_wb_wa: np.ndarray
    inliers: int
    correspondences: int
    median_reprojection_px: float
    confidence: float

    def as_dict(self) -> Dict[str, Any]:
        return {
            "method": self.method,
            "T_wb_wa": self.T_wb_wa.reshape(-1).tolist(),
            "inliers": int(self.inliers),
            "correspondences": int(self.correspondences),
            "median_reprojection_px": float(self.median_reprojection_px),
            "confidence": float(self.confidence),
        }


def quaternion_to_rotation(q: Iterable[float]) -> np.ndarray:
    x, y, z, w = [float(v) for v in q]
    n = math.sqrt(x * x + y * y + z * z + w * w)
    if n < 1e-12:
        return np.eye(3, dtype=np.float64)
    x, y, z, w = x / n, y / n, z / n, w / n
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ], dtype=np.float64)


def pose_matrix(pose: Dict[str, Any]) -> np.ndarray:
    T = np.eye(4, dtype=np.float64)
    T[:3, :3] = quaternion_to_rotation(pose["q"])
    T[:3, 3] = np.asarray(pose["t"], dtype=np.float64)
    return T


def camera_matrix(frame: Dict[str, Any]) -> np.ndarray:
    k = frame["intrinsics"]
    return np.array([
        [float(k["fx"]), 0.0, float(k["cx"])],
        [0.0, float(k["fy"]), float(k["cy"])],
        [0.0, 0.0, 1.0],
    ], dtype=np.float64)


def decode_gray(frame: Dict[str, Any]) -> np.ndarray:
    import base64
    raw = base64.b64decode(frame["jpeg_b64"])
    arr = np.frombuffer(raw, dtype=np.uint8)
    image = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE)
    if image is None:
        raise ValueError("invalid jpeg_b64")
    return image


def _sift_matches(frame_a: Dict[str, Any], frame_b: Dict[str, Any]):
    a = decode_gray(frame_a)
    b = decode_gray(frame_b)
    sift = cv2.SIFT_create(nfeatures=2200, contrastThreshold=0.02, edgeThreshold=12)
    kpa, da = sift.detectAndCompute(a, None)
    kpb, db = sift.detectAndCompute(b, None)
    if da is None or db is None or len(kpa) < 8 or len(kpb) < 8:
        return [], kpa or [], kpb or []
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    pairs = matcher.knnMatch(da, db, k=2)
    good = []
    for pair in pairs:
        if len(pair) != 2:
            continue
        m, n = pair
        if m.distance < 0.76 * n.distance:
            good.append(m)
    return good, kpa, kpb


def solve_pnp_correspondences(object_points_wa, image_points_b, K_b, T_wb_cb_arcore):
    object_points_wa = np.asarray(object_points_wa, dtype=np.float64).reshape(-1, 3)
    image_points_b = np.asarray(image_points_b, dtype=np.float64).reshape(-1, 2)
    if len(object_points_wa) < 6 or len(image_points_b) != len(object_points_wa):
        return None
    ok, rvec, tvec, inliers = cv2.solvePnPRansac(
        object_points_wa, image_points_b, K_b, None,
        flags=cv2.SOLVEPNP_EPNP, iterationsCount=500,
        reprojectionError=3.0, confidence=0.999,
    )
    if not ok or inliers is None or len(inliers) < 6:
        return None
    idx = inliers.reshape(-1)
    obj_in = object_points_wa[idx]
    img_in = image_points_b[idx]
    try:
        rvec, tvec = cv2.solvePnPRefineLM(obj_in, img_in, K_b, None, rvec, tvec)
    except cv2.error:
        pass
    R_cv, _ = cv2.Rodrigues(rvec)
    T_cv_b_wa = np.eye(4, dtype=np.float64)
    T_cv_b_wa[:3, :3] = R_cv
    T_cv_b_wa[:3, 3] = tvec.reshape(3)
    T_cb_wa_arcore = S4 @ T_cv_b_wa
    T_wb_wa = T_wb_cb_arcore @ T_cb_wa_arcore
    projected, _ = cv2.projectPoints(obj_in, rvec, tvec, K_b, None)
    err = np.linalg.norm(projected.reshape(-1, 2) - img_in, axis=1)
    median = float(np.median(err)) if len(err) else 999.0
    inlier_ratio = float(len(idx)) / float(len(object_points_wa))
    confidence = max(0.0, min(1.0, inlier_ratio * min(1.0, len(idx) / 24.0) * math.exp(-median / 4.0)))
    if not np.isfinite(T_wb_wa).all() or abs(np.linalg.det(T_wb_wa[:3, :3]) - 1.0) > 0.05:
        return None
    return AlignmentResult("metric_depth_pnp", T_wb_wa, len(idx), len(object_points_wa), median, confidence)


def align_metric_pnp(frame_a: Dict[str, Any], frame_b: Dict[str, Any], pixel_gate: float = 6.0):
    support = np.asarray(frame_a.get("metric_points", []), dtype=np.float64)
    if support.ndim != 2 or support.shape[1] < 5 or len(support) < 12:
        return None
    matches, kpa, kpb = _sift_matches(frame_a, frame_b)
    if len(matches) < 8:
        return None
    uv = support[:, :2]
    xyz = support[:, 2:5]
    used_support = set()
    object_points = []
    image_points = []
    gate2 = pixel_gate * pixel_gate
    for m in matches:
        p = np.asarray(kpa[m.queryIdx].pt, dtype=np.float64)
        d2 = np.sum((uv - p) ** 2, axis=1)
        j = int(np.argmin(d2))
        if d2[j] > gate2 or j in used_support:
            continue
        used_support.add(j)
        object_points.append(xyz[j])
        image_points.append(kpb[m.trainIdx].pt)
    if len(object_points) < 6:
        return None
    return solve_pnp_correspondences(np.asarray(object_points), np.asarray(image_points), camera_matrix(frame_b), pose_matrix(frame_b["pose"]))


def align_essential_scaled(frame_a: Dict[str, Any], frame_b: Dict[str, Any], distance_m: float):
    if not (0.05 < float(distance_m) < 250.0):
        return None
    matches, kpa, kpb = _sift_matches(frame_a, frame_b)
    if len(matches) < 8:
        return None
    p1 = np.float64([kpa[m.queryIdx].pt for m in matches]).reshape(-1, 1, 2)
    p2 = np.float64([kpb[m.trainIdx].pt for m in matches]).reshape(-1, 1, 2)
    n1 = cv2.undistortPoints(p1, camera_matrix(frame_a), None).reshape(-1, 2)
    n2 = cv2.undistortPoints(p2, camera_matrix(frame_b), None).reshape(-1, 2)
    E, mask = cv2.findEssentialMat(n1, n2, np.eye(3), cv2.RANSAC, 0.999, 0.0025)
    if E is None:
        return None
    inliers, R_cv, t_cv, _ = cv2.recoverPose(E, n1, n2, np.eye(3), mask=mask)
    if inliers < 8:
        return None
    t = t_cv.reshape(3)
    t_norm = np.linalg.norm(t)
    if t_norm < 1e-9:
        return None
    t *= float(distance_m) / t_norm
    T_cv_b_ca = np.eye(4, dtype=np.float64)
    T_cv_b_ca[:3, :3] = R_cv
    T_cv_b_ca[:3, 3] = t
    T_cb_ca_arcore = S4 @ T_cv_b_ca @ S4
    T_wa_ca = pose_matrix(frame_a["pose"])
    T_wb_cb = pose_matrix(frame_b["pose"])
    T_wb_wa = T_wb_cb @ T_cb_ca_arcore @ np.linalg.inv(T_wa_ca)
    inlier_ratio = float(inliers) / float(len(matches))
    confidence = max(0.0, min(0.65, inlier_ratio * 0.65))
    return AlignmentResult("essential_plus_range", T_wb_wa, int(inliers), len(matches), float("nan"), confidence)


def align_frames(frame_a: Dict[str, Any], frame_b: Dict[str, Any], range_measurement: Optional[Dict[str, Any]] = None):
    primary = align_metric_pnp(frame_a, frame_b)
    if primary is not None:
        return primary
    if range_measurement is not None:
        return align_essential_scaled(frame_a, frame_b, float(range_measurement.get("distance_m", 0.0)))
    return None


def transform_point(T: np.ndarray, p: Iterable[float]) -> np.ndarray:
    x = np.ones(4, dtype=np.float64)
    x[:3] = np.asarray(list(p), dtype=np.float64)
    y = T @ x
    return y[:3] / y[3]
