#include "stablear/stablear_c.h"
#include "stablear/stablear.hpp"

#include <chrono>
#include <cmath>
#include <memory>
#include <cstring>
#include <algorithm>
#include <optional>
#include <stdexcept>
#include <vector>

using namespace stablear;

namespace {
int64_t steady_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
V2 cv2(stablear_v2 v){ return {v.x,v.y}; }
V3 cv3(stablear_v3 v){ return {v.x,v.y,v.z}; }
Q cq(stablear_quat v){ return Q::normalized(v.x,v.y,v.z,v.w); }
Rigid cr(stablear_rigid v){ return {cv3(v.t),cq(v.q)}; }
Intrinsics ck(stablear_intrinsics v){ return {v.fx,v.fy,v.cx,v.cy,v.width,v.height}; }
stablear_v2 ov2(V2 v){ return {v.x,v.y}; }
stablear_v3 ov3(V3 v){ return {v.x,v.y,v.z}; }
stablear_quat oq(Q v){ return {v.x,v.y,v.z,v.w}; }
stablear_rigid origid(Rigid v){ return {ov3(v.t),oq(v.q)}; }
stablear_intrinsics ok(Intrinsics v){ return {v.fx,v.fy,v.cx,v.cy,v.width,v.height}; }
std::optional<DepthOrigin> origin(int v){
    switch(v){
        case 0:return DepthOrigin::Raw; case 1:return DepthOrigin::Smoothed;
        case 2:return DepthOrigin::PointCloud; case 3:return DepthOrigin::External;
        default:return std::nullopt;
    }
}
std::optional<DepthSample> cds(const stablear_depth_sample& v){
    auto o=origin(v.origin); if(!o||v.source_timestamp_ns<=0) return std::nullopt;
    if(!std::isfinite(v.pixel.x)||!std::isfinite(v.pixel.y)||!std::isfinite(v.z)||v.z<=0||
       !std::isfinite(v.confidence)||v.confidence<0||v.confidence>1) return std::nullopt;
    return DepthSample{cv2(v.pixel),v.z,v.confidence,{*o,v.source_timestamp_ns}};
}
FrameRef cfr(const stablear_frame_ref& f){
    return {f.id,f.epoch,f.camera_timestamp_ns,f.captured_ns,f.anchor_id,cr(f.anchor_from_camera),ck(f.intrinsics)};
}
stablear_frame_ref ofr(const FrameRef& f){
    return {f.id,f.epoch,f.anchor_id,f.camera_timestamp_ns,f.captured_ns,origid(f.anchor_from_camera),ok(f.intrinsics)};
}
stablear_attachment_snapshot os(const AttachmentSnapshot& s){
    return {s.id, s.generation, s.root.frame_id, s.root.epoch, s.root.anchor_id,
            s.root.camera_timestamp_ns, origid(s.root.camera_in_anchor), ok(s.root.intrinsics),
            ov2(s.root.pixel), s.depth_m, s.conditional_sigma_m, s.travel_m,
            static_cast<int>(s.state)};
}
std::optional<VisualObservation> co(const stablear_visual_observation& o){
    if(o.frame_id==0||o.epoch==0||o.anchor_id==0||o.generation==0||o.camera_timestamp_ns<=0||o.captured_ns<0) return std::nullopt;
    try{
        VisualObservation v{o.frame_id,o.epoch,o.anchor_id,o.generation,o.camera_timestamp_ns,o.captured_ns,
            cr(o.camera_in_anchor),ck(o.intrinsics),cv2(o.pixel),o.inliers,o.forward_backward_px,o.reprojection_px,o.sigma_px,{}};
        return v.valid()?std::optional<VisualObservation>(v):std::nullopt;
    }catch(...){return std::nullopt;}
}
LockPolicy cp(stablear_lock_policy p){
    return {p.minimum_views,p.minimum_parallax_deg,p.max_correction_m,p.total_travel_m,
        p.max_conditional_sigma_m,p.assumed_common_translation_sigma_m,p.systematic_floor_m,p.max_observation_age_ns};
}
SessionConfig cc(stablear_session_config c){
    return {{c.max_frames,c.history_ns,c.max_anchors,c.max_freeze_ns},cp(c.lock)};
}
class CallbackAnchors final: public AnchorStore{
public:
    explicit CallbackAnchors(stablear_anchor_callbacks cb):cb_(cb){
        if(!cb_.create||!cb_.locate||!cb_.destroy) throw std::invalid_argument("all anchor callbacks required");
    }
    std::optional<uint64_t> create(const Rigid& p) override{
        auto v=origid(p); auto id=cb_.create(cb_.context,&v); if(id==0) return std::nullopt; return id;
    }
    std::optional<Rigid> locate(uint64_t id) override{
        stablear_rigid v{}; if(!cb_.locate(cb_.context,id,&v)) return std::nullopt;
        try{return cr(v);}catch(...){return std::nullopt;}
    }
    void destroy(uint64_t id) override{cb_.destroy(cb_.context,id);}
private: stablear_anchor_callbacks cb_{};
};
}

struct stablear_session{
    CallbackAnchors anchors;
    Session session;
    stablear_session(stablear_anchor_callbacks cb, SessionConfig config):anchors(cb),session(anchors,steady_ns,config){}
};

extern "C" {
const char* stablear_version(void){return kVersion;}
uint32_t stablear_abi_version(void){return 1u;}
int64_t stablear_monotonic_now_ns(void){return steady_ns();}
stablear_session_config stablear_default_session_config(void){
    SessionConfig c{}; stablear_session_config out{};
    out.max_frames=c.ledger.max_frames; out.history_ns=c.ledger.history_ns; out.max_anchors=c.ledger.max_anchors; out.max_freeze_ns=c.ledger.max_freeze_ns;
    out.lock={c.lock.minimum_views,c.lock.minimum_parallax_deg,c.lock.max_correction_m,c.lock.total_travel_m,
        c.lock.max_conditional_sigma_m,c.lock.assumed_common_translation_sigma_m,c.lock.systematic_floor_m,c.lock.max_observation_age_ns};
    return out;
}
stablear_session* stablear_session_create(stablear_anchor_callbacks cb, stablear_session_config cfg){
    try{auto c=cc(cfg); if(!c.lock.valid()||c.ledger.max_frames<=0||c.ledger.max_anchors<=0||c.ledger.history_ns<=0||c.ledger.max_freeze_ns<=0)return nullptr; return new stablear_session(cb,c);}catch(...){return nullptr;}
}
void stablear_session_destroy(stablear_session* s){delete s;}
uint64_t stablear_session_epoch(const stablear_session* s){return s?s->session.epoch():0;}
int stablear_session_capture(stablear_session* s,const stablear_rigid* pose,const stablear_intrinsics* k,int64_t ts,const stablear_depth_sample* depth,size_t n,stablear_frame_ref* out){
    if (!s || !pose || !k || !out || ts <= 0 || (n && !depth)) return 0;
    try {
        std::vector<DepthSample> d;
        d.reserve(n);
        for (size_t i = 0; i < n; ++i) { auto x = cds(depth[i]); if (!x) return 0; d.push_back(*x); }
        auto r = s->session.capture(cr(*pose), ck(*k), ts, std::move(d));
        if (!r) return 0;
        *out = ofr(r->frame);
        return 1;
    } catch (...) { return 0; }}
int stablear_session_freeze(stablear_session* s,uint64_t id,stablear_frame_ref* out){if(!s||!out||!id)return 0;try{auto f=s->session.freeze(id);if(!f)return 0;*out=ofr(*f);return 1;}catch(...){return 0;}}
void stablear_session_unfreeze(stablear_session* s,uint64_t id){if(s&&id)try{s->session.unfreeze(id);}catch(...){}}
int stablear_session_place(stablear_session* s,const stablear_frame_ref* frame,const stablear_depth_sample* depth,size_t n,stablear_v2 pixel,stablear_attachment_snapshot* out){
    if (!s || !frame || !out || (n && !depth)) return 0;
    try {
        std::vector<DepthSample> d;
        d.reserve(n);
        for (size_t i = 0; i < n; ++i) { auto x = cds(depth[i]); if (!x) return 0; d.push_back(*x); }
        CameraSample sample{cfr(*frame), std::move(d)};
        auto p = s->session.place(sample, cv2(pixel));
        if (!p) return 0;
        *out = os(p->attachment);
        return 1;
    } catch (...) { return 0; }}
int stablear_session_context(stablear_session* s,uint64_t id,const stablear_frame_ref* f,stablear_rigid* out,uint64_t* gen,uint64_t* anchor){
    if (!s || !f || !out || !gen || !anchor) return 0;
    try {
        CameraSample sample{cfr(*f), {}};
        auto c = s->session.context(id, sample);
        if (!c) return 0;
        *out = origid(c->camera_in_anchor); *gen = c->generation; *anchor = c->root.anchor_id;
        return 1;
    } catch (...) { return 0; }}
int stablear_session_observe(stablear_session* s,uint64_t id,const stablear_visual_observation* o,stablear_attachment_snapshot* out,int* accepted){
    if (!s || !o || !out || !accepted) return 0;
    auto v = co(*o);
    if (!v) return 0;
    try {
        auto d = s->session.observe(id, *v);
        *accepted = d.accepted ? 1 : 0;
        if (d.snapshot) *out = os(*d.snapshot); else *out = {};
        return d.snapshot ? 1 : 0;
    } catch (...) { return 0; }}
int stablear_session_world_point(stablear_session* s,uint64_t id,stablear_v3* out){if(!s||!out)return 0;try{auto p=s->session.worldPoint(id);if(!p)return 0;*out=ov3(*p);return 1;}catch(...){return 0;}}
int stablear_session_initial_world_point(stablear_session* s,uint64_t id,stablear_v3* out){if(!s||!out)return 0;try{auto p=s->session.initialWorldPoint(id);if(!p)return 0;*out=ov3(*p);return 1;}catch(...){return 0;}}
int stablear_session_snapshot(stablear_session* s,uint64_t id,stablear_attachment_snapshot* out){if(!s||!out)return 0;try{auto p=s->session.snapshot(id);if(!p)return 0;*out=os(*p);return 1;}catch(...){return 0;}}
void stablear_session_visibility(stablear_session* s,uint64_t id,int visible,int lost){if(s)try{s->session.visibility(id,visible!=0,lost!=0);}catch(...){}}
void stablear_session_remove(stablear_session* s,uint64_t id){if(s)try{s->session.remove(id);}catch(...){}}
void stablear_session_tracking_lost(stablear_session* s){if(s)try{s->session.trackingLost();}catch(...){}}
void stablear_session_reset(stablear_session* s){if(s)try{s->session.reset();}catch(...){}}

stablear_entitlement_result stablear_entitlement_verify(
    const char* token, const char* product, const char* platform, const char* app_id,
    const char* feature, int64_t now, void* verifier_context, stablear_signature_verify_fn verifier) {
    stablear_entitlement_result out{};
    auto set_reason = [&](const std::string& reason) {
        const size_t n = std::min(reason.size(), sizeof(out.reason) - 1);
        std::memcpy(out.reason, reason.data(), n);
        out.reason[n] = '\0';
    };
    if (!token || !product || !platform || !app_id || !feature || !verifier) {
        set_reason("Invalid entitlement arguments"); return out;
    }
    try {
        auto status = EntitlementGate::verify(token, product, platform, app_id, feature, now,
            [&](const std::string& input, const std::vector<uint8_t>& sig) {
                return verifier(verifier_context,
                    reinterpret_cast<const uint8_t*>(input.data()), input.size(),
                    sig.data(), sig.size()) != 0;
            });
        out.usable = status.usable ? 1 : 0;
        out.in_grace = status.in_grace ? 1 : 0;
        set_reason(status.reason);
        return out;
    } catch (...) {
        set_reason("Entitlement verification failed"); return out;
    }
}
}
