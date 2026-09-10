#pragma once
#include "stablear/stablear.hpp"
#include <openxr/openxr.h>
#include <cstdint>
#include <map>
#include <optional>

namespace stablear::openxr {
/** Convert an OpenXR world-from-view pose (+Y up, -Z forward) to StableAR camera axes (+Y down,+Z forward). */
Rigid stableCameraPose(const XrPosef& world_from_view);
XrPosef xrPose(const Rigid& world_from_anchor);
Rigid stableWorldPose(const XrPosef& pose);

/**
 * Session-local anchor store implemented only from core OpenXR reference spaces.
 * It deliberately does not claim persistence or vendor spatial-anchor semantics.
 * Call setLocateTime() with the frame's predicted display time before core capture/queries.
 */
class LocalReferenceAnchorStore final : public AnchorStore {
public:
    LocalReferenceAnchorStore(XrSession session,XrSpace local_space);
    ~LocalReferenceAnchorStore() override;
    void setLocateTime(XrTime time);
    std::optional<uint64_t> create(const Rigid& world_from_anchor) override;
    std::optional<Rigid> locate(uint64_t id) override;
    void destroy(uint64_t id) override;
    void clear();
private:
    XrSession session_{XR_NULL_HANDLE}; XrSpace local_{XR_NULL_HANDLE}; XrTime time_{}; uint64_t next_{};
    std::map<uint64_t,XrSpace> spaces_;
};
} // namespace stablear::openxr
