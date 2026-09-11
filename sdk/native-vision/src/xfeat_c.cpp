#include "stablear/vision_c.h"
#include "stablear/xfeat.hpp"

struct stablear_xfeat_tracker {
    stablear_xfeat_tracker() : tracker(stablear::vision::XFeatPolicy{}) {}
    stablear::vision::XFeatLocalTracker tracker;
};

namespace {
stablear::vision::XFeatMapView mapView(const float* descriptors,const float* reliability,int cw,int ch,int iw,int ih){
    return {descriptors,reliability,cw,ch,64,iw,ih};
}
stablear::vision::XFeatView xview(int has,double x,double y,double z,double scale,double reliability){
    stablear::vision::XFeatView result;
    if(has) result.direction=stablear::V3{x,y,z};
    result.scale=scale;
    result.reliability=reliability;
    return result;
}
}

extern "C" {
stablear_xfeat_tracker* stablear_xfeat_create(void){try{return new stablear_xfeat_tracker();}catch(...){return nullptr;}}
void stablear_xfeat_destroy(stablear_xfeat_tracker*t){delete t;}
int stablear_xfeat_add_root(stablear_xfeat_tracker*t,uint64_t id,const float*d,const float*r,int cw,int ch,int iw,int ih,double x,double y){if(!t||!id||!d)return 0;try{return t->tracker.add(id,mapView(d,r,cw,ch,iw,ih),{x,y})?1:0;}catch(...){return 0;}}
int stablear_xfeat_add_template(stablear_xfeat_tracker*t,uint64_t id,const float*d,const float*r,int cw,int ch,int iw,int ih,double x,double y,int has,double vx,double vy,double vz,double scale,double quality){if(!t||!id||!d)return 0;try{return t->tracker.addTemplate(id,mapView(d,r,cw,ch,iw,ih),{x,y},xview(has,vx,vy,vz,scale,quality))?1:0;}catch(...){return 0;}}
int stablear_xfeat_begin_frame(stablear_xfeat_tracker*t,uint64_t id,const float*d,const float*r,int cw,int ch,int iw,int ih){if(!t||!id||!d)return 0;try{return t->tracker.beginFrame(id,mapView(d,r,cw,ch,iw,ih))?1:0;}catch(...){return 0;}}
int stablear_xfeat_track(stablear_xfeat_tracker*t,uint64_t id,double px,double py,int has,double vx,double vy,double vz,double scale,double quality,stablear_xfeat_match*out){if(!t||!id||!out)return 0;try{auto match=t->tracker.track(id,{px,py},xview(has,vx,vy,vz,scale,quality));if(!match)return 0;out->image={match->image.pixel.x,match->image.pixel.y,match->image.inliers,match->image.median_reprojection_px,match->image.forward_backward_px,STABLEAR_VISION_METHOD_XFEAT_PATCH};out->score=match->score;out->second_best_score=match->second_best_score;out->score_margin=match->score_margin;out->mean_reliability=match->mean_reliability;out->sigma_px=match->sigma_px;out->template_serial=match->template_serial;return 1;}catch(...){return 0;}}
void stablear_xfeat_remove(stablear_xfeat_tracker*t,uint64_t id){if(t&&id)try{t->tracker.remove(id);}catch(...){}}
void stablear_xfeat_clear(stablear_xfeat_tracker*t){if(t)try{t->tracker.clear();}catch(...){}}
}
