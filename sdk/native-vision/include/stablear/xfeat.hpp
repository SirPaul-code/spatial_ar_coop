#pragma once

#include "stablear/vision.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>

namespace stablear::vision {

struct XFeatInputShape {
    static constexpr int width = 640;
    static constexpr int height = 480;
    static constexpr int elements = width * height;
};

/** Resize grayscale to 640x480 and apply the model-card host-side per-image InstanceNorm. */
bool prepareXFeatInput(const uint8_t* gray, int width, int height, int row_stride,
                       float* output, size_t output_elements);

/** Borrowed XFeat output view. Descriptors use CHW layout: [channels][cells_y][cells_x]. */
struct XFeatMapView {
    const float* descriptors{};
    const float* reliability{};
    int cells_width{};
    int cells_height{};
    int channels{64};
    int image_width{};
    int image_height{};
    bool valid() const;
};

/** View metadata is optional. It is used only to select/scale a bounded template bank. */
struct XFeatView {
    std::optional<V3> direction{};
    double scale{1.0};
    double reliability{1.0};
    bool valid() const;
};

struct XFeatPolicy {
    int patch_radius_cells{2};
    double patch_spacing_px{8.0};
    double search_radius_px{64.0};
    double coarse_step_px{8.0};
    double refine_radius_px{4.0};
    double refine_step_px{1.0};
    double minimum_cosine{0.82};
    double minimum_reliability{0.05};
    int minimum_inliers{12};
    double minimum_score_margin{0.025};
    double consensus_radius_px{3.0};
    double distinct_peak_radius_px{8.0};
    int maximum_templates{8};
    bool valid() const;
};

struct XFeatMatch {
    ImageMatch image{};
    double score{};
    double second_best_score{};
    double score_margin{};
    double mean_reliability{};
    double sigma_px{};
    uint64_t template_serial{};
};

/**
 * Learned local-correspondence tracker for XFeat dense descriptor maps.
 *
 * This class does not run the neural network. LiteRT/CoreML/other platform runtimes own inference
 * and pass their borrowed output buffers here. StableAR remains the geometry authority.
 *
 * Template updates are explicit. Call addTemplate only after an independently accepted StableAR
 * observation/commit; the tracker never self-trains from its own unverified match.
 */
class XFeatLocalTracker {
public:
    explicit XFeatLocalTracker(XFeatPolicy policy = {});
    ~XFeatLocalTracker();
    XFeatLocalTracker(XFeatLocalTracker&&) noexcept;
    XFeatLocalTracker& operator=(XFeatLocalTracker&&) noexcept;
    XFeatLocalTracker(const XFeatLocalTracker&) = delete;
    XFeatLocalTracker& operator=(const XFeatLocalTracker&) = delete;

    bool add(uint64_t id, const XFeatMapView& map, V2 root_pixel, XFeatView view = {});
    bool addTemplate(uint64_t id, const XFeatMapView& map, V2 pixel, XFeatView view = {});
    bool beginFrame(uint64_t frame_id, const XFeatMapView& map);
    std::optional<XFeatMatch> track(uint64_t id, V2 predicted_pixel, XFeatView current_view = {});
    void remove(uint64_t id);
    void clear();
    const XFeatPolicy& policy() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace stablear::vision
