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
constexpr int C = 64;

bool fin(double v) { return std::isfinite(v); }

double angle(const V3& a, const V3& b) {
    if (!a.valid() || !b.valid() || a.norm() < 1e-9 || b.norm() < 1e-9) return 180.0;
    return std::acos(std::clamp(a.dot(b) / (a.norm() * b.norm()), -1.0, 1.0)) * 57.29577951308232;
}

bool coords(const XFeatMapView& m, V2 p, int& x0, int& x1, int& y0, int& y1,
            double& tx, double& ty) {
    if (!p.valid() || p.x < 0 || p.y < 0 || p.x > m.image_width - 1 || p.y > m.image_height - 1)
        return false;
    // Matches XFeat's normalized sparse coordinate convention followed by
    // grid_sample(..., align_corners=false).
    const double x = p.x * m.cells_width / (m.image_width - 1.0) - 0.5;
    const double y = p.y * m.cells_height / (m.image_height - 1.0) - 0.5;
    if (x < 0 || y < 0 || x > m.cells_width - 1 || y > m.cells_height - 1) return false;
    x0 = static_cast<int>(std::floor(x));
    y0 = static_cast<int>(std::floor(y));
    x1 = std::min(x0 + 1, m.cells_width - 1);
    y1 = std::min(y0 + 1, m.cells_height - 1);
    tx = x - x0;
    ty = y - y0;
    return true;
}

float at(const XFeatMapView& m, int c, int x, int y) {
    const size_t n = static_cast<size_t>(m.cells_width) * static_cast<size_t>(m.cells_height);
    return m.descriptors[static_cast<size_t>(c) * n + static_cast<size_t>(y) * m.cells_width + x];
}

bool desc(const XFeatMapView& m, V2 p, std::array<float, C>& out) {
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
    double tx = 0.0, ty = 0.0;
    if (!coords(m, p, x0, x1, y0, y1, tx, ty)) return false;
    double n = 0.0;
    for (int c = 0; c < C; ++c) {
        const double a = at(m, c, x0, y0) * (1 - tx) + at(m, c, x1, y0) * tx;
        const double b = at(m, c, x0, y1) * (1 - tx) + at(m, c, x1, y1) * tx;
        const double v = a * (1 - ty) + b * ty;
        if (!fin(v)) return false;
        out[static_cast<size_t>(c)] = static_cast<float>(v);
        n += v * v;
    }
    if (n < 1e-12) return false;
    const float inv = static_cast<float>(1.0 / std::sqrt(n));
    for (float& v : out) v *= inv;
    return true;
}

double rel(const XFeatMapView& m, V2 p) {
    if (!m.reliability) return 1.0;
    int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
    double tx = 0.0, ty = 0.0;
    if (!coords(m, p, x0, x1, y0, y1, tx, ty)) return 0.0;
    const auto atRel = [&](int x, int y) {
        return static_cast<double>(m.reliability[static_cast<size_t>(y) * m.cells_width + x]);
    };
    const double a = atRel(x0, y0) * (1 - tx) + atRel(x1, y0) * tx;
    const double b = atRel(x0, y1) * (1 - tx) + atRel(x1, y1) * tx;
    const double v = a * (1 - ty) + b * ty;
    return fin(v) ? std::clamp(v, 0.0, 1.0) : 0.0;
}

double cosim(const std::array<float, C>& a, const std::array<float, C>& b) {
    double s = 0.0;
    for (int i = 0; i < C; ++i) s += static_cast<double>(a[static_cast<size_t>(i)]) * b[static_cast<size_t>(i)];
    return std::clamp(s, -1.0, 1.0);
}

struct Sample {
    V2 offset{};
    std::array<float, C> descriptor{};
    double reliability{};
};

struct Template {
    uint64_t serial{};
    XFeatView view{};
    std::vector<Sample> samples{};
};

struct Candidate {
    const Template* source{};
    V2 point{};
    double score{-std::numeric_limits<double>::infinity()};
    double reliability{};
    int inliers{};
};

std::optional<Template> capture(uint64_t serial, const XFeatMapView& map, V2 point,
                                const XFeatView& view, const XFeatPolicy& policy) {
    Template result;
    result.serial = serial;
    result.view = view;
    for (int y = -policy.patch_radius_cells; y <= policy.patch_radius_cells; ++y) {
        for (int x = -policy.patch_radius_cells; x <= policy.patch_radius_cells; ++x) {
            Sample sample;
            sample.offset = {x * policy.patch_spacing_px, y * policy.patch_spacing_px};
            const V2 p{point.x + sample.offset.x, point.y + sample.offset.y};
            sample.reliability = rel(map, p);
            if (sample.reliability < policy.minimum_reliability ||
                !desc(map, p, sample.descriptor))
                continue;
            result.samples.push_back(sample);
        }
    }
    if (static_cast<int>(result.samples.size()) < policy.minimum_inliers) return std::nullopt;
    return result;
}

Candidate scoreCandidate(const Template& source, const XFeatMapView& current, V2 center,
                         const XFeatView& current_view, const XFeatPolicy& policy) {
    Candidate result;
    result.source = &source;
    result.point = center;
    const double scale_ratio = current_view.scale / source.view.scale;
    double weighted_score = 0.0, weight_sum = 0.0, reliability_sum = 0.0;
    int valid = 0, inliers = 0;
    for (const auto& sample : source.samples) {
        const V2 point{center.x + sample.offset.x * scale_ratio,
                       center.y + sample.offset.y * scale_ratio};
        std::array<float, C> descriptor{};
        const double r = rel(current, point);
        if (r < policy.minimum_reliability || !desc(current, point, descriptor)) continue;
        const double similarity = cosim(sample.descriptor, descriptor);
        const double weight = std::sqrt(std::max(0.0, sample.reliability * r));
        weighted_score += weight * similarity;
        weight_sum += weight;
        reliability_sum += r;
        ++valid;
        if (similarity >= policy.minimum_cosine) ++inliers;
    }
    if (valid < policy.minimum_inliers || inliers < policy.minimum_inliers || weight_sum <= 1e-9)
        return result;
    const double coverage = static_cast<double>(valid) / source.samples.size();
    const double inlier_fraction = static_cast<double>(inliers) / source.samples.size();
    result.score = (weighted_score / weight_sum) * std::sqrt(coverage * inlier_fraction);
    result.reliability = reliability_sum / valid;
    result.inliers = inliers;
    return result;
}

std::vector<double> axisSamples(double center, double radius, double step) {
    std::vector<double> values;
    const int count = static_cast<int>(std::floor(radius / step));
    values.reserve(static_cast<size_t>(2 * count + 1));
    for (int i = -count; i <= count; ++i) values.push_back(center + i * step);
    return values;
}

double median(std::vector<double> values) {
    if (values.empty()) return std::numeric_limits<double>::infinity();
    auto mid = values.begin() + static_cast<std::ptrdiff_t>(values.size() / 2);
    std::nth_element(values.begin(), mid, values.end());
    return *mid;
}
} // namespace

bool XFeatMapView::valid() const {
    return descriptors && cells_width > 1 && cells_height > 1 && channels == 64 &&
           image_width > 1 && image_height > 1;
}

bool XFeatView::valid() const {
    return fin(scale) && scale > 0.05 && scale < 20.0 && fin(reliability) &&
           reliability >= 0.0 && reliability <= 1.0 &&
           (!direction || (direction->valid() && direction->norm() > 1e-9));
}

bool XFeatPolicy::valid() const {
    const int side = 2 * patch_radius_cells + 1;
    return patch_radius_cells >= 1 && side * side >= minimum_inliers && patch_spacing_px > 0 &&
           search_radius_px >= 0 && coarse_step_px > 0 && refine_radius_px >= 0 &&
           refine_step_px > 0 && minimum_cosine >= -1 && minimum_cosine <= 1 &&
           minimum_reliability >= 0 && minimum_reliability <= 1 && minimum_inliers >= 3 &&
           minimum_score_margin >= 0 && consensus_radius_px > 0 && distinct_peak_radius_px > 0 &&
           maximum_templates >= 1 && maximum_templates <= 32;
}

struct XFeatLocalTracker::Impl {
    struct Pending {
        uint64_t attachment_id{};
        Template descriptor_template{};
    };

    explicit Impl(XFeatPolicy policy) : policy(std::move(policy)) {}

    XFeatPolicy policy;
    std::map<uint64_t, std::vector<Template>> bank;
    uint64_t serial{};
    uint64_t frame{};
    XFeatMapView current{};
    bool has_current{};
    uint64_t pending_serial{};
    std::map<uint64_t, Pending> pending;
    std::map<uint64_t, uint64_t> pending_by_attachment;

    bool install(uint64_t id, Template descriptor_template, bool first) {
        auto found = bank.find(id);
        if (first) {
            if (found != bank.end() && !found->second.empty()) return false;
            found = bank.try_emplace(id).first;
        } else if (found == bank.end() || found->second.empty()) {
            return false;
        }
        auto& templates = found->second;
        const XFeatView& view = descriptor_template.view;
        if (view.direction) {
            auto same_view = std::find_if(templates.begin(), templates.end(), [&](const Template& existing) {
                return existing.view.direction && angle(*view.direction, *existing.view.direction) < 12.0;
            });
            if (same_view != templates.end()) {
                if (view.reliability >= same_view->view.reliability) *same_view = std::move(descriptor_template);
                return true;
            }
        }
        if (static_cast<int>(templates.size()) < policy.maximum_templates) {
            templates.push_back(std::move(descriptor_template));
            return true;
        }
        auto weakest = std::min_element(templates.begin(), templates.end(), [](const Template& a, const Template& b) {
            return a.view.reliability < b.view.reliability;
        });
        if (view.reliability <= weakest->view.reliability) return false;
        *weakest = std::move(descriptor_template);
        return true;
    }

    bool put(uint64_t id, const XFeatMapView& map, V2 pixel, XFeatView view, bool first) {
        if (!id || !map.valid() || !view.valid()) return false;
        auto descriptor_template = capture(++serial, map, pixel, view, policy);
        if (!descriptor_template) return false;
        return install(id, std::move(*descriptor_template), first);
    }

    void discardPendingFor(uint64_t id) {
        auto by_id = pending_by_attachment.find(id);
        if (by_id == pending_by_attachment.end()) return;
        pending.erase(by_id->second);
        pending_by_attachment.erase(by_id);
    }
};

XFeatLocalTracker::XFeatLocalTracker(XFeatPolicy policy) : impl_(std::make_unique<Impl>(policy)) {
    if (!policy.valid()) throw std::invalid_argument("invalid XFeat policy");
}
XFeatLocalTracker::~XFeatLocalTracker() = default;
XFeatLocalTracker::XFeatLocalTracker(XFeatLocalTracker&&) noexcept = default;
XFeatLocalTracker& XFeatLocalTracker::operator=(XFeatLocalTracker&&) noexcept = default;

bool XFeatLocalTracker::add(uint64_t id, const XFeatMapView& map, V2 point, XFeatView view) {
    if (impl_->bank.size() >= 64 && !impl_->bank.count(id)) return false;
    return impl_->put(id, map, point, view, true);
}

bool XFeatLocalTracker::addTemplate(uint64_t id, const XFeatMapView& map, V2 point, XFeatView view) {
    return impl_->bank.count(id) && impl_->put(id, map, point, view, false);
}

bool XFeatLocalTracker::beginFrame(uint64_t id, const XFeatMapView& map) {
    if (!id || !map.valid()) return false;
    impl_->frame = id;
    impl_->current = map;
    impl_->has_current = true;
    return true;
}

std::optional<XFeatMatch> XFeatLocalTracker::track(uint64_t id, V2 predicted, XFeatView view) {
    const auto found = impl_->bank.find(id);
    if (found == impl_->bank.end() || !impl_->has_current || !predicted.valid() || !view.valid())
        return std::nullopt;
    const auto& policy = impl_->policy;
    Candidate best;
    std::vector<Candidate> all;
    for (const auto& descriptor_template : found->second) {
        for (double y : axisSamples(predicted.y, policy.search_radius_px, policy.coarse_step_px)) {
            for (double x : axisSamples(predicted.x, policy.search_radius_px, policy.coarse_step_px)) {
                auto candidate = scoreCandidate(descriptor_template, impl_->current, {x, y}, view, policy);
                if (fin(candidate.score)) {
                    all.push_back(candidate);
                    if (candidate.score > best.score) best = candidate;
                }
            }
        }
    }
    if (!fin(best.score)) return std::nullopt;
    for (double y : axisSamples(best.point.y, policy.refine_radius_px, policy.refine_step_px)) {
        for (double x : axisSamples(best.point.x, policy.refine_radius_px, policy.refine_step_px)) {
            auto candidate = scoreCandidate(*best.source, impl_->current, {x, y}, view, policy);
            if (fin(candidate.score)) {
                all.push_back(candidate);
                if (candidate.score > best.score) best = candidate;
            }
        }
    }
    double second = -std::numeric_limits<double>::infinity();
    for (const auto& candidate : all)
        if ((candidate.point - best.point).norm() >= policy.distinct_peak_radius_px)
            second = std::max(second, candidate.score);
    if (!fin(second)) second = -1.0;
    const double margin = best.score - second;
    if (best.score < policy.minimum_cosine || margin < policy.minimum_score_margin) return std::nullopt;

    const double scale_ratio = view.scale / best.source->view.scale;
    std::vector<double> residuals;
    double reliability_sum = 0.0;
    int inliers = 0;
    for (const auto& sample : best.source->samples) {
        const V2 expected{best.point.x + sample.offset.x * scale_ratio,
                          best.point.y + sample.offset.y * scale_ratio};
        V2 best_point = expected;
        double best_similarity = -std::numeric_limits<double>::infinity();
        for (double dy = -policy.consensus_radius_px; dy <= policy.consensus_radius_px; dy += 1.0) {
            for (double dx = -policy.consensus_radius_px; dx <= policy.consensus_radius_px; dx += 1.0) {
                const V2 point{expected.x + dx, expected.y + dy};
                std::array<float, C> descriptor{};
                const double r = rel(impl_->current, point);
                if (r < policy.minimum_reliability || !desc(impl_->current, point, descriptor)) continue;
                const double similarity = cosim(sample.descriptor, descriptor);
                if (similarity > best_similarity) {
                    best_similarity = similarity;
                    best_point = point;
                }
            }
        }
        if (best_similarity >= policy.minimum_cosine) {
            const double residual = (best_point - expected).norm();
            if (residual <= policy.consensus_radius_px) {
                residuals.push_back(residual);
                reliability_sum += rel(impl_->current, best_point);
                ++inliers;
            }
        }
    }
    if (inliers < policy.minimum_inliers) return std::nullopt;
    const double reprojection = median(std::move(residuals));
    const double mean_reliability = reliability_sum / inliers;
    if (!fin(reprojection)) return std::nullopt;
    const double sigma = std::clamp(0.35 + reprojection + (1 - mean_reliability), 0.5, 4.0);

    XFeatMatch result;
    // Legacy ImageMatch has an LK-named consistency field. For XFeat this value is the local
    // descriptor-patch consensus residual, not an LK forward/backward cycle. The method tag keeps
    // that distinction explicit until the shared visual-observation ABI gains source-aware metrics.
    result.image = {best.point, inliers, reprojection, reprojection, "XFEAT_PATCH"};
    result.score = best.score;
    result.second_best_score = second;
    result.score_margin = margin;
    result.mean_reliability = mean_reliability;
    result.sigma_px = sigma;
    result.template_serial = best.source->serial;
    return result;
}

std::optional<uint64_t> XFeatLocalTracker::stageTemplate(uint64_t id, V2 pixel, XFeatView view) {
    if (!id || !impl_->has_current || !pixel.valid() || !view.valid() || !impl_->bank.count(id))
        return std::nullopt;
    auto descriptor_template = capture(++impl_->serial, impl_->current, pixel, view, impl_->policy);
    if (!descriptor_template) return std::nullopt;
    impl_->discardPendingFor(id);
    ++impl_->pending_serial;
    if (impl_->pending_serial == 0) ++impl_->pending_serial;
    const uint64_t token = impl_->pending_serial;
    impl_->pending.emplace(token, Impl::Pending{id, std::move(*descriptor_template)});
    impl_->pending_by_attachment[id] = token;
    return token;
}

bool XFeatLocalTracker::commitStagedTemplate(uint64_t id, uint64_t token) {
    if (!id || !token) return false;
    auto found = impl_->pending.find(token);
    if (found == impl_->pending.end() || found->second.attachment_id != id) return false;
    Template descriptor_template = std::move(found->second.descriptor_template);
    impl_->pending.erase(found);
    auto by_id = impl_->pending_by_attachment.find(id);
    if (by_id != impl_->pending_by_attachment.end() && by_id->second == token)
        impl_->pending_by_attachment.erase(by_id);
    if (!impl_->bank.count(id)) return false;
    return impl_->install(id, std::move(descriptor_template), false);
}

void XFeatLocalTracker::discardStagedTemplate(uint64_t id, uint64_t token) {
    if (!id || !token) return;
    auto found = impl_->pending.find(token);
    if (found == impl_->pending.end() || found->second.attachment_id != id) return;
    impl_->pending.erase(found);
    auto by_id = impl_->pending_by_attachment.find(id);
    if (by_id != impl_->pending_by_attachment.end() && by_id->second == token)
        impl_->pending_by_attachment.erase(by_id);
}

void XFeatLocalTracker::remove(uint64_t id) {
    impl_->discardPendingFor(id);
    impl_->bank.erase(id);
}

void XFeatLocalTracker::clear() {
    impl_->bank.clear();
    impl_->pending.clear();
    impl_->pending_by_attachment.clear();
    impl_->current = {};
    impl_->frame = 0;
    impl_->has_current = false;
}

const XFeatPolicy& XFeatLocalTracker::policy() const { return impl_->policy; }

} // namespace stablear::vision
