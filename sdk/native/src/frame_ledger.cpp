#include "stablear/stablear.hpp"
#include <limits>
#include <set>
#include <stdexcept>
#include <vector>
namespace stablear {
FrameLedger::FrameLedger(AnchorStore& anchors, Clock clock, FrameLedgerConfig config)
    : anchors_(anchors), clock_(std::move(clock)), config_(config) {
    if (!clock_ || config_.max_frames<=0 || config_.history_ns<=0 || config_.max_anchors<=0 || config_.max_freeze_ns<=0)
        throw std::invalid_argument("invalid FrameLedger configuration");
}
FrameLedger::~FrameLedger(){ try { reset(); } catch (...) {} }

void FrameLedger::prune() {
    const int64_t now=clock_();
    for(auto it=frozen_.begin();it!=frozen_.end();) it=now>it->second.deadline?frozen_.erase(it):std::next(it);
    for(auto it=frames_.begin();it!=frames_.end();) {
        const int64_t age=now-it->second.captured_ns;
        it=(age<0||age>config_.history_ns)?frames_.erase(it):std::next(it);
    }
    while(static_cast<int>(frames_.size())>config_.max_frames) frames_.erase(frames_.begin());
    std::set<uint64_t> used;
    for(const auto& p:frames_)used.insert(p.second.anchor_id);
    for(const auto& p:frozen_)used.insert(p.second.frame.anchor_id);
    std::vector<uint64_t> dead;
    for(const auto& p:slots_) if(p.second.holds==0 && !used.count(p.first) && now-p.second.last_use>config_.history_ns) dead.push_back(p.first);
    for(uint64_t id:dead){ anchors_.destroy(id); slots_.erase(id); }
}

std::optional<FrameRef> FrameLedger::capture(const Rigid& world_from_camera,const Intrinsics& k,int64_t timestamp_ns) {
    if(timestamp_ns<=0||!k.valid()||!world_from_camera.valid()) return std::nullopt;
    prune();
    std::optional<uint64_t> nearest; double best=std::numeric_limits<double>::infinity();
    for(const auto& p:slots_) {
        auto pose=anchors_.locate(p.first); if(!pose||!pose->valid())continue;
        double d=(pose->t-world_from_camera.t).norm(); if(d<best){best=d;nearest=p.first;}
    }
    uint64_t id{};
    if(!nearest||best>.75) {
        if(static_cast<int>(slots_.size())>=config_.max_anchors){
            std::set<uint64_t> referenced;
            for(const auto& p:frames_)referenced.insert(p.second.anchor_id);
            for(const auto& p:frozen_)referenced.insert(p.second.frame.anchor_id);
            std::optional<uint64_t> old; int64_t oldest=std::numeric_limits<int64_t>::max();
            for(const auto& p:slots_)if(p.second.holds==0&&!referenced.count(p.first)&&p.second.last_use<oldest){old=p.first;oldest=p.second.last_use;}
            if(old){anchors_.destroy(*old);slots_.erase(*old);}
        }
        if(static_cast<int>(slots_.size())>=config_.max_anchors)return std::nullopt;
        auto created=anchors_.create(world_from_camera); if(!created||*created==0||slots_.count(*created))return std::nullopt;
        id=*created; slots_[id]=Slot{id,clock_(),0};
    } else id=*nearest;
    auto world_from_anchor=anchors_.locate(id); if(!world_from_anchor||!world_from_anchor->valid())return std::nullopt;
    slots_[id].last_use=clock_();
    FrameRef f{++last_frame_id_,epoch_,timestamp_ns,clock_(),id,world_from_anchor->inverse()*world_from_camera,k};
    frames_[f.id]=f; prune(); return f;
}
std::optional<FrameRef> FrameLedger::get(uint64_t id){prune();auto f=frozen_.find(id);if(f!=frozen_.end())return f->second.frame;auto it=frames_.find(id);return it==frames_.end()?std::nullopt:std::optional<FrameRef>(it->second);}
std::optional<FrameRef> FrameLedger::freeze(uint64_t id){prune();auto x=frozen_.find(id);if(x!=frozen_.end())return x->second.frame;auto f=frames_.find(id);if(f==frames_.end()||frozen_.size()>=4)return std::nullopt;frozen_[id]=Frozen{f->second,clock_()+config_.max_freeze_ns};return f->second;}
void FrameLedger::unfreeze(uint64_t id){frozen_.erase(id);prune();}
std::optional<Rigid> FrameLedger::currentWorldFromCamera(const FrameRef& f){if(f.epoch!=epoch_)return std::nullopt;auto held=get(f.id);if(!held||held->epoch!=f.epoch||held->camera_timestamp_ns!=f.camera_timestamp_ns||held->anchor_id!=f.anchor_id)return std::nullopt;auto a=anchors_.locate(held->anchor_id);return a&&a->valid()?std::optional<Rigid>((*a)*held->anchor_from_camera):std::nullopt;}
std::optional<Rigid> FrameLedger::worldFromAnchor(uint64_t id){auto p=anchors_.locate(id);return p&&p->valid()?p:std::nullopt;}
bool FrameLedger::retain(uint64_t id){auto it=slots_.find(id);if(it==slots_.end())return false;++it->second.holds;return true;}
void FrameLedger::release(uint64_t id){auto it=slots_.find(id);if(it==slots_.end()||it->second.holds<=0)throw std::logic_error("release without retain");--it->second.holds;prune();}
size_t FrameLedger::anchorCount()const{return slots_.size();}
size_t FrameLedger::frameCount(){prune();return frames_.size();}
uint64_t FrameLedger::epoch()const{return epoch_;}
void FrameLedger::reset(){for(const auto& p:slots_)anchors_.destroy(p.first);slots_.clear();frames_.clear();frozen_.clear();++epoch_;}


} // namespace stablear
