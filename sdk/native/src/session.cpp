#include "stablear/stablear.hpp"
#include <stdexcept>
#include <vector>
namespace stablear {
Session::Session(AnchorStore& anchors,Clock clock,SessionConfig config):anchors_(anchors),clock_(clock),ledger_(anchors,clock,config.ledger),engine_(clock,config.lock){if(!clock_)throw std::invalid_argument("clock required");}
Session::~Session(){try{reset();}catch(...){}}
std::optional<CameraSample> Session::capture(const Rigid& world_from_camera,const Intrinsics& intrinsics,int64_t ts,std::vector<DepthSample> depth){auto f=ledger_.capture(world_from_camera,intrinsics,ts);if(!f)return std::nullopt;return CameraSample{*f,std::move(depth)};}
std::optional<FrameRef> Session::freeze(uint64_t id){return ledger_.freeze(id);}void Session::unfreeze(uint64_t id){ledger_.unfreeze(id);}
std::optional<Placement> Session::place(const CameraSample& sample,const V2& pixel){
    if(attachment_anchors_.size()>=64)return std::nullopt;
    auto canonical=ledger_.get(sample.frame.id);
    if(!canonical||canonical->epoch!=sample.frame.epoch||canonical->camera_timestamp_ns!=sample.frame.camera_timestamp_ns||canonical->anchor_id!=sample.frame.anchor_id)return std::nullopt;
    auto current=ledger_.currentWorldFromCamera(*canonical);if(!current)return std::nullopt;
    auto fit=SurfaceFitter::fit(canonical->intrinsics,pixel,sample.depth);if(!fit)return std::nullopt;
    V3 world=current->point(canonical->intrinsics.ray(pixel)*fit->depth);
    auto anchor=anchors_.create(Rigid{world,Q::identity()});if(!anchor||*anchor==0)return std::nullopt;
    auto pose=anchors_.locate(*anchor);if(!pose){anchors_.destroy(*anchor);return std::nullopt;}
    RootReference root{canonical->id,canonical->epoch,*anchor,canonical->camera_timestamp_ns,pose->inverse()*(*current),canonical->intrinsics,pixel};
    try{auto s=engine_.create(root,*fit);attachment_anchors_[s.id]=*anchor;initial_points_[s.id]=s.pointInAnchor();return Placement{s,*fit};}catch(...){anchors_.destroy(*anchor);throw;}
}
std::optional<ObservationContext> Session::context(uint64_t id,const CameraSample& current){
    auto s=engine_.snapshot(id);if(!s)return std::nullopt;
    auto ait=attachment_anchors_.find(id);if(ait==attachment_anchors_.end())return std::nullopt;
    auto canonical=ledger_.get(current.frame.id);
    if(!canonical||canonical->epoch!=current.frame.epoch||canonical->camera_timestamp_ns!=current.frame.camera_timestamp_ns||canonical->anchor_id!=current.frame.anchor_id)return std::nullopt;
    auto anchor=anchors_.locate(ait->second),camera=ledger_.currentWorldFromCamera(*canonical);
    if(!anchor||!camera||canonical->epoch!=ledger_.epoch())return std::nullopt;
    return ObservationContext{id,s->generation,s->root,*canonical,anchor->inverse()*(*camera)};
}
LockDecision Session::observe(uint64_t id,const VisualObservation&o){
    auto s=engine_.snapshot(id);
    auto ait=attachment_anchors_.find(id);
    if(ait==attachment_anchors_.end()||!anchors_.locate(ait->second))return{false,"Anchor is not tracking",s};
    auto p=pending_.find(id);
    if(p!=pending_.end()){
        if(o.generation!=p->second.generation){
            pending_.erase(p);
        }else{
            auto d=engine_.commit(p->second,o);
            if(d.accepted){pending_.erase(id);return d;}
            // A frame used to challenge a proposal is held-out evidence. Never recycle a failed
            // validation frame into the solver that produced the proposal (self-confirming drift).
            if(d.reason!="Need a new held-out observation")pending_.erase(id);
            return{false,std::string("Held-out validation: ")+d.reason,d.snapshot};
        }
    }
    auto next=engine_.offer(id,o);
    if(next)pending_[id]=*next;
    return{false,"Collecting diverse independent views",engine_.snapshot(id)};
}
std::optional<V3> Session::worldPoint(uint64_t id){auto s=engine_.snapshot(id);auto a=attachment_anchors_.find(id);if(!s||a==attachment_anchors_.end())return std::nullopt;auto pose=anchors_.locate(a->second);return pose?std::optional<V3>(pose->point(s->pointInAnchor())):std::nullopt;}
std::optional<V3> Session::initialWorldPoint(uint64_t id){auto p=initial_points_.find(id);auto a=attachment_anchors_.find(id);if(p==initial_points_.end()||a==attachment_anchors_.end())return std::nullopt;auto pose=anchors_.locate(a->second);return pose?std::optional<V3>(pose->point(p->second)):std::nullopt;}
std::optional<AttachmentSnapshot> Session::snapshot(uint64_t id)const{return engine_.snapshot(id);}void Session::visibility(uint64_t id,bool visible,bool lost){engine_.visibility(id,visible,lost);if(!visible||lost)pending_.erase(id);}void Session::remove(uint64_t id){auto a=attachment_anchors_.find(id);if(a!=attachment_anchors_.end()){anchors_.destroy(a->second);attachment_anchors_.erase(a);}engine_.remove(id);pending_.erase(id);initial_points_.erase(id);}void Session::trackingLost(){pending_.clear();for(const auto&s:engine_.snapshots())engine_.visibility(s.id,false,true);}void Session::reset(){std::vector<uint64_t> ids;for(const auto&p:attachment_anchors_)ids.push_back(p.first);for(auto id:ids)remove(id);engine_.clear();pending_.clear();initial_points_.clear();ledger_.reset();}uint64_t Session::epoch()const{return ledger_.epoch();}


} // namespace stablear
