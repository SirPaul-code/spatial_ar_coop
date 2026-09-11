#include "stablear/xfeat.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

using namespace stablear;
using namespace stablear::vision;

namespace {
struct OwnedMap {
    int w{20}, h{15}, iw{160}, ih{120};
    std::vector<float> desc;
    std::vector<float> rel;
    OwnedMap() : desc(static_cast<size_t>(64*w*h)), rel(static_cast<size_t>(w*h), 1.0f) {}
    XFeatMapView view() const { return {desc.data(), rel.data(), w, h, 64, iw, ih}; }
};

void fill(OwnedMap& map) {
    const size_t plane = static_cast<size_t>(map.w * map.h);
    for (int y = 0; y < map.h; ++y) for (int x = 0; x < map.w; ++x) {
        double n2 = 0.0;
        for (int c = 0; c < 64; ++c) {
            const double v = std::sin((x + 1) * (c + 3) * 0.173) + std::cos((y + 2) * (c + 5) * 0.119) +
                             0.2 * std::sin((x + y + 1) * (c + 1) * 0.071);
            map.desc[static_cast<size_t>(c) * plane + static_cast<size_t>(y * map.w + x)] = static_cast<float>(v);
            n2 += v * v;
        }
        const float inv = static_cast<float>(1.0 / std::sqrt(n2));
        for (int c = 0; c < 64; ++c) map.desc[static_cast<size_t>(c) * plane + static_cast<size_t>(y * map.w + x)] *= inv;
    }
}

OwnedMap shiftedRight(const OwnedMap& src, int cells) {
    OwnedMap out;
    const size_t plane = static_cast<size_t>(src.w * src.h);
    for (int y = 0; y < src.h; ++y) for (int x = 0; x < src.w; ++x) {
        const int sx = std::clamp(x - cells, 0, src.w - 1);
        for (int c = 0; c < 64; ++c) {
            out.desc[static_cast<size_t>(c) * plane + static_cast<size_t>(y * src.w + x)] =
                src.desc[static_cast<size_t>(c) * plane + static_cast<size_t>(y * src.w + sx)];
        }
    }
    return out;
}
} // namespace

int main() {
    std::vector<uint8_t> gray(32 * 24);
    for (int y = 0; y < 24; ++y) for (int x = 0; x < 32; ++x)
        gray[static_cast<size_t>(y * 32 + x)] = static_cast<uint8_t>((x * 7 + y * 11) & 255);
    std::vector<float> normalized(XFeatInputShape::elements);
    assert(prepareXFeatInput(gray.data(), 32, 24, 32, normalized.data(), normalized.size()));
    double mean = 0.0, var = 0.0;
    for (float v : normalized) mean += v;
    mean /= normalized.size();
    for (float v : normalized) var += (v - mean) * (v - mean);
    var /= normalized.size();
    assert(std::abs(mean) < 1e-4);
    assert(std::abs(var - 1.0) < 1e-3);

    XFeatPolicy policy;
    policy.search_radius_px = 24.0;
    policy.minimum_score_margin = 0.01;
    assert(policy.valid());

    OwnedMap root;
    fill(root);
    OwnedMap current = shiftedRight(root, 1);

    XFeatLocalTracker tracker(policy);
    const V2 root_px{80.0, 60.0};
    assert(tracker.add(7, root.view(), root_px));
    assert(!tracker.add(7, root.view(), root_px));
    assert(tracker.beginFrame(10, current.view()));
    const auto match = tracker.track(7, {84.0, 60.0});
    assert(match.has_value());
    const double expected_shift = static_cast<double>(root.iw - 1) / root.w;
    assert(std::abs(match->image.pixel.x - (root_px.x + expected_shift)) <= 2.0);
    assert(std::abs(match->image.pixel.y - root_px.y) <= 2.0);
    assert(match->image.inliers >= policy.minimum_inliers);
    assert(match->score_margin >= policy.minimum_score_margin);
    assert(match->sigma_px >= 0.5 && match->sigma_px <= 4.0);
    assert(match->image.method == "XFEAT_PATCH");

    XFeatView high_quality;
    high_quality.direction = V3{0.0, 0.0, 1.0};
    high_quality.reliability = 0.95;
    assert(tracker.addTemplate(7, current.view(), match->image.pixel, high_quality));

    tracker.remove(7);
    assert(!tracker.track(7, root_px));
    std::cout << "StableAR XFeat contract passed\n";
    return 0;
}
