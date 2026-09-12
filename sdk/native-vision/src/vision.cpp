#include "stablear/vision.hpp"
#include <opencv2/calib3d.hpp>
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <algorithm>
#include <cmath>
#include <map>
#include <vector>
namespace stablear::vision { namespace {
constexpr double kPi=3.141592653589793238462643383279502884;
double median(std::vector<double> v){if(v.empty())return INFINITY;std::sort(v.begin(),v.end());return v[v.size()/2];}
bool enclosed(const std::vector<cv::Point2f>& a,const std::vector<int>& kept,const V2& root){
    std::vector<double> angles; angles.reserve(kept.size());
    for(int i:kept) angles.push_back(std::atan2(a[i].y-root.y,a[i].x-root.x));
    if(angles.size()<3)return false;std::sort(angles.begin(),angles.end());double gap=angles.front()+2*kPi-angles.back();
    for(size_t i=1;i<angles.size();++i)gap=std::max(gap,angles[i]-angles[i-1]);return gap<=kPi;
}
}
struct LocalSurfaceTracker::Impl {
    struct Ref { cv::Mat image; V2 root; std::vector<cv::Point2f> corners; std::vector<cv::KeyPoint> keys; cv::Mat desc; };
    std::map<uint64_t,Ref> refs;
    cv::Ptr<cv::ORB> orb=cv::ORB::create(800);
    cv::BFMatcher matcher{cv::NORM_HAMMING,true};
    uint64_t frame_id{}; bool has_frame=false,described=false; cv::Mat current; std::vector<cv::KeyPoint> current_keys; cv::Mat current_desc;

    std::optional<ImageMatch> geometry(const Ref& ref,const std::vector<cv::Point2f>& a,const std::vector<cv::Point2f>& b,double fb,const char* method){
        if(a.size()!=b.size()||a.size()<12)return std::nullopt;
        cv::Mat mask;cv::Mat h=cv::findHomography(a,b,cv::RANSAC,1.5,mask,2000,.995);if(h.empty())return std::nullopt;
        cv::Mat inv;if(cv::invert(h,inv,cv::DECOMP_SVD)<1e-9)return std::nullopt;
        std::vector<int> kept;kept.reserve(a.size());const unsigned char* mask_data=mask.ptr<unsigned char>();for(int i=0;i<mask.rows*mask.cols;++i)if(mask_data[i])kept.push_back(i);
        if(kept.size()<12||kept.size()<a.size()*.65||!enclosed(a,kept,ref.root))return std::nullopt;
        std::vector<cv::Point2f> projected,out,back;cv::perspectiveTransform(a,projected,h);cv::perspectiveTransform(std::vector<cv::Point2f>{{(float)ref.root.x,(float)ref.root.y}},out,h);
        if(out.empty()||!std::isfinite(out[0].x)||!std::isfinite(out[0].y)||out[0].x<0||out[0].y<0||out[0].x>=current.cols||out[0].y>=current.rows)return std::nullopt;
        std::vector<double> errors;errors.reserve(kept.size());for(int i:kept)errors.push_back(std::hypot(projected[i].x-b[i].x,projected[i].y-b[i].y));double reproj=median(errors);if(!std::isfinite(reproj)||reproj>1.0)return std::nullopt;
        cv::perspectiveTransform(b,back,inv);std::vector<double> rev;rev.reserve(kept.size());for(int i:kept)rev.push_back(std::hypot(back[i].x-a[i].x,back[i].y-a[i].y));double reverse=median(rev);if(!std::isfinite(reverse)||reverse>1.0)return std::nullopt;
        return ImageMatch{{out[0].x,out[0].y},(int)kept.size(),reproj,std::max(fb,reverse),method};
    }
    std::optional<ImageMatch> lk(const Ref& ref,V2 predicted){
        const V2 shift{predicted.x-ref.root.x,predicted.y-ref.root.y};std::vector<cv::Point2f> forward;forward.reserve(ref.corners.size());for(auto p:ref.corners)forward.push_back({p.x+(float)shift.x,p.y+(float)shift.y});
        std::vector<unsigned char> st,back_st;std::vector<float> err,back_err;cv::TermCriteria criteria(cv::TermCriteria::COUNT|cv::TermCriteria::EPS,25,.01);
        cv::calcOpticalFlowPyrLK(ref.image,current,ref.corners,forward,st,err,{21,21},3,criteria,cv::OPTFLOW_USE_INITIAL_FLOW);
        std::vector<cv::Point2f> back;cv::calcOpticalFlowPyrLK(current,ref.image,forward,back,back_st,back_err,{21,21},3,criteria);
        std::vector<cv::Point2f>a,b;std::vector<double>fbv;
        for(size_t i=0;i<ref.corners.size()&&i<forward.size()&&i<back.size()&&i<st.size()&&i<back_st.size();++i){double e=std::hypot(ref.corners[i].x-back[i].x,ref.corners[i].y-back[i].y);auto p=forward[i];if(st[i]&&back_st[i]&&e<=1.0&&p.x>=0&&p.y>=0&&p.x<current.cols&&p.y<current.rows){a.push_back(ref.corners[i]);b.push_back(p);fbv.push_back(e);}}
        if(a.size()<12)return std::nullopt;return geometry(ref,a,b,median(fbv),"LK_ROOT");
    }
};
LocalSurfaceTracker::LocalSurfaceTracker():impl_(std::make_unique<Impl>()){} LocalSurfaceTracker::~LocalSurfaceTracker()=default; LocalSurfaceTracker::LocalSurfaceTracker(LocalSurfaceTracker&&) noexcept=default; LocalSurfaceTracker& LocalSurfaceTracker::operator=(LocalSurfaceTracker&&) noexcept=default;
bool LocalSurfaceTracker::add(uint64_t id,const uint8_t* gray,int width,int height,V2 root){if(!id||!gray||width<=0||height<=0||!root.valid()||root.x<0||root.y<0||root.x>=width||root.y>=height||impl_->refs.count(id)||impl_->refs.size()>=64)return false;cv::Mat image(height,width,CV_8UC1,const_cast<uint8_t*>(gray));Impl::Ref ref;ref.image=image.clone();ref.root=root;cv::Mat mask=cv::Mat::zeros(height,width,CV_8UC1);cv::circle(mask,{(int)std::lround(root.x),(int)std::lround(root.y)},64,cv::Scalar(255),-1);cv::goodFeaturesToTrack(ref.image,ref.corners,120,.015,5.0,mask);impl_->orb->detectAndCompute(ref.image,mask,ref.keys,ref.desc);if(ref.corners.size()<12)return false;impl_->refs.emplace(id,std::move(ref));return true;}
bool LocalSurfaceTracker::beginFrame(uint64_t id,const uint8_t* gray,int width,int height){if(!id||!gray||width<=0||height<=0)return false;if(impl_->has_frame&&impl_->frame_id==id)return true;cv::Mat image(height,width,CV_8UC1,const_cast<uint8_t*>(gray));impl_->current=image.clone();impl_->current_keys.clear();impl_->current_desc.release();impl_->described=false;impl_->frame_id=id;impl_->has_frame=true;return true;}
std::optional<ImageMatch> LocalSurfaceTracker::track(uint64_t id,std::optional<V2> predicted){auto it=impl_->refs.find(id);if(it==impl_->refs.end()||impl_->current.empty()||impl_->current.size()!=it->second.image.size())return std::nullopt;if(predicted){auto v=impl_->lk(it->second,*predicted);if(v)return v;}if(!impl_->described){impl_->orb->detectAndCompute(impl_->current,cv::noArray(),impl_->current_keys,impl_->current_desc);impl_->described=true;}if(it->second.desc.empty()||impl_->current_desc.empty())return std::nullopt;std::vector<cv::DMatch>matches;impl_->matcher.match(it->second.desc,impl_->current_desc,matches);std::sort(matches.begin(),matches.end(),[](auto&a,auto&b){return a.distance<b.distance;});std::vector<cv::Point2f>a,b;for(auto&m:matches){if(m.distance>=48||a.size()>=100)break;if(m.queryIdx<0||m.trainIdx<0||m.queryIdx>=(int)it->second.keys.size()||m.trainIdx>=(int)impl_->current_keys.size())continue;a.push_back(it->second.keys[m.queryIdx].pt);b.push_back(impl_->current_keys[m.trainIdx].pt);}if(a.size()<12)return std::nullopt;return impl_->geometry(it->second,a,b,0.0,"ORB_ROOT");}
void LocalSurfaceTracker::remove(uint64_t id){impl_->refs.erase(id);}void LocalSurfaceTracker::clear(){impl_->refs.clear();impl_->current.release();impl_->current_keys.clear();impl_->current_desc.release();impl_->has_frame=false;impl_->described=false;impl_->frame_id=0;}
} // namespace stablear::vision
