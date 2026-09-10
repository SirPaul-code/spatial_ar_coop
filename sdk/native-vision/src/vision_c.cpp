#include "stablear/vision_c.h"
#include "stablear/vision.hpp"
#include <optional>
struct stablear_vision_tracker{stablear::vision::LocalSurfaceTracker tracker;};
extern "C" {
uint32_t stablear_vision_abi_version(void){return 1u;}
stablear_vision_tracker* stablear_vision_create(void){try{return new stablear_vision_tracker{};}catch(...){return nullptr;}}
void stablear_vision_destroy(stablear_vision_tracker*t){delete t;}
int stablear_vision_add_root(stablear_vision_tracker*t,uint64_t id,const uint8_t*g,int w,int h,double x,double y){if(!t||!id||!g||w<=0||h<=0)return 0;try{return t->tracker.add(id,g,w,h,{x,y})?1:0;}catch(...){return 0;}}
int stablear_vision_begin_frame(stablear_vision_tracker*t,uint64_t id,const uint8_t*g,int w,int h){if(!t||!id||!g||w<=0||h<=0)return 0;try{return t->tracker.beginFrame(id,g,w,h)?1:0;}catch(...){return 0;}}
int stablear_vision_track(stablear_vision_tracker*t,uint64_t id,int has,double px,double py,stablear_vision_match*out){if(!t||!id||!out)return 0;try{std::optional<stablear::V2>p;if(has)p=stablear::V2{px,py};auto r=t->tracker.track(id,p);if(!r)return 0;int method=STABLEAR_VISION_METHOD_NONE;if(r->method=="LK_ROOT")method=STABLEAR_VISION_METHOD_LK_ROOT;else if(r->method=="ORB_ROOT")method=STABLEAR_VISION_METHOD_ORB_ROOT;*out={r->pixel.x,r->pixel.y,r->inliers,r->median_reprojection_px,r->forward_backward_px,method};return 1;}catch(...){return 0;}}
void stablear_vision_remove(stablear_vision_tracker*t,uint64_t id){if(t&&id)try{t->tracker.remove(id);}catch(...){}}
void stablear_vision_clear(stablear_vision_tracker*t){if(t)try{t->tracker.clear();}catch(...){}}
}
