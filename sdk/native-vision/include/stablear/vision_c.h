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
typedef enum stablear_vision_method { STABLEAR_VISION_METHOD_NONE=0, STABLEAR_VISION_METHOD_LK_ROOT=1, STABLEAR_VISION_METHOD_ORB_ROOT=2 } stablear_vision_method;
typedef struct stablear_vision_match { double x,y; int inliers; double median_reprojection_px,forward_backward_px; int method; } stablear_vision_match;
STABLEAR_VISION_API uint32_t stablear_vision_abi_version(void);
STABLEAR_VISION_API stablear_vision_tracker* stablear_vision_create(void);
STABLEAR_VISION_API void stablear_vision_destroy(stablear_vision_tracker* tracker);
STABLEAR_VISION_API int stablear_vision_add_root(stablear_vision_tracker* tracker,uint64_t attachment_id,const uint8_t* gray,int width,int height,double root_x,double root_y);
STABLEAR_VISION_API int stablear_vision_begin_frame(stablear_vision_tracker* tracker,uint64_t frame_id,const uint8_t* gray,int width,int height);
STABLEAR_VISION_API int stablear_vision_track(stablear_vision_tracker* tracker,uint64_t attachment_id,int has_prediction,double predicted_x,double predicted_y,stablear_vision_match* out_match);
STABLEAR_VISION_API void stablear_vision_remove(stablear_vision_tracker* tracker,uint64_t attachment_id);
STABLEAR_VISION_API void stablear_vision_clear(stablear_vision_tracker* tracker);
#ifdef __cplusplus
}
#endif
