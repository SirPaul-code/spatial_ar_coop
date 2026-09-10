#pragma once
#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
  #if defined(STABLEAR_BUILDING_DLL)
    #define STABLEAR_API __declspec(dllexport)
  #else
    #define STABLEAR_API __declspec(dllimport)
  #endif
#else
  #define STABLEAR_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct stablear_session stablear_session;
typedef struct { double x, y; } stablear_v2;
typedef struct { double x, y, z; } stablear_v3;
typedef struct { double x, y, z, w; } stablear_quat;
typedef struct { stablear_v3 t; stablear_quat q; } stablear_rigid;
typedef struct { double fx, fy, cx, cy; int width, height; } stablear_intrinsics;
typedef enum stablear_depth_origin { STABLEAR_DEPTH_RAW=0, STABLEAR_DEPTH_SMOOTHED=1, STABLEAR_DEPTH_POINT_CLOUD=2, STABLEAR_DEPTH_EXTERNAL=3 } stablear_depth_origin;
typedef enum stablear_lock_state { STABLEAR_LOCK_UNVERIFIED=0, STABLEAR_LOCK_GEOMETRY_SUPPORTED=1, STABLEAR_LOCK_OCCLUDED=2, STABLEAR_LOCK_UNCERTAIN=3, STABLEAR_LOCK_RELOCALIZING=4, STABLEAR_LOCK_LOST=5 } stablear_lock_state;
typedef struct { stablear_v2 pixel; double z; double confidence; int origin; int64_t source_timestamp_ns; } stablear_depth_sample;
typedef struct { uint64_t id, epoch, anchor_id; int64_t camera_timestamp_ns, captured_ns; stablear_rigid anchor_from_camera; stablear_intrinsics intrinsics; } stablear_frame_ref;
typedef struct { uint64_t id, generation; uint64_t root_frame_id, root_epoch, root_anchor_id; int64_t root_camera_timestamp_ns; stablear_rigid root_camera_in_anchor; stablear_intrinsics root_intrinsics; stablear_v2 root_pixel; double depth_m, conditional_sigma_m, travel_m; int state; } stablear_attachment_snapshot;
typedef struct { uint64_t frame_id, epoch, anchor_id, generation; int64_t camera_timestamp_ns, captured_ns; stablear_rigid camera_in_anchor; stablear_intrinsics intrinsics; stablear_v2 pixel; int inliers; double forward_backward_px, reprojection_px, sigma_px; } stablear_visual_observation;
typedef uint64_t (*stablear_anchor_create_fn)(void*, const stablear_rigid*);
typedef int (*stablear_anchor_locate_fn)(void*, uint64_t, stablear_rigid*);
typedef void (*stablear_anchor_destroy_fn)(void*, uint64_t);
typedef struct { void* context; stablear_anchor_create_fn create; stablear_anchor_locate_fn locate; stablear_anchor_destroy_fn destroy; } stablear_anchor_callbacks;
typedef struct { int minimum_views; double minimum_parallax_deg, max_correction_m, total_travel_m; double max_conditional_sigma_m, assumed_common_translation_sigma_m, systematic_floor_m; int64_t max_observation_age_ns; } stablear_lock_policy;
typedef struct { int max_frames; int64_t history_ns; int max_anchors; int64_t max_freeze_ns; stablear_lock_policy lock; } stablear_session_config;

STABLEAR_API const char* stablear_version(void);
STABLEAR_API uint32_t stablear_abi_version(void);
STABLEAR_API int64_t stablear_monotonic_now_ns(void);
STABLEAR_API stablear_session_config stablear_default_session_config(void);
STABLEAR_API stablear_session* stablear_session_create(stablear_anchor_callbacks callbacks, stablear_session_config config);
STABLEAR_API void stablear_session_destroy(stablear_session* session);
STABLEAR_API uint64_t stablear_session_epoch(const stablear_session* session);
STABLEAR_API int stablear_session_capture(stablear_session*, const stablear_rigid*, const stablear_intrinsics*, int64_t, const stablear_depth_sample*, size_t, stablear_frame_ref*);
STABLEAR_API int stablear_session_freeze(stablear_session*, uint64_t, stablear_frame_ref*);
STABLEAR_API void stablear_session_unfreeze(stablear_session*, uint64_t);
STABLEAR_API int stablear_session_place(stablear_session*, const stablear_frame_ref*, const stablear_depth_sample*, size_t, stablear_v2, stablear_attachment_snapshot*);
STABLEAR_API int stablear_session_context(stablear_session*, uint64_t, const stablear_frame_ref*, stablear_rigid*, uint64_t*, uint64_t*);
STABLEAR_API int stablear_session_observe(stablear_session*, uint64_t, const stablear_visual_observation*, stablear_attachment_snapshot*, int*);
STABLEAR_API int stablear_session_world_point(stablear_session*, uint64_t, stablear_v3*);
STABLEAR_API int stablear_session_initial_world_point(stablear_session*, uint64_t, stablear_v3*);
STABLEAR_API int stablear_session_snapshot(stablear_session*, uint64_t, stablear_attachment_snapshot*);
STABLEAR_API void stablear_session_visibility(stablear_session*, uint64_t, int, int);
STABLEAR_API void stablear_session_remove(stablear_session*, uint64_t);
STABLEAR_API void stablear_session_tracking_lost(stablear_session*);
STABLEAR_API void stablear_session_reset(stablear_session*);
typedef int (*stablear_signature_verify_fn)(void*, const uint8_t*, size_t, const uint8_t*, size_t);
typedef struct { int usable; int in_grace; char reason[128]; } stablear_entitlement_result;
STABLEAR_API stablear_entitlement_result stablear_entitlement_verify(const char*, const char*, const char*, const char*, const char*, int64_t, void*, stablear_signature_verify_fn);

#ifdef __cplusplus
}
#endif
