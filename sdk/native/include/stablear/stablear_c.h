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
typedef enum stablear_depth_origin {
    STABLEAR_DEPTH_RAW = 0,
    STABLEAR_DEPTH_SMOOTHED = 1,
    STABLEAR_DEPTH_POINT_CLOUD = 2,
    STABLEAR_DEPTH_EXTERNAL = 3
} stablear_depth_origin;

typedef enum stablear_lock_state {
    STABLEAR_LOCK_UNVERIFIED = 0,
    STABLEAR_LOCK_GEOMETRY_SUPPORTED = 1,
    STABLEAR_LOCK_OCCLUDED = 2,
    STABLEAR_LOCK_UNCERTAIN = 3,
    STABLEAR_LOCK_RELOCALIZING = 4,
    STABLEAR_LOCK_LOST = 5
} stablear_lock_state;

typedef struct {
    stablear_v2 pixel;
    double z;
    double confidence;
    int origin; /* stablear_depth_origin */
    int64_t source_timestamp_ns;
} stablear_depth_sample;

typedef struct {
    uint64_t id, epoch, anchor_id;
    int64_t camera_timestamp_ns, captured_ns;
    stablear_rigid anchor_from_camera;
    stablear_intrinsics intrinsics;
} stablear_frame_ref;

typedef struct {
    uint64_t id, generation;
    uint64_t root_frame_id, root_epoch, root_anchor_id;
    int64_t root_camera_timestamp_ns;
    stablear_rigid root_camera_in_anchor;
    stablear_intrinsics root_intrinsics;
    stablear_v2 root_pixel;
    double depth_m, conditional_sigma_m, travel_m;
    int state; /* stablear_lock_state */
} stablear_attachment_snapshot;

typedef struct {
    uint64_t frame_id, epoch, anchor_id, generation;
    int64_t camera_timestamp_ns, captured_ns;
    stablear_rigid camera_in_anchor;
    stablear_intrinsics intrinsics;
    stablear_v2 pixel;
    int inliers;
    double forward_backward_px, reprojection_px, sigma_px;
} stablear_visual_observation;

typedef uint64_t (*stablear_anchor_create_fn)(void* context, const stablear_rigid* world_from_anchor);
typedef int (*stablear_anchor_locate_fn)(void* context, uint64_t id, stablear_rigid* out_world_from_anchor);
typedef void (*stablear_anchor_destroy_fn)(void* context, uint64_t id);

typedef struct {
    void* context;
    stablear_anchor_create_fn create;
    stablear_anchor_locate_fn locate;
    stablear_anchor_destroy_fn destroy;
} stablear_anchor_callbacks;

typedef struct {
    int minimum_views;
    double minimum_parallax_deg, max_correction_m, total_travel_m;
    double max_conditional_sigma_m, assumed_common_translation_sigma_m, systematic_floor_m;
    int64_t max_observation_age_ns;
} stablear_lock_policy;

typedef struct {
    int max_frames;
    int64_t history_ns;
    int max_anchors;
    int64_t max_freeze_ns;
    stablear_lock_policy lock;
} stablear_session_config;

STABLEAR_API const char* stablear_version(void);
STABLEAR_API uint32_t stablear_abi_version(void);
STABLEAR_API int64_t stablear_monotonic_now_ns(void);
STABLEAR_API stablear_session_config stablear_default_session_config(void);
STABLEAR_API stablear_session* stablear_session_create(stablear_anchor_callbacks callbacks,
                                                       stablear_session_config config);
STABLEAR_API void stablear_session_destroy(stablear_session* session);
STABLEAR_API uint64_t stablear_session_epoch(const stablear_session* session);
STABLEAR_API int stablear_session_capture(stablear_session* session,
                                         const stablear_rigid* world_from_camera,
                                         const stablear_intrinsics* intrinsics,
                                         int64_t camera_timestamp_ns,
                                         const stablear_depth_sample* depth,
                                         size_t depth_count,
                                         stablear_frame_ref* out_frame);
STABLEAR_API int stablear_session_freeze(stablear_session* session, uint64_t frame_id, stablear_frame_ref* out_frame);
STABLEAR_API void stablear_session_unfreeze(stablear_session* session, uint64_t frame_id);
STABLEAR_API int stablear_session_place(stablear_session* session,
                                       const stablear_frame_ref* frame,
                                       const stablear_depth_sample* depth,
                                       size_t depth_count,
                                       stablear_v2 pixel,
                                       stablear_attachment_snapshot* out_attachment);
STABLEAR_API int stablear_session_context(stablear_session* session,
                                         uint64_t attachment_id,
                                         const stablear_frame_ref* current_frame,
                                         stablear_rigid* out_camera_in_anchor,
                                         uint64_t* out_generation,
                                         uint64_t* out_anchor_id);
STABLEAR_API int stablear_session_observe(stablear_session* session,
                                         uint64_t attachment_id,
                                         const stablear_visual_observation* observation,
                                         stablear_attachment_snapshot* out_attachment,
                                         int* out_accepted);
STABLEAR_API int stablear_session_world_point(stablear_session* session, uint64_t attachment_id, stablear_v3* out_world_point);
STABLEAR_API int stablear_session_initial_world_point(stablear_session* session, uint64_t attachment_id, stablear_v3* out_world_point);
STABLEAR_API int stablear_session_snapshot(stablear_session* session, uint64_t attachment_id, stablear_attachment_snapshot* out_attachment);
STABLEAR_API void stablear_session_visibility(stablear_session* session, uint64_t attachment_id, int visible, int lost);
STABLEAR_API void stablear_session_remove(stablear_session* session, uint64_t attachment_id);
STABLEAR_API void stablear_session_tracking_lost(stablear_session* session);
STABLEAR_API void stablear_session_reset(stablear_session* session);

typedef int (*stablear_signature_verify_fn)(void* context,
                                            const uint8_t* signing_input, size_t signing_input_len,
                                            const uint8_t* signature, size_t signature_len);
typedef struct {
    int usable;
    int in_grace;
    char reason[128];
} stablear_entitlement_result;
STABLEAR_API stablear_entitlement_result stablear_entitlement_verify(
    const char* token, const char* expected_product, const char* expected_platform,
    const char* expected_app_id, const char* required_feature, int64_t now_unix_s,
    void* verifier_context, stablear_signature_verify_fn verifier);

#ifdef __cplusplus
}
#endif
