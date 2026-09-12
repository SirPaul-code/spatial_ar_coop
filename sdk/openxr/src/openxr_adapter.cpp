#include "stablear/openxr_adapter.hpp"
#include <stdexcept>
namespace stablear::openxr { namespace {
Q q(const XrQuaternionf& v){return Q::normalized(v.x,v.y,v.z,v.w);} V3 v(const XrVector3f&p){return{p.x,p.y,p.z};}
XrQuaternionf xq(Q p){return{(float)p.x,(float)p.y,(float)p.z,(float)p.w};} XrVector3f xv(V3 p){return{(float)p.x,(float)p.y,(float)p.z};}
}
Rigid stableWorldPose(const XrPosef&p){return{v(p.position),q(p.orientation)};}
XrPosef xrPose(const Rigid&p){if(!p.valid())throw std::invalid_argument("invalid StableAR pose");return{xq(p.q),xv(p.t)};}
Rigid stableCameraPose(const XrPosef&p){const Rigid gl=stableWorldPose(p);const Rigid cv{{0,0,0},Q::normalized(1,0,0,0)};return gl*cv;}
LocalReferenceAnchorStore::LocalReferenceAnchorStore(XrSession s,XrSpace local):session_(s),local_(local){if(s==XR_NULL_HANDLE||local==XR_NULL_HANDLE)throw std::invalid_argument("OpenXR session/local space required");}
LocalReferenceAnchorStore::~LocalReferenceAnchorStore(){clear();}
void LocalReferenceAnchorStore::setLocateTime(XrTime t){time_=t;}
std::optional<uint64_t> LocalReferenceAnchorStore::create(const Rigid& pose){if(!pose.valid())return std::nullopt;XrReferenceSpaceCreateInfo info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};info.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;info.poseInReferenceSpace=xrPose(pose);XrSpace s=XR_NULL_HANDLE;if(XR_FAILED(xrCreateReferenceSpace(session_,&info,&s))||s==XR_NULL_HANDLE)return std::nullopt;const uint64_t id=++next_;spaces_[id]=s;return id;}
std::optional<Rigid> LocalReferenceAnchorStore::locate(uint64_t id){auto it=spaces_.find(id);if(it==spaces_.end()||time_<=0)return std::nullopt;XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};if(XR_FAILED(xrLocateSpace(it->second,local_,time_,&loc)))return std::nullopt;const XrSpaceLocationFlags needed=XR_SPACE_LOCATION_POSITION_VALID_BIT|XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;if((loc.locationFlags&needed)!=needed)return std::nullopt;try{return stableWorldPose(loc.pose);}catch(...){return std::nullopt;}}
void LocalReferenceAnchorStore::destroy(uint64_t id){auto it=spaces_.find(id);if(it!=spaces_.end()){xrDestroySpace(it->second);spaces_.erase(it);}}
void LocalReferenceAnchorStore::clear(){for(auto&p:spaces_)if(p.second!=XR_NULL_HANDLE)xrDestroySpace(p.second);spaces_.clear();}
} // namespace stablear::openxr
