#pragma once
#include <stddef.h>
#include <stdint.h>
#if defined(_WIN32)
 #if defined(STABLEAR_VISION_BUILDING_DLL)
  #define STABLEAR_VISION_API __declspec(dllexport)
 #else
  #define STABLEAR_VISION_API __declspec(dllimport)
 #endif
#else
 #define STABLEAR_VISION_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

typedef struct stablear_vision_tracker stablear_vision_tracker;
typedef struct stablear_xfeat_tracker stablear_xfeat_tracker;
typedef enum stablear_vision_method {
    STABLEAR_VISION_METHOD_NONE=0,
    STABLEAR_VISION_METHOD_LK_ROOT=1,
    STABLEAR_VISION_METHOD_ORB_ROOT=2,
    STABLEAR_VISION_METHOD_XFEAT_PATCH=3
} stablear_vision_method;
typedef struct stablear_vision_match {
    double x,y;
    int inliers;
    double median_reprojection_px,forward_backward_px;
    int method;
} stablear_vision_match;
typedef struct stablear_xfeat_match {
    stablear_vision_match image;
    double score,second_best_score,score_margin,mean_reliability,sigma_px;
    uint64_t template_serial;
} stablear_xfeat_match;

STABLEAR_VISION_API uint32_t stablear_vision_abi_version(void);
STABLEAR_VISION_API stablear_vision_tracker* stablear_vision_create(void);
STABLEAR_VISION_API void stablear_vision_destroy(stablear_vision_tracker* tracker);
STABLEAR_VISION_API int stablear_vision_add_root(stablear_vision_tracker* tracker,uint64_t attachment_id,const uint8_t* gray,int width,int height,double root_x,double root_y);
STABLEAR_VISION_API int stablear_vision_begin_frame(stablear_vision_tracker* tracker,uint64_t frame_id,const uint8_t* gray,int width,int height);
STABLEAR_VISION_API int stablear_vision_track(stablear_vision_tracker* tracker,uint64_t attachment_id,int has_prediction,double predicted_x,double predicted_y,stablear_vision_match* out_match);
STABLEAR_VISION_API void stablear_vision_remove(stablear_vision_tracker* tracker,uint64_t attachment_id);
STABLEAR_VISION_API void stablear_vision_clear(stablear_vision_tracker* tracker);

/* XFeat dense-map matcher. Inference stays outside this ABI; buffers must remain valid for the call. */
STABLEAR_VISION_API stablear_xfeat_tracker* stablear_xfeat_create(void);
STABLEAR_VISION_API void stablear_xfeat_destroy(stablear_xfeat_tracker* tracker);
STABLEAR_VISION_API int stablear_xfeat_add_root(stablear_xfeat_tracker* tracker,uint64_t attachment_id,const float* descriptors,const float* reliability,int cells_width,int cells_height,int image_width,int image_height,double root_x,double root_y);
STABLEAR_VISION_API int stablear_xfeat_add_template(stablear_xfeat_tracker* tracker,uint64_t attachment_id,const float* descriptors,const float* reliability,int cells_width,int cells_height,int image_width,int image_height,double pixel_x,double pixel_y,int has_view_direction,double view_x,double view_y,double view_z,double scale,double reliability_score);
STABLEAR_VISION_API int stablear_xfeat_begin_frame(stablear_xfeat_tracker* tracker,uint64_t frame_id,const float* descriptors,const float* reliability,int cells_width,int cells_height,int image_width,int image_height);
STABLEAR_VISION_API int stablear_xfeat_track(stablear_xfeat_tracker* tracker,uint64_t attachment_id,double predicted_x,double predicted_y,int has_view_direction,double view_x,double view_y,double view_z,double scale,double reliability_score,stablear_xfeat_match* out_match);
/*
 * Safe asynchronous template admission. stage copies only the local descriptor patch from the
 * current exact frame and returns an opaque token. It does NOT alter the active template bank.
 * A newer staged candidate for the same attachment invalidates the older token.
 */
STABLEAR_VISION_API uint64_t stablear_xfeat_stage_template(stablear_xfeat_tracker* tracker,uint64_t attachment_id,double pixel_x,double pixel_y,int has_view_direction,double view_x,double view_y,double view_z,double scale,double reliability_score);
STABLEAR_VISION_API int stablear_xfeat_commit_staged_template(stablear_xfeat_tracker* tracker,uint64_t attachment_id,uint64_t token);
STABLEAR_VISION_API void stablear_xfeat_discard_staged_template(stablear_xfeat_tracker* tracker,uint64_t attachment_id,uint64_t token);
STABLEAR_VISION_API void stablear_xfeat_remove(stablear_xfeat_tracker* tracker,uint64_t attachment_id);
STABLEAR_VISION_API void stablear_xfeat_clear(stablear_xfeat_tracker* tracker);
#ifdef __cplusplus
}
#endif
