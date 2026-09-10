#!/usr/bin/env python3
"""Research prototype: robust depth refinement on an immutable clicked ray.

Input correspondences and camera-to-retained-anchor poses are provided by a front
end. This is NOT a camera tracker, Android adapter, or calibrated accuracy model.
The parameter is metric depth along the original ray (equivalent to inverse depth
away from zero). A single static rigid patch and a valid initial depth are assumed.
"""
from __future__ import annotations
from dataclasses import dataclass, asdict
import json
from pathlib import Path
import numpy as np
from scipy.optimize import least_squares

@dataclass(frozen=True)
class Observation:
    frame_id: int
    epoch: int
    anchor_generation: int
    T_AC: np.ndarray
    uv: np.ndarray
    sigma_px: float = .5

@dataclass
class Estimate:
    state: str
    depth_m: float
    sigma_depth_m: float
    inliers: int
    max_parallax_deg: float
    point_A: list[float]
    reason: str


def validate_pose(T: np.ndarray) -> None:
    if T.shape != (4,4) or not np.isfinite(T).all():
        raise ValueError('Pose must be finite 4x4')
    R = T[:3,:3]
    if not np.allclose(R.T@R,np.eye(3),atol=1e-5) or abs(np.linalg.det(R)-1)>1e-5:
        raise ValueError('Rotation must be proper orthonormal')
    if not np.allclose(T[3], [0,0,0,1]):
        raise ValueError('Invalid homogeneous transform')


def fit_surface_ray(K: np.ndarray, T_AC0: np.ndarray, original_uv: np.ndarray,
                    seed_depth_m: float, seed_sigma_m: float,
                    observations: list[Observation], *, epoch: int=1,
                    anchor_generation: int=1, systematic_floor_m: float=.010,
                    max_correction_m: float=.25,
                    common_pose_translation_sigma_m: float=0.) -> Estimate:
    """All transforms must be expressed relative to the SAME retained local anchor.

    Stale epochs/generations are discarded. Duplicate frame IDs are not re-counted.
    sigma_depth is conditional on supplied poses plus an explicitly assumed floor;
    this does NOT account for arbitrary correlated pose errors or identity swaps.
    """
    validate_pose(T_AC0)
    if K.shape != (3,3) or not np.isfinite(K).all() or K[0,0] <= 0 or K[1,1] <= 0:
        raise ValueError('Invalid intrinsics')
    if np.asarray(original_uv).shape != (2,) or not np.isfinite(original_uv).all():
        raise ValueError('Invalid original pixel')
    if not .15 < seed_depth_m < 8 or seed_sigma_m <= 0 or systematic_floor_m < 0 or max_correction_m <= 0 or common_pose_translation_sigma_m < 0:
        raise ValueError('Invalid depth/noise/bounds')
    origin = T_AC0[:3,3]
    ray = T_AC0[:3,:3] @ np.linalg.solve(K,np.r_[original_uv,1.])
    seed_point = origin + ray*seed_depth_m
    unique = {}
    for ob in observations:
        if ob.epoch != epoch or ob.anchor_generation != anchor_generation:
            continue
        validate_pose(ob.T_AC)
        if ob.uv.shape != (2,) or not np.isfinite(ob.uv).all() or ob.sigma_px <= 0:
            raise ValueError('Invalid image observation')
        if ob.frame_id not in unique:
            unique[ob.frame_id] = ob
    obs = list(unique.values())
    def pending(reason: str, parallax: float=0.) -> Estimate:
        return Estimate('PENDING',seed_depth_m,max(seed_sigma_m,systematic_floor_m),0,
                        parallax,seed_point.tolist(),reason)
    if len(obs) < 3:
        return pending('Fewer than three distinct valid image observations')
    def angle(point, ob):
        a = point-origin; b = point-ob.T_AC[:3,3]
        cos = np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b))
        return float(np.degrees(np.arccos(np.clip(cos,-1,1))))
    max_angle = max(angle(seed_point, ob) for ob in obs)
    if max_angle < 1.:
        return pending('Insufficient translational parallax; do not claim a visual depth correction',max_angle)
    def pixel(z, ob, shared_shift=None):
        shift = np.zeros(3) if shared_shift is None else shared_shift
        p = ob.T_AC[:3,:3].T @ (origin+ray*z-ob.T_AC[:3,3]-shift)
        if p[2] <= .05:
            return np.array([1e6,1e6])
        q = K@p
        return q[:2]/q[2]
    def residual(x, active):
        z = float(x[0])
        image = [(pixel(z, ob)-ob.uv)/ob.sigma_px for ob in active]
        return np.r_[np.concatenate(image),(z-seed_depth_m)/seed_sigma_m]
    bounds = (max(.15,seed_depth_m-max_correction_m), min(8.,seed_depth_m+max_correction_m))
    first = least_squares(residual,[seed_depth_m],args=(obs,),bounds=bounds,loss='huber',f_scale=2.,max_nfev=60)
    z = float(first.x[0])
    kept = [ob for ob in obs if np.linalg.norm(pixel(z,ob)-ob.uv) <= 3.*ob.sigma_px]
    if len(kept) < 3:
        return pending('Insufficient geometric inliers',max_angle)
    final = least_squares(residual,[z],args=(kept,),bounds=bounds,loss='linear',max_nfev=60)
    z = float(final.x[0])
    post_angle = max(angle(origin+ray*z, ob) for ob in kept)
    if post_angle < 1. or min(z-bounds[0],bounds[1]-z) < 1e-5:
        return pending('Unobservable after gating, or correction bound reached',post_angle)
    conditional_sigma = 1./np.sqrt(float(np.sum(final.jac**2)))
    # Sensitivity of the fitted depth to a coherent translation error in all
    # supplied current poses (the root reference stays fixed). Implicit LS rule:
    # dz/db = -(Jz^T Jz)^-1 Jz^T Jb. Marginalize its known covariance.
    eps = 1e-5
    Jz = final.jac[:,0]
    Jb = np.zeros((len(Jz),3))
    for axis in range(3):
        delta=np.eye(3)[axis]*eps
        for index,ob in enumerate(kept):
            Jb[2*index:2*index+2,axis]=(pixel(z,ob,delta)-pixel(z,ob,-delta))/(2*eps*ob.sigma_px)
    sensitivity=-(Jz@Jb)/float(Jz@Jz)
    common_variance=common_pose_translation_sigma_m**2*float(sensitivity@sensitivity)
    sigma = float(np.sqrt(conditional_sigma**2+systematic_floor_m**2+common_variance))
    state = 'SUPPORTED' if sigma <= .030 else 'PENDING'
    return Estimate(state,z,sigma,len(kept),post_angle,(origin+ray*z).tolist(),
                    'Conditional synthetic geometry support, not certified device accuracy')


def synthetic_probe() -> dict:
    rng = np.random.default_rng(20260910)
    K = np.array([[800.,0,320],[0,800.,240],[0,0,1.]])
    root = np.eye(4)
    truth = np.array([0.,0.,2.])
    output = {}
    for label, noise_sd, declared_sd, baseline in [
        ('exact_camera_poses',0.,0.,.24),
        ('shared_5mm_error_ignored',.005,0.,.24),
        ('shared_5mm_error_modelled',.005,.005,.24),
        ('shared_5mm_error_modelled_80cm_baseline',.005,.005,.80)]:
        rng = np.random.default_rng(20260910)  # paired data between estimator variants
        errors, sigma, accepted = [], [], 0
        for trial in range(300):
            obs = []
            common = rng.normal(0,noise_sd,3)
            for j,x in enumerate(np.linspace(.04,baseline,6)):
                physical = np.eye(4); physical[:3,3]=[x,.02*np.sin(j),0]
                cam_point = truth-physical[:3,3]
                uv = (K@cam_point)[:2]/cam_point[2]+rng.normal(0,.5,2)
                if j == 3:
                    uv += [25., -18.]  # a false correspondence
                supplied = physical.copy(); supplied[:3,3] += common
                obs.append(Observation(j,1,1,supplied,uv))
            result=fit_surface_ray(K,root,np.array([320.,240.]),2.12,.12,obs,common_pose_translation_sigma_m=declared_sd)
            if result.state == 'SUPPORTED':
                accepted += 1; errors.append(abs(result.depth_m-2.)); sigma.append(result.sigma_depth_m)
            # Repeated frame IDs must not change the estimate.
            duplicate=fit_surface_ray(K,root,np.array([320.,240.]),2.12,.12,obs+obs,common_pose_translation_sigma_m=declared_sd)
            assert abs(duplicate.depth_m-result.depth_m) < 1e-12
        a=np.array(errors)
        output[label] = dict(trials=300,supported=accepted,seed_error_mm=120.,
                             supported_median_error_mm=float(np.median(a)*1000) if len(a) else None,
                             supported_p95_error_mm=float(np.quantile(a,.95)*1000) if len(a) else None,
                             median_assumed_sigma_mm=float(np.median(sigma)*1000) if len(a) else None)
    still=[Observation(i,1,1,np.eye(4),np.array([320.,240.])) for i in range(6)]
    no_parallax=fit_surface_ray(K,root,np.array([320.,240.]),2.12,.12,still)
    assert no_parallax.state == 'PENDING' and no_parallax.depth_m == 2.12
    stale=[Observation(i,0,1,np.eye(4),np.array([320.,240.])) for i in range(6)]
    assert fit_surface_ray(K,root,np.array([320.,240.]),2.12,.12,stale).state == 'PENDING'
    output['no_parallax'] = asdict(no_parallax)
    output['limits']='Synthetic supplied correspondences, no image tracker or hardware. Stale-ID/dedup/parallax checks included. Noise floor 10 mm is ASSUMED, not calibrated.'
    return output

if __name__ == '__main__':
    result=synthetic_probe()
    Path(__file__).with_name('surface_lock_results.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
