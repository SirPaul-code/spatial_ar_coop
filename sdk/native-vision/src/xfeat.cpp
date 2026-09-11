#include "stablear/xfeat.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <map>
#include <stdexcept>
#include <utility>
#include <vector>

namespace stablear::vision {
namespace {

constexpr int kXFeatChannels = 64;
constexpr double kPi = 3.141592653589793238462643383279502884;

bool finite(double value) { return std::isfinite(value); }

double median(std::vector<double> values) {
    if (values.empty()) return std::numeric_limits<double>::infinity();
    const auto mid = values.begin() + static_cast<std::ptrdiff_t>(values.size() / 2);
    std::nth_element(values.begin(), mid, values.end());
    return *mid;
}

double angularDistanceDeg(const V3& a, const V3& b) {
    if (!a.valid() || !b.valid() || a.norm() < 1e-9 || b.norm() < 1e-9) return 180.0;
    const double c = std::clamp(a.dot(b) / (a.norm() * b.norm()), -1.0, 1.0);
    return std::acos(c) * 180.0 / kPi;
}

/** Replicates XFeat InterpolateSparse2d coordinate convention (grid_sample, align_corners=false). */
double featureCoordinate(double pixel, int image_extent, int feature_extent) {
    return pixel * static_cast<double>(feature_extent) / static_cast<double>(image_extent - 1) - 0.5;
}

bool bilinearCoordinates(const XFeatMapView& map, const V2& pixel,
                         int& x0, int& x1, int& y0, int& y1, double& tx, double& ty) {
    if (!pixel.valid() || pixel.x < 0.0 || pixel.y < 0.0 ||
        pixel.x > static_cast<double>(map.image_width - 1) ||
        pixel.y > static_cast<double>(map.image_height - 1)) return false;
    const double fx = featureCoordinate(pixel.x, map.image_width, map.cells_width);
    const double fy = featureCoordinate(pixel.y, map.image_height, map.cells_height);
    if (fx < 0.0 || fy < 0.0 || fx > static_cast<double>(map.cells_width - 1) ||
        fy > static_cast<double>(map.cells_height - 1)) return false;
    x0 = static_cast<int>(std::floor(fx));
    y0 = static_cast<int>(std::floor(fy));
    x1 = std::min(x0 + 1, map.cells_width - 1);
    y1 = std::min(y0 + 1, map.cells_height - 1);
    tx = fx - x0;
    ty = fy - y0;
    return true;
}

float chw(const XFeatMapView& map, int channel, int x, int y) {
    const size_t plane = static_cast<size_t>(map.cells_width) * static_cast<size_t>(map.cells_height);
    return map.descriptors[static_cast<size_t>(channel) * plane +
                           static_cast<size_t>(y) * static_cast<size_t>(map.cells_width) +
                           static_cast<size_t>(x)];
}

bool sampleDescriptor(const XFeatMapView& map, const V2& pixel,
                      std::array<float, kXFeatChannels>& output) {
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
    double tx = 0.0, ty = 0.0;
    if (!bilinearCoordinates(map, pixel, x0, x1, y0, y1, tx, ty)) return false;
    double norm2 = 0.0;
    for (int c = 0; c < kXFeatChannels; ++c) {
        const double a = chw(map, c, x0, y0) * (1.0 - tx) + chw(map, c, x1, y0) * tx;
        const double b = chw(map, c, x0, y1) * (1.0 - tx) + chw(map, c, x1, y1) * tx;
        const double value = a * (1.0 - ty) + b * ty;
        if (!finite(value)) return false;
        output[static_cast<size_t>(c)] = static_cast<float>(value);
        norm2 += value * value;
    }
    if (norm2 < 1e-12) return false;
    const float inv = static_cast<float>(1.0 / std::sqrt(norm2));
    for (float& v : output) v *= inv;
    return true;
}

double sampleReliability(const XFeatMapView& map, const V2& pixel) {
    if (!map.reliability) return 1.0;
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
    double tx = 0.0, ty = 0.0;
    if (!bilinearCoordinates(map, pixel, x0, x1, y0, y1, tx, ty)) return 0.0;
    const auto at = [&](int x, int y) {
        return static_cast<double>(map.reliability[static_cast<size_t>(y) *
                                                   static_cast<size_t>(map.cells_width) +
                                                   static_cast<size_t>(x)]);
    };
    const double a = at(x0, y0) * (1.0 - tx) + at(x1, y0) * tx;
    const double b = at(x0, y1) * (1.0 - tx) + at(x1, y1) * tx;
    const double value = a * (1.0 - ty) + b * ty;
    return finite(value) ? std::clamp(value, 0.0, 1.0) : 0.0;
}

double cosine(const std::array<float, kXFeatChannels>& a,
              const std::array<float, kXFeatChannels>& b) {
    double result = 0.0;
    for (int i = 0; i < kXFeatChannels; ++i) result += static_cast<double>(a[i]) * b[i];
    return std::clamp(result, -1.0, 1.0);
}

struct PatchSample {
    V2 offset{};
    std::array<float, kXFeatChannels> descriptor{};
    double reliability{};
};

struct Template {
    uint64_t serial{};
    V2 root{};
    XFeatView view{};
    std::vector<PatchSample> samples{};
};

std::optional<Template> captureTemplate(uint64_t serial, const XFeatMapView& map, V2 root,
                                        const XFeatView& view, const XFeatPolicy& policy) {
    if (!map.valid() || !root.valid() || !view.valid()) return std::nullopt;
    Template result;
    result.serial = serial;
    result.root = root;
    result.view = view;
    for (int y = -policy.patch_radius_cells; y <= policy.patch_radius_cells; ++y) {
        for (int x = -policy.patch_radius_cells; x <= policy.patch_radius_cells; ++x) {
            const V2 offset{x * policy.patch_spacing_px, y * policy.patch_spacing_px};
            const V2 point{root.x + offset.x, root.y + offset.y};
            PatchSample sample;
            sample.offset = offset;
            sample.reliability = sampleReliability(map, point);
            if (sample.reliability < policy.minimum_reliability ||
                !sampleDescriptor(map, point, sample.descriptor)) continue;
            result.samples.push_back(sample);
        }
    }
    if (static_cast<int>(result.samples.size()) < policy.minimum_inliers) return std::nullopt;
    return result;
}

struct Candidate {
    const Template* source{};
    V2 center{};
    double score{-std::numeric_limits<double>::infinity()};
    double mean_reliability{};
    int inliers{};
};

Candidate scoreCandidate(const Template& source, const XFeatMapView& current, V2 center,
                         const XFeatView& current_view, const XFeatPolicy& policy) {
    Candidate result;
    result.source = &source;
    result.center = center;
    const double scale_ratio = current_view.scale / source.view.scale;
    double weighted_score = 0.0;
    double weight_sum = 0.0;
    double reliability_sum = 0.0;
    int valid = 0;
    int inliers = 0;
    for (const auto& sample : source.samples) {
        const V2 point{center.x + sample.offset.x * scale_ratio,
                       center.y + sample.offset.y * scale_ratio};
        std::array<float, kXFeatChannels> descriptor{};
        const double rel = sampleReliability(current, point);
        if (rel < policy.minimum_reliability || !sampleDescriptor(current, point, descriptor)) continue;
        const double c = cosine(sample.descriptor, descriptor);
        const double weight = std::sqrt(std::max(0.0, sample.reliability * rel));
        weighted_score += weight * c;
        weight_sum += weight;
        reliability_sum += rel;
        ++valid;
        if (c >= policy.minimum_cosine) ++inliers;
    }
    if (valid < policy.minimum_inliers || inliers < policy.minimum_inliers || weight_sum <= 1e-9) return result;
    const double coverage = static_cast<double>(valid) / static_cast<double>(source.samples.size());
    const double inlier_fraction = static_cast<double>(inliers) / static_cast<double>(source.samples.size());
    result.score = (weighted_score / weight_sum) * std::sqrt(coverage * inlier_fraction);
    result.mean_reliability = reliability_sum / valid;
    result.inliers = inliers;
    return result;
}

bool finiteCandidate(const Candidate& candidate) { return finite(candidate.score); }

std::vector<double> axisSamples(double center, double radius, double step) {
    std::vector<double> values;
    const int count = static_cast<int>(std::floor(radius / step));
    values.reserve(static_cast<size_t>(2 * count + 1));
    for (int i = -count; i <= count; ++i) values.push_back(center + i * step);
    return values;
}

} // namespace

bool prepareXFeatInput(const uint8_t* gray, int width, int height, int row_stride,
                       float* output, size_t output_elements) {
    if (!gray || !output || width <= 1 || height <= 1 || row_stride < width ||
        output_elements < static_cast<size_t>(XFeatInputShape::elements)) return false;
    double sum = 0.0;
    double sum2 = 0.0;
    for (int oy = 0; oy < XFeatInputShape::height; ++oy) {
        const double sy = (static_cast<double>(oy) + 0.5) * height / XFeatInputShape::height - 0.5;
        const int y0 = std::clamp(static_cast<int>(std::floor(sy)), 0, height - 1);
        const int y1 = std::min(y0 + 1, height - 1);
        const double ty = std::clamp(sy - std::floor(sy), 0.0, 1.0);
        for (int ox = 0; ox < XFeatInputShape::width; ++ox) {
            const double sx = (static_cast<double>(ox) + 0.5) * width / XFeatInputShape::width - 0.5;
            const int x0 = std::clamp(static_cast<int>(std::floor(sx)), 0, width - 1);
            const int x1 = std::min(x0 + 1, width - 1);
            const double tx = std::clamp(sx - std::floor(sx), 0.0, 1.0);
            const double a = gray[static_cast<size_t>(y0) * row_stride + x0] * (1.0 - tx) +
                             gray[static_cast<size_t>(y0) * row_stride + x1] * tx;
            const double b = gray[static_cast<size_t>(y1) * row_stride + x0] * (1.0 - tx) +
                           gray[static_cast<size_t>(y1) * row_stride + x1] * tx;
            const double value = a * (1.0 - ty) + b * ty;
            const size_t index = static_cast<size_t>(oy) * XFeatInputShape::width + ox;
            output[index] = static_cast<float>(value);
            sum += value;
            sum2 += value * value;
        }
    }
    const double n = static_cast<double>(XFeatInputShape::elements);
    const double mean = sum / n;
    const double variance = std::max(0.0, sum2 / n - mean * mean);
    const double inv_std = 1.0 / std::sqrt(variance + 1e-5);
    for (int i = 0; i < XFeatInputShape::elements; ++i)
        output[i] = static_cast<float>((output[i] - mean) * inv_std);
    return true;
}
