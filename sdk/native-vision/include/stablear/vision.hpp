#pragma once
#include "stablear/stablear.hpp"
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
namespace stablear::vision {
struct ImageMatch { V2 pixel{}; int inliers{}; double median_reprojection_px{}; double forward_backward_px{}; std::string method{}; };
/** Optional OpenCV front-end. It proposes image correspondences; core geometry remains authority. */
class LocalSurfaceTracker {
public:
    LocalSurfaceTracker(); ~LocalSurfaceTracker(); LocalSurfaceTracker(LocalSurfaceTracker&&) noexcept; LocalSurfaceTracker& operator=(LocalSurfaceTracker&&) noexcept;
    LocalSurfaceTracker(const LocalSurfaceTracker&)=delete; LocalSurfaceTracker& operator=(const LocalSurfaceTracker&)=delete;
    bool add(uint64_t id,const uint8_t* gray,int width,int height,V2 root_pixel);
    bool beginFrame(uint64_t frame_id,const uint8_t* gray,int width,int height);
    std::optional<ImageMatch> track(uint64_t id,std::optional<V2> predicted=std::nullopt);
    void remove(uint64_t id); void clear();
private: struct Impl; std::unique_ptr<Impl> impl_;
};
} // namespace stablear::vision
