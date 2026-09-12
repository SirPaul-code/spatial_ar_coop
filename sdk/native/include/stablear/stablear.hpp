#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

namespace stablear {

constexpr const char* kVersion = "0.2.0-research";

struct V2 {
    double x{}, y{};
    bool valid() const;
    V2 operator-(const V2& b) const;
    double norm() const;
};

struct V3 {
    double x{}, y{}, z{};
    bool valid() const;
    V3 operator+(const V3& b) const;
    V3 operator-(const V3& b) const;
    V3 operator*(double s) const;
    double dot(const V3& b) const;
    V3 cross(const V3& b) const;
    double norm() const;
    V3 unit() const;
};

struct Q {
    double x{}, y{}, z{}, w{1.0};
    bool valid() const;
    static Q identity();
    static Q normalized(double x, double y, double z, double w);
    Q inverse() const;
    V3 rotate(const V3& p) const;
    Q operator*(const Q& b) const;
};

struct Rigid {
    V3 t{};
    Q q{};
    bool valid() const;
    static Rigid identity();
    V3 point(const V3& p) const;
    Rigid inverse() const;
    Rigid operator*(const Rigid& b) const;
};

struct Intrinsics {
    double fx{}, fy{}, cx{}, cy{};
    int width{}, height{};
    bool valid() const;
    bool contains(const V2& p) const;
    V3 ray(const V2& p) const;
    std::optional<V2> project(const V3& p) const;
    Intrinsics scaled(double sx, double sy, int w, int h) const;
};

enum class DepthOrigin : uint8_t { Raw = 0, Smoothed = 1, PointCloud = 2, External = 3 };

struct EvidenceId {
    DepthOrigin origin{DepthOrigin::Raw};
    int64_t source_timestamp_ns{};
    bool operator<(const EvidenceId& b) const;
    bool operator==(const EvidenceId& b) const;
};

struct DepthSample {
    V2 pixel{};
    double z{};
    double confidence{};
    EvidenceId evidence{};
};

struct SurfaceFit {
    double depth{};
    double conditional_sigma{};
    int support_count{};
    double residual_m{};
    std::set<EvidenceId> evidence{};
    std::array<double, 3> inverse_plane{};
};

class SurfaceFitter {
public:
    static std::optional<SurfaceFit> fit(const Intrinsics& k,
                                         const V2& click,
                                         const std::vector<DepthSample>& samples,
                                         double radius_px = -1.0);
};

struct PresentedImage {
    V2 origin{};
    V2 horizontal{};
    V2 vertical{};
    std::optional<V2> sensorPixel(const V2& normalized, const Intrinsics& k) const;
    static PresentedImage upright(const Intrinsics& k, int clockwise_rotation);
};

class AnchorStore {
public:
    virtual ~AnchorStore() = default;
    virtual std::optional<uint64_t> create(const Rigid& world_from_anchor) = 0;
    virtual std::optional<Rigid> locate(uint64_t id) = 0;
    virtual void destroy(uint64_t id) = 0;
};

struct FrameRef {
    uint64_t id{};
    uint64_t epoch{};
    int64_t camera_timestamp_ns{};
    int64_t captured_ns{};
    uint64_t anchor_id{};
    Rigid anchor_from_camera{};
    Intrinsics intrinsics{};
};

struct FrameLedgerConfig {
    int max_frames{120};
    int64_t history_ns{4'000'000'000LL};
    int max_anchors{12};
    int64_t max_freeze_ns{60'000'000'000LL};
};

class FrameLedger {
public:
    using Clock = std::function<int64_t()>;
    FrameLedger(AnchorStore& anchors, Clock clock, FrameLedgerConfig config = {});
    ~FrameLedger();

    std::optional<FrameRef> capture(const Rigid& world_from_camera,
                                    const Intrinsics& k,
                                    int64_t timestamp_ns);
    std::optional<FrameRef> get(uint64_t id);
    std::optional<FrameRef> freeze(uint64_t id);
    void unfreeze(uint64_t id);
    std::optional<Rigid> currentWorldFromCamera(const FrameRef& frame);
    std::optional<Rigid> worldFromAnchor(uint64_t id);
    bool retain(uint64_t anchor_id);
    void release(uint64_t anchor_id);
    size_t anchorCount() const;
    size_t frameCount();
    uint64_t epoch() const;
    void reset();

private:
    struct Slot { uint64_t id{}; int64_t last_use{}; int holds{}; };
    struct Frozen { FrameRef frame{}; int64_t deadline{}; };
    AnchorStore& anchors_;
    Clock clock_;
    FrameLedgerConfig config_;
    std::map<uint64_t, Slot> slots_;
    std::map<uint64_t, FrameRef> frames_;
    std::map<uint64_t, Frozen> frozen_;
    uint64_t last_frame_id_{};
    uint64_t epoch_{1};
    void prune();
};

struct LockPolicy {
    int minimum_views{4};
    double minimum_parallax_deg{2.0};
    double max_correction_m{0.08};
    double total_travel_m{0.15};
    double max_conditional_sigma_m{0.03};
    double assumed_common_translation_sigma_m{0.005};
    double systematic_floor_m{0.01};
    int64_t max_observation_age_ns{2'000'000'000LL};
    bool valid() const;
};

struct RootReference {
    uint64_t frame_id{};
    uint64_t epoch{};
    uint64_t anchor_id{};
    int64_t camera_timestamp_ns{};
    Rigid camera_in_anchor{};
    Intrinsics intrinsics{};
    V2 pixel{};
    bool valid() const;
};

struct VisualObservation {
    uint64_t frame_id{};
    uint64_t epoch{};
    uint64_t anchor_id{};
    uint64_t generation{};
    int64_t camera_timestamp_ns{};
    int64_t captured_ns{};
    Rigid camera_in_anchor{};
    Intrinsics intrinsics{};
    V2 pixel{};
    int inliers{};
    double forward_backward_px{};
    double reprojection_px{};
    double sigma_px{1.0};
    std::set<EvidenceId> depth_evidence{};
    bool valid() const;
};

enum class LockState : uint8_t {
    Unverified = 0,
    GeometrySupported = 1,
    Occluded = 2,
    Uncertain = 3,
    Relocalizing = 4,
    Lost = 5
};

struct AttachmentSnapshot {
    uint64_t id{};
    uint64_t generation{};
    RootReference root{};
    double depth_m{};
    double conditional_sigma_m{};
    LockState state{LockState::Unverified};
    double travel_m{};
    V3 pointInAnchor() const;
};

struct LockProposal {
    uint64_t attachment_id{};
    uint64_t generation{};
    uint64_t epoch{};
    uint64_t anchor_id{};
    double depth_m{};
    double conditional_sigma_m{};
    double parallax_deg{};
    std::vector<VisualObservation> observations{};
    std::string reason{};
};

struct LockDecision {
    bool accepted{};
    std::string reason{};
    std::optional<AttachmentSnapshot> snapshot{};
};

class RayRefiner {
public:
    static std::optional<LockProposal> propose(const AttachmentSnapshot& snapshot,
                                              const std::vector<VisualObservation>& observations,
                                              const LockPolicy& policy);
};

class AttachmentEngine {
public:
    using Clock = std::function<int64_t()>;
    explicit AttachmentEngine(Clock clock, LockPolicy policy = {});
    AttachmentSnapshot create(const RootReference& root, const SurfaceFit& fit);
    std::optional<AttachmentSnapshot> snapshot(uint64_t id) const;
    std::vector<AttachmentSnapshot> snapshots() const;
    void remove(uint64_t id);
    void clear();
    std::optional<LockProposal> offer(uint64_t id, const VisualObservation& observation);
    LockDecision commit(const LockProposal& proposal, const VisualObservation& held_out);
    void visibility(uint64_t id, bool visible, bool lost = false);
    const LockPolicy& policy() const;

private:
    struct Entry {
        AttachmentSnapshot snapshot{};
        double seed_depth{};
        std::map<int64_t, VisualObservation> observations{};
        int64_t last_commit_ns{};
    };
    Clock clock_;
    LockPolicy policy_;
    std::map<uint64_t, Entry> entries_;
    uint64_t next_id_{};
};

struct CameraSample {
    FrameRef frame{};
    std::vector<DepthSample> depth{};
};

struct Placement {
    AttachmentSnapshot attachment{};
    SurfaceFit fit{};
};

struct ObservationContext {
    uint64_t id{};
    uint64_t generation{};
    RootReference root{};
    FrameRef frame{};
    Rigid camera_in_anchor{};
};

struct SessionConfig {
    FrameLedgerConfig ledger{};
    LockPolicy lock{};
};

/** Platform-neutral attachment session. All calls are made by the host's AR/XR owner thread. */
class Session {
public:
    using Clock = std::function<int64_t()>;
    Session(AnchorStore& anchors, Clock clock, SessionConfig config = {});
    ~Session();

    std::optional<CameraSample> capture(const Rigid& world_from_camera,
                                        const Intrinsics& intrinsics,
                                        int64_t camera_timestamp_ns,
                                        std::vector<DepthSample> depth = {});
    std::optional<FrameRef> freeze(uint64_t frame_id);
    void unfreeze(uint64_t frame_id);
    std::optional<Placement> place(const CameraSample& sample, const V2& pixel);
    std::optional<ObservationContext> context(uint64_t attachment_id, const CameraSample& current);
    LockDecision observe(uint64_t attachment_id, const VisualObservation& observation);
    std::optional<V3> worldPoint(uint64_t attachment_id);
    std::optional<V3> initialWorldPoint(uint64_t attachment_id);
    std::optional<AttachmentSnapshot> snapshot(uint64_t attachment_id) const;
    void visibility(uint64_t attachment_id, bool visible, bool lost = false);
    void remove(uint64_t attachment_id);
    void trackingLost();
    void reset();
    uint64_t epoch() const;

private:
    AnchorStore& anchors_;
    Clock clock_;
    FrameLedger ledger_;
    AttachmentEngine engine_;
    std::map<uint64_t, uint64_t> attachment_anchors_;
    std::map<uint64_t, LockProposal> pending_;
    std::map<uint64_t, V3> initial_points_;
};

struct EntitlementClaims {
    std::string product_id{};
    std::string customer_id{};
    std::string app_id{};
    std::string platform{};
    std::set<std::string> features{};
    int64_t not_before_unix_s{};
    int64_t expires_unix_s{};
    int64_t offline_grace_s{};
};

struct EntitlementStatus {
    bool usable{};
    bool in_grace{};
    std::string reason{};
    std::optional<EntitlementClaims> claims{};
};

/** Parser/gate only. Signature verification is supplied by the platform wrapper. */
class EntitlementGate {
public:
    using SignatureVerifier = std::function<bool(const std::string& signing_input,
                                                 const std::vector<uint8_t>& signature)>;
    static EntitlementStatus verify(const std::string& token,
                                    const std::string& expected_product,
                                    const std::string& expected_platform,
                                    const std::string& expected_app_id,
                                    const std::string& required_feature,
                                    int64_t now_unix_s,
                                    const SignatureVerifier& verifier);
};

} // namespace stablear
