#!/usr/bin/env python3
"""Reproducible synthetic AR failure-mode experiments. Not device benchmarks.

Conventions: transforms T_AB map B into A; CV camera is x-right/y-down/z-forward.
ARCore adapter must flip the y,z signs once. No learned weights or network access.
"""
from __future__ import annotations
import json
import math
import platform
from pathlib import Path
import numpy as np
import cv2
from scipy.spatial.transform import Rotation

SEED = 20260910

def transform(T: np.ndarray, p: np.ndarray) -> np.ndarray:
    return np.asarray(p) @ T[:3, :3].T + T[:3, 3]

def pose(rotvec=(0., 0., 0.), t=(0., 0., 0.)) -> np.ndarray:
    T = np.eye(4)
    T[:3, :3] = Rotation.from_rotvec(rotvec).as_matrix()
    T[:3, 3] = t
    return T

def anchor_local_point(T_WA: np.ndarray, T_WC: np.ndarray,
                       uv: np.ndarray, z: float, K: np.ndarray) -> np.ndarray:
    """Same-update transforms only. Store output with a retained anchor handle."""
    if z <= 0 or not np.isfinite(z):
        raise ValueError('Depth must be positive and finite')
    ray = np.linalg.solve(K, np.r_[uv, 1.])
    return transform(np.linalg.inv(T_WA) @ T_WC, ray * z)

def project(T_WC: np.ndarray, p_W: np.ndarray, K: np.ndarray) -> np.ndarray:
    p = transform(np.linalg.inv(T_WC), p_W)
    if np.any(p[..., 2] <= 0):
        raise ValueError('Point behind camera')
    h = p @ K.T
    return h[..., :2] / h[..., 2, None]

def stats(values) -> dict:
    a = np.asarray(values, dtype=float)
    if not np.all(np.isfinite(a)) or a.size == 0:
        raise ValueError('Invalid statistics input')
    return dict(n=int(a.size), median=float(np.median(a)),
                p95=float(np.quantile(a, .95)), rms=float(np.sqrt(np.mean(a*a))))

def timing_and_gauge() -> dict:
    rng = np.random.default_rng(SEED)
    K = np.array([[800., 0, 320], [0, 800., 240], [0, 0, 1.]])
    p = np.array([0., 0., 2.])
    rows = []
    for delay in [.0167, .05, .10, .20]:
        latest = pose((0, math.radians(60)*delay, 0), (.3*delay, 0, 0))
        wrong = transform(latest, p)
        rows.append(dict(delay_ms=delay*1000,
                         wrong_world_error_mm=float(np.linalg.norm(wrong-p)*1000),
                         rendered_error_px=float(np.linalg.norm(project(latest, wrong, K)-project(latest, p, K)))))
    bad, good = [], []
    for _ in range(1000):
        A = pose(rng.normal(0, .05, 3), rng.normal(0, .1, 3))
        C = pose(rng.normal(0, .03, 3), rng.normal(0, .02, 3))
        q = anchor_local_point(A, C, np.array([340., 235.]), 2., K)
        old_p = transform(A, q)
        # Synthetic rigid gauge update: 3 cm per-axis translation, 1 degree rotation.
        G = pose(rng.normal(0, math.radians(1), 3), rng.normal(0, .03, 3))
        actual = transform(G, old_p)
        reanchored = transform(G @ A, q)
        bad.append(np.linalg.norm(old_p-actual)*1000)
        good.append(np.linalg.norm(reanchored-actual)*1000)
    assert max(good) < 1e-8
    return dict(timing=rows, rigid_gauge_stale_mm=stats(bad), rigid_gauge_anchor_local_mm=stats(good),
                caveat='Exact invariance only for common rigid gauge updates; not a model of nonrigid SLAM correction.')

def depth_observability() -> dict:
    rng = np.random.default_rng(SEED+1)
    z, f, sigma_px = 2., 800., .5
    rows = []
    for baseline in [.01, .05, .20]:
        disparity = f*baseline/z + rng.normal(0, math.sqrt(2)*sigma_px, 20000)
        estimate = f*baseline/disparity
        rows.append(dict(baseline_mm=baseline*1000,
                         depth_absolute_error_mm=stats(np.abs(estimate-z)*1000),
                         linearized_sigma_mm=z*z/(f*baseline)*math.sqrt(2)*sigma_px*1000))
    # For a point on the optical axis, one projection's Jacobian has a radial nullspace.
    J = np.array([[f/z, 0, 0], [0, f/z, 0]])
    evals = np.linalg.eigvalsh(J.T @ J)
    assert evals[0] == 0
    return dict(monte_carlo=rows, single_view_information_eigenvalues=evals.tolist(),
                assumptions='Exact poses/calibration; Gaussian independent 0.5 px error in each of two images. No depth prior.')

def correlated_depth() -> dict:
    rng = np.random.default_rng(SEED+2)
    n, common_sd, fresh_sd = 30, .020, .010
    common = rng.normal(0, common_sd, (20000, 1))
    fresh = rng.normal(0, fresh_sd, (20000, n))
    err = (common+fresh).mean(1)
    naive = math.sqrt(common_sd**2+fresh_sd**2)/math.sqrt(n)
    aware = math.sqrt(common_sd**2+fresh_sd**2/n)
    dedup = []
    independent_per_source = rng.normal(0, fresh_sd, (20000, 10))
    repeated = np.repeat(independent_per_source, 3, axis=1)
    assert np.allclose(repeated.mean(1), independent_per_source.mean(1))
    return dict(n_observations=n, common_bias_sigma_mm=common_sd*1000,
                white_noise_sigma_mm=fresh_sd*1000, empirical_mean_error_sigma_mm=float(err.std()*1000),
                naive_reported_sigma_mm=naive*1000, correlation_aware_sigma_mm=aware*1000,
                naive_95pct_interval_coverage=float(np.mean(np.abs(err) <= 1.96*naive)),
                aware_95pct_interval_coverage=float(np.mean(np.abs(err) <= 1.96*aware)),
                repeats_30_from_10_empirical_sigma_mm=float(repeated.mean(1).std()*1000),
                caveat='Assumed noise model, not measured ARCore noise. Source-ID deduplication alone does not remove shared bias.')

def robust_inverse_depth(xy: np.ndarray, depths: np.ndarray) -> float:
    """IRLS local plane prototype; production also needs boundary/coverage checks."""
    if xy.shape != (len(depths), 2) or len(depths) < 6:
        raise ValueError('Need >= 6 two-dimensional support samples')
    X = np.c_[xy/30., np.ones(len(depths))]
    if np.linalg.cond(X) > 100:
        raise ValueError('Degenerate support geometry')
    y = 1./depths
    weights = np.ones(len(depths))
    for _ in range(12):
        w = np.sqrt(weights)
        coeff = np.linalg.lstsq(X*w[:, None], y*w, rcond=None)[0]
        e = X@coeff-y
        scale = max(1e-5, 1.4826*np.median(np.abs(e-np.median(e))))
        weights = np.minimum(1., 1.5*scale/np.maximum(np.abs(e), 1e-12))
    if coeff[2] <= 0:
        raise ValueError('Nonphysical depth')
    return float(1./coeff[2])

def plane_fitting() -> dict:
    rng = np.random.default_rng(SEED+3)
    output = {}
    for mode in ('symmetric_support', 'asymmetric_support'):
        median_errors, fit_errors = [], []
        for _ in range(500):
            # A plane through z=2 m with about 0.004 m/px local depth slope.
            x = rng.uniform(-20, 20, 80) if mode == 'symmetric_support' else rng.uniform(-4, 25, 80)
            xy = np.c_[x, rng.uniform(-20, 20, 80)]
            depths = 1./(.5-.001*xy[:, 0]+.0003*xy[:, 1])
            noisy = depths+rng.normal(0, .005, len(depths))
            # Mild sparse outliers; no foreground/background boundary in this test.
            noisy[rng.choice(len(depths), 8, replace=False)] += .04
            median_errors.append(abs(np.median(noisy)-2)*1000)
            fit_errors.append(abs(robust_inverse_depth(xy, noisy)-2)*1000)
        output[mode] = dict(local_median_error_mm=stats(median_errors), plane_fit_error_mm=stats(fit_errors))
    try:
        robust_inverse_depth(np.c_[np.arange(10), np.zeros(10)], np.ones(10)*2)
        raise AssertionError('Collinear supports not rejected')
    except ValueError:
        pass
    return dict(cases=output, note='Median baseline represents verifier simplification, NOT existing ShowMe pointAt which already fits inverse depth.')

def texture(seed: int, n: int=480) -> np.ndarray:
    rng = np.random.default_rng(seed)
    img = np.full((n,n), 130, np.uint8)
    for _ in range(140):
        a = tuple(int(x) for x in rng.integers(5, n-5, 2))
        b = tuple(int(x) for x in rng.integers(5, n-5, 2))
        cv2.line(img, a, b, int(rng.integers(15, 240)), int(rng.integers(1, 4)), cv2.LINE_AA)
    for _ in range(110):
        a = tuple(int(x) for x in rng.integers(15, n-15, 2))
        cv2.circle(img, a, int(rng.integers(3, 15)), int(rng.integers(10, 245)), -1, cv2.LINE_AA)
    return img

def warp_points(H: np.ndarray, points: np.ndarray) -> np.ndarray:
    q = np.c_[points, np.ones(len(points))] @ H.T
    return q[:, :2]/q[:, 2, None]

def edges(img: np.ndarray) -> np.ndarray:
    return cv2.Canny(cv2.GaussianBlur(cv2.equalizeHist(img), (3, 3), 0), 48, 132, L2gradient=True)

def edge_match(ref_edges: np.ndarray, cur_edges: np.ndarray,
               ref_center: np.ndarray, predicted: np.ndarray) -> tuple[np.ndarray, float]:
    x, y = ref_center.astype(int)
    cx, cy = predicted.astype(int)
    r, s = 27, 11
    template = ref_edges[y-r:y+r+1, x-r:x+r+1]
    if cv2.countNonZero(template) < 42:
        raise ValueError('Insufficient edges')
    search = cur_edges[cy-r-s:cy+r+s+1, cx-r-s:cx+r+s+1]
    response = cv2.matchTemplate(search, template, cv2.TM_CCOEFF_NORMED)
    _, score, _, loc = cv2.minMaxLoc(response)
    hit = np.array([cx-s+loc[0], cy-s+loc[1]], dtype=float)
    return hit, float(score)

def edge_refinement() -> dict:
    records = []
    for degrees in (0, 2, 4, 8, 16, 24):
        proposed, coarse, warped, scores, worsening = [], [], [], [], 0
        total = 0
        for seed in range(12):
            src = texture(SEED+100+seed)
            A = cv2.getRotationMatrix2D((240.,240.), degrees, 1.0)
            H = np.vstack([A, [0,0,1.]])
            H[0, 2] += 4.3; H[1, 2] -= 3.6
            cur = cv2.warpPerspective(src, H, (480,480), borderMode=cv2.BORDER_REFLECT)
            reference_edges, current_edges = edges(src), edges(cur)
            # This warped-template control uses the KNOWN true H, not an estimated tracker.
            oracle_edges = edges(cv2.warpPerspective(src, H, (480,480), borderMode=cv2.BORDER_REFLECT))
            for center in (np.array([210.,210.]), np.array([260.,260.]), np.array([220.,270.])):
                truth = warp_points(H, center[None])[0]
                pred = truth + np.array([1., -.7])
                total += 1
                hit, score = edge_match(reference_edges,current_edges,center,pred)
                if score >= .28 and np.linalg.norm(hit-pred) <= 12:
                    err = float(np.linalg.norm(hit-truth))
                    proposed.append(err); coarse.append(float(np.linalg.norm(pred-truth)))
                    scores.append(score)
                    worsening += int(err > np.linalg.norm(pred-truth))
                ohit, _ = edge_match(oracle_edges,current_edges,np.floor(truth),pred)
                warped.append(float(np.linalg.norm(ohit-truth)))
        records.append(dict(rotation_deg=degrees, total=total, image_gate_accepted=len(proposed),
                            image_gate_accepted_worse_than_input=worsening,
                            accepted_refined_error_px=stats(proposed) if proposed else None,
                            coarse_input_error_px=math.sqrt(1+.7**2),
                            oracle_warped_template_error_px=stats(warped)))
    return dict(cases=records,
                caveat='Image-gate stress test of the published constants; no metric/depth gates. Oracle warped control is NOT an achieved SDK improvement. Synthetic planar images; no occlusion.')

def klt_test() -> dict:
    src = texture(SEED+200)
    A = cv2.getRotationMatrix2D((240.,240.), 1.5, 1.)
    A[:, 2] += [4.1, -2.6]
    H = np.vstack([A, [0,0,1.]])
    cur = cv2.warpPerspective(src, H, (480,480), borderMode=cv2.BORDER_REFLECT)
    cur = np.clip(cur.astype(float)*.98+2., 0, 255).astype(np.uint8)
    p = cv2.goodFeaturesToTrack(src, maxCorners=256, qualityLevel=.02, minDistance=10, blockSize=7)
    opts = dict(winSize=(21,21), maxLevel=3, criteria=(cv2.TERM_CRITERIA_COUNT|cv2.TERM_CRITERIA_EPS,30,.01))
    q, ok, _ = cv2.calcOpticalFlowPyrLK(src, cur, p, None, **opts)
    back, okback, _ = cv2.calcOpticalFlowPyrLK(cur, src, q, None, **opts)
    fb = np.linalg.norm(back[:,0]-p[:,0], axis=1)
    keep = ok[:,0].astype(bool) & okback[:,0].astype(bool) & (fb < .5)
    truth = warp_points(H, p[:,0])
    err = np.linalg.norm(q[keep,0]-truth[keep], axis=1)
    assert keep.sum() > 80 and np.median(err) < .5
    blank = cv2.goodFeaturesToTrack(np.full_like(src,130),256,.02,10)
    assert blank is None
    return dict(detected=len(p), accepted=int(keep.sum()), error_px=stats(err),
                blank_image_points=0, caveat='One synthetic planar pair; not a long-sequence/occlusion/mobile benchmark.')

def screen_smoothing() -> dict:
    rng = np.random.default_rng(SEED+4)
    fps = 60
    t = np.arange(fps*10)/fps
    truth = 320 + 160*np.sin(2*np.pi*.6*t)
    observed = truth+rng.normal(0,.8,len(t))
    rows = []
    for alpha in (1., .5, .2, .05):
        result = observed.copy()
        for i in range(1,len(result)):
            result[i] = alpha*observed[i]+(1-alpha)*result[i-1]
        rows.append(dict(alpha=alpha, moving_projection_error_px=stats(np.abs(result[60:]-truth[60:])),
                         asymptotic_low_frequency_delay_ms=(1-alpha)/alpha/fps*1000))
    return dict(cases=rows, caveat='Smoothing screen coordinates; not smoothing a slowly varying anchor-local correction.')

def reprojection_false_certainty() -> dict:
    K = np.array([[800.,0,320],[0,800.,240],[0,0,1.]])
    a, b = np.array([0.,0.,2.]), np.array([0.,0.,2.15])
    camera = pose()
    error = np.linalg.norm(project(camera,a,K)-project(camera,b,K))
    side = pose(t=(.20,0,0))
    later = np.linalg.norm(project(side,a,K)-project(side,b,K))
    assert error == 0 and later > 5
    return dict(depth_error_mm=150, original_view_error_px=float(error),
                error_after_20cm_translation_px=float(later),
                lesson='Small reprojection residual from one view cannot certify depth accuracy.')

def main() -> None:
    cv2.setNumThreads(1)
    cv2.setRNGSeed(SEED)
    result = dict(seed=SEED, python=platform.python_version(), numpy=np.__version__, opencv=cv2.__version__,
                  scope='Synthetic mathematical and image tests, not phone measurements or end-to-end SDK evaluation.',
                  timing_and_gauge=timing_and_gauge(), depth_observability=depth_observability(),
                  correlated_depth=correlated_depth(), plane_fitting=plane_fitting(),
                  edge_refinement=edge_refinement(), klt=klt_test(),
                  smoothing=screen_smoothing(), reprojection=reprojection_false_certainty())
    out = Path(__file__).parent/'results.json'
    out.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n', encoding='utf-8')
    print(json.dumps(result,indent=2,allow_nan=False))

if __name__ == '__main__':
    main()
