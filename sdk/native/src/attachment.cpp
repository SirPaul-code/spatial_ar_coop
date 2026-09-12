#include "stablear/stablear.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>
namespace stablear { namespace { constexpr double kPi=3.141592653589793238462643383279502884; bool finite(double v){return std::isfinite(v);} }
bool LockPolicy::valid() const {return minimum_views>=3&&finite(minimum_parallax_deg)&&minimum_parallax_deg>0&&finite(max_correction_m)&&max_correction_m>0&&finite(total_travel_m)&&total_travel_m>0&&finite(max_conditional_sigma_m)&&max_conditional_sigma_m>0&&finite(assumed_common_translation_sigma_m)&&assumed_common_translation_sigma_m>=0&&finite(systematic_floor_m)&&systematic_floor_m>=0&&max_observation_age_ns>0;}
bool RootReference::valid()const{return frame_id>0&&epoch>0&&anchor_id>0&&camera_timestamp_ns>0&&camera_in_anchor.valid()&&intrinsics.valid()&&intrinsics.contains(pixel);}
bool VisualObservation::valid()const{return frame_id>0&&epoch>0&&anchor_id>0&&generation>0&&camera_timestamp_ns>0&&captured_ns>=0&&camera_in_anchor.valid()&&intrinsics.valid()&&intrinsics.contains(pixel)&&inliers>=0&&finite(forward_backward_px)&&forward_backward_px>=0&&finite(reprojection_px)&&reprojection_px>=0&&finite(sigma_px)&&sigma_px>0;}
V3 AttachmentSnapshot::pointInAnchor()const{return root.camera_in_anchor.point(root.intrinsics.ray(root.pixel)*depth_m);}

std::optional<LockProposal> RayRefiner::propose(const AttachmentSnapshot& s,const std::vector<VisualObservation>& observations,const LockPolicy& p){
    if(!s.root.valid()||!p.valid())return std::nullopt;
    const auto& root=s.root;const V3 ray=root.camera_in_anchor.q.rotate(root.intrinsics.ray(root.pixel));const V3 origin=root.camera_in_anchor.t;
    std::map<int64_t,VisualObservation> unique;
    for(const auto&o:observations)if(o.valid()&&o.epoch==root.epoch&&o.anchor_id==root.anchor_id&&o.generation==s.generation&&o.camera_timestamp_ns!=root.camera_timestamp_ns&&o.inliers>=12&&o.forward_backward_px<=1.0&&o.reprojection_px<=1.5)unique[o.camera_timestamp_ns]=o;
    std::vector<VisualObservation> obs; for(auto it=unique.rbegin();it!=unique.rend()&&obs.size()<12;++it)obs.push_back(it->second);std::reverse(obs.begin(),obs.end());if(static_cast<int>(obs.size())<p.minimum_views)return std::nullopt;
    auto project=[&](double z,const VisualObservation&o,V3 shift=V3{}) -> std::optional<V2>{return o.intrinsics.project(o.camera_in_anchor.q.inverse().rotate(origin+ray*z-o.camera_in_anchor.t-shift));};
    auto angle=[&](double z,const VisualObservation&o){const V3 point=origin+ray*z,a=point-origin,b=point-o.camera_in_anchor.t; if(a.norm()<1e-6||b.norm()<1e-6)return 0.0;return std::acos(std::clamp(a.dot(b)/(a.norm()*b.norm()),-1.0,1.0))*180.0/kPi;};
    double max_a=0;for(const auto&o:obs)max_a=std::max(max_a,angle(s.depth_m,o));if(max_a<p.minimum_parallax_deg)return std::nullopt;
    const double lo=std::max(.15,s.depth_m-p.max_correction_m),hi=std::min(8.0,s.depth_m+p.max_correction_m);double z=s.depth_m;const double prior_sigma=std::max(.025,s.conditional_sigma_m);
    auto solve=[&](const std::vector<VisualObservation>& active,bool robust){for(int it=0;it<15;++it){double h=1/(prior_sigma*prior_sigma),g=(z-s.depth_m)*h;for(const auto&o:active){auto pred=project(z,o),ap=project(z+1e-4,o),am=project(z-1e-4,o);if(!pred||!ap||!am)return false;V2 j{(ap->x-am->x)/2e-4/o.sigma_px,(ap->y-am->y)/2e-4/o.sigma_px};V2 r{(pred->x-o.pixel.x)/o.sigma_px,(pred->y-o.pixel.y)/o.sigma_px};double w=robust?std::min(1.0,2.0/std::max(1e-12,r.norm())):1.0;h+=w*(j.x*j.x+j.y*j.y);g+=w*(j.x*r.x+j.y*r.y);}double step=std::clamp(g/h,-.03,.03);z=std::clamp(z-step,lo,hi);}return true;};
    if (!solve(obs, true)) return std::nullopt;
    std::vector<VisualObservation> kept;
    for (const auto& o : obs) {
        auto px = project(z, o);
        if (!px) return std::nullopt;
        if ((*px - o.pixel).norm() <= 3 * o.sigma_px) kept.push_back(o);
    }
    if (static_cast<int>(kept.size()) < p.minimum_views || !solve(kept, false)) return std::nullopt;
    if (z - lo < 1e-5 || hi - z < 1e-5) return std::nullopt;
    for (const auto& o : kept) {
        auto px = project(z, o);
        if (!px || (*px - o.pixel).norm() > 3 * o.sigma_px) return std::nullopt;
    }
    double parallax=0;for(const auto&o:kept)parallax=std::max(parallax,angle(z,o));if(parallax<p.minimum_parallax_deg)return std::nullopt;
    double h=1/(prior_sigma*prior_sigma);std::array<double,3> cross{};for(const auto&o:kept){auto ap=project(z+1e-5,o),am=project(z-1e-5,o);if(!ap||!am)return std::nullopt;double j[2]{(ap->x-am->x)/2e-5/o.sigma_px,(ap->y-am->y)/2e-5/o.sigma_px};h+=j[0]*j[0]+j[1]*j[1];for(int axis=0;axis<3;++axis){V3 v{};if(axis==0)v.x=1e-5;else if(axis==1)v.y=1e-5;else v.z=1e-5;auto bp=project(z,o,v),bm=project(z,o,v*-1.0);if(!bp||!bm)return std::nullopt;cross[axis]+=j[0]*(bp->x-bm->x)/2e-5/o.sigma_px+j[1]*(bp->y-bm->y)/2e-5/o.sigma_px;}}
    double cross_term=0;for(double c:cross)cross_term+=(c/h)*(c/h);double sigma=std::sqrt(1/h+p.systematic_floor_m*p.systematic_floor_m+p.assumed_common_translation_sigma_m*p.assumed_common_translation_sigma_m*cross_term);if(sigma>p.max_conditional_sigma_m)return std::nullopt;
    return LockProposal{s.id,s.generation,root.epoch,root.anchor_id,z,sigma,parallax,kept,"Conditional geometry support; identity and systematic noise remain assumptions"};
}

AttachmentEngine::AttachmentEngine(Clock clock,LockPolicy policy):clock_(std::move(clock)),policy_(policy){if(!clock_||!policy_.valid())throw std::invalid_argument("invalid attachment engine");}
AttachmentSnapshot AttachmentEngine::create(const RootReference& root,const SurfaceFit& fit){if(!root.valid()||fit.depth<.15||fit.depth>8||!finite(fit.conditional_sigma)||fit.conditional_sigma<=0)throw std::invalid_argument("invalid attachment");if(entries_.size()>=64)throw std::runtime_error("Attachment capacity reached");AttachmentSnapshot s{++next_id_,1,root,fit.depth,fit.conditional_sigma,LockState::Unverified,0};entries_[s.id]=Entry{s,fit.depth,{},0};return s;}
std::optional<AttachmentSnapshot> AttachmentEngine::snapshot(uint64_t id)const{auto it=entries_.find(id);return it==entries_.end()?std::nullopt:std::optional<AttachmentSnapshot>(it->second.snapshot);}
std::vector<AttachmentSnapshot> AttachmentEngine::snapshots()const{std::vector<AttachmentSnapshot> out;for(const auto&p:entries_)out.push_back(p.second.snapshot);return out;}
void AttachmentEngine::remove(uint64_t id){entries_.erase(id);}void AttachmentEngine::clear(){entries_.clear();}
std::optional<LockProposal> AttachmentEngine::offer(uint64_t id,const VisualObservation&o){auto it=entries_.find(id);if(it==entries_.end()||!o.valid())return std::nullopt;auto&e=it->second;auto&s=e.snapshot;int64_t age=clock_()-o.captured_ns;if(o.epoch!=s.root.epoch||o.anchor_id!=s.root.anchor_id||o.generation!=s.generation||age<0||age>policy_.max_observation_age_ns||o.camera_timestamp_ns<=e.last_commit_ns||e.observations.count(o.camera_timestamp_ns))return std::nullopt;for(auto jt=e.observations.begin();jt!=e.observations.end();){int64_t a=clock_()-jt->second.captured_ns;jt=(a<0||a>policy_.max_observation_age_ns)?e.observations.erase(jt):std::next(jt);}if(!e.observations.empty()){const auto&prev=e.observations.rbegin()->second;if((prev.camera_in_anchor.t-o.camera_in_anchor.t).norm()<.015||o.camera_timestamp_ns-prev.camera_timestamp_ns<80'000'000LL)return std::nullopt;}e.observations[o.camera_timestamp_ns]=o;while(e.observations.size()>12)e.observations.erase(e.observations.begin());std::vector<VisualObservation> v;for(const auto&p:e.observations)v.push_back(p.second);return RayRefiner::propose(s,v,policy_);}
LockDecision AttachmentEngine::commit(const LockProposal& proposal,const VisualObservation& current){auto it=entries_.find(proposal.attachment_id);if(it==entries_.end())return{false,"Attachment removed",std::nullopt};auto&e=it->second;auto s=e.snapshot;auto reject=[&](std::string r){return LockDecision{false,std::move(r),s};};if(s.generation!=proposal.generation||s.root.epoch!=proposal.epoch||s.root.anchor_id!=proposal.anchor_id)return reject("Stale attachment generation or tracking space");int64_t age=clock_()-current.captured_ns;if(!current.valid()||current.epoch!=s.root.epoch||current.anchor_id!=s.root.anchor_id||current.generation!=s.generation||age<0||age>policy_.max_observation_age_ns)return reject("Invalid current observation");std::vector<VisualObservation> active;for(const auto&p:e.observations){int64_t a=clock_()-p.second.captured_ns;if(a>=0&&a<=policy_.max_observation_age_ns)active.push_back(p.second);}auto fit=RayRefiner::propose(s,active,policy_);if(!fit)return reject("Evidence no longer supports correction");if(std::abs(fit->depth_m-proposal.depth_m)>1e-8)return reject("Proposal no longer matches evidence");for(const auto&o:fit->observations)if(o.camera_timestamp_ns==current.camera_timestamp_ns)return reject("Need a new held-out observation");if(current.camera_timestamp_ns-fit->observations.back().camera_timestamp_ns<80'000'000LL)return reject("Need a new held-out observation");if(current.inliers<12||current.forward_backward_px>1||current.reprojection_px>1.5)return reject("Visual check failed");V3 point=s.root.camera_in_anchor.point(s.root.intrinsics.ray(s.root.pixel)*fit->depth_m);auto px=current.intrinsics.project(current.camera_in_anchor.inverse().point(point));if(!px)return reject("Behind camera");if((*px-current.pixel).norm()>2*current.sigma_px)return reject("Held-out reprojection failed");double travel=(point-s.pointInAnchor()).norm();if(travel>policy_.max_correction_m||s.travel_m+travel>policy_.total_travel_m||std::abs(fit->depth_m-e.seed_depth)*s.root.intrinsics.ray(s.root.pixel).norm()>policy_.total_travel_m)return reject("Correction travel limit");e.snapshot=s;e.snapshot.generation++;e.snapshot.depth_m=fit->depth_m;e.snapshot.conditional_sigma_m=fit->conditional_sigma_m;e.snapshot.state=LockState::GeometrySupported;e.snapshot.travel_m+=travel;e.last_commit_ns=current.camera_timestamp_ns;e.observations.clear();return{true,"Accepted with held-out evidence",e.snapshot};}
void AttachmentEngine::visibility(uint64_t id,bool visible,bool lost){auto it=entries_.find(id);if(it==entries_.end())return;it->second.snapshot.state=lost?LockState::Lost:(!visible?LockState::Occluded:LockState::Unverified);if(!visible||lost)it->second.observations.clear();}
const LockPolicy& AttachmentEngine::policy()const{return policy_;}


} // namespace stablear
