#include "stablear/stablear.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
namespace stablear { namespace {
constexpr double kPi=3.141592653589793238462643383279502884;
bool finite(double v){return std::isfinite(v);}
double median(std::vector<double> v){if(v.empty())throw std::invalid_argument("median of empty vector");std::sort(v.begin(),v.end());return v[v.size()/2];}
std::optional<std::array<double,3>> solve3(const std::array<std::array<double,3>,3>&a,const std::array<double,3>&b){double m[3][4]{};for(int r=0;r<3;++r){for(int c=0;c<3;++c)m[r][c]=a[r][c];m[r][3]=b[r];}for(int i=0;i<3;++i){int p=i;for(int r=i+1;r<3;++r)if(std::abs(m[r][i])>std::abs(m[p][i]))p=r;if(std::abs(m[p][i])<1e-9)return std::nullopt;if(p!=i)for(int c=i;c<4;++c)std::swap(m[p][c],m[i][c]);double scale=m[i][i];for(int c=i;c<4;++c)m[i][c]/=scale;for(int r=0;r<3;++r)if(r!=i){double f=m[r][i];for(int c=i;c<4;++c)m[r][c]-=f*m[i][c];}}std::array<double,3>o{m[0][3],m[1][3],m[2][3]};return finite(o[0])&&finite(o[1])&&finite(o[2])?std::optional(o):std::nullopt;}
} // namespace
std::optional<SurfaceFit> SurfaceFitter::fit(const Intrinsics& k,
                                             const V2& click,
                                             const std::vector<DepthSample>& samples,
                                             double radius_px) {
    if (!k.valid() || !k.contains(click)) return std::nullopt;
    if (radius_px < 0) radius_px = std::max(12.0, k.width * .025);
    if (!finite(radius_px) || radius_px < 1) return std::nullopt;

    std::vector<DepthSample> near;
    for (const auto& s : samples) {
        if (!finite(s.z) || !finite(s.confidence) || s.confidence < .5 || s.confidence > 1.0 || s.z < .15 || s.z > 8.0 || s.evidence.source_timestamp_ns <= 0) continue;
        if ((s.pixel - click).norm() <= radius_px) near.push_back(s);
    }
    std::sort(near.begin(), near.end(), [&](const DepthSample& a, const DepthSample& b) {
        const double da = (a.pixel-click).norm(), db = (b.pixel-click).norm();
        if (da != db) return da < db;
        return static_cast<int>(a.evidence.origin) < static_cast<int>(b.evidence.origin);
    });
    std::set<std::pair<int,int>> bins;
    std::vector<DepthSample> dedup;
    for (const auto& s : near) {
        auto bin = std::make_pair(static_cast<int>(s.pixel.x / 2.0), static_cast<int>(s.pixel.y / 2.0));
        if (bins.insert(bin).second) dedup.push_back(s);
        if (dedup.size() == 96) break;
    }
    near.swap(dedup);
    if (near.size() < 6 || (near.front().pixel-click).norm() > radius_px*.6) return std::nullopt;

    std::vector<double> seed_values;
    for (size_t i=0; i<std::min<size_t>(5, near.size()); ++i) seed_values.push_back(near[i].z);
    const double seed = median(seed_values);
    const double limit = std::max(.04, seed*.04);
    int divergent = 0;
    for (size_t i=0; i<std::min<size_t>(6, near.size()); ++i) if (std::abs(near[i].z-seed) > 2*limit) ++divergent;
    if (divergent >= 2) return std::nullopt;

    std::vector<DepthSample> points;
    for (const auto& s : near) if (std::abs(s.z-seed) < limit) points.push_back(s);
    if (points.size() < 6 || points.size() < near.size()*.65) return std::nullopt;

    std::vector<double> angles;
    for (const auto& p : points) angles.push_back(std::atan2(p.pixel.y-click.y,p.pixel.x-click.x));
    std::sort(angles.begin(), angles.end());
    double max_gap = angles.front() + 2*kPi - angles.back();
    for (size_t i=1; i<angles.size(); ++i) max_gap = std::max(max_gap, angles[i]-angles[i-1]);
    if (max_gap > kPi + .01) return std::nullopt;

    std::vector<std::array<double,3>> rows;
    std::vector<double> weights;
    for (const auto& p : points) {
        rows.push_back({(p.pixel.x-click.x)/radius_px, (p.pixel.y-click.y)/radius_px, 1.0});
        const double d=(p.pixel-click).norm();
        weights.push_back(p.confidence/(1+d*d/64.0));
    }
    std::optional<std::array<double,3>> fitv;
    for (int iter=0; iter<5; ++iter) {
        std::array<std::array<double,3>,3> a{};
        std::array<double,3> b{};
        for (size_t i=0; i<points.size(); ++i) for (int r=0; r<3; ++r) {
            b[r] += weights[i]*rows[i][r]/points[i].z;
            for (int c=0; c<3; ++c) a[r][c] += weights[i]*rows[i][r]*rows[i][c];
        }
        fitv=solve3(a,b); if (!fitv) return std::nullopt;
        std::vector<double> residual;
        for (size_t i=0; i<points.size(); ++i) {
            const double pred=rows[i][0]*(*fitv)[0]+rows[i][1]*(*fitv)[1]+rows[i][2]*(*fitv)[2];
            residual.push_back(std::abs(pred-1.0/points[i].z));
        }
        const double scale=std::max(.0005,1.4826*median(residual));
        for (size_t i=0;i<points.size();++i) {
            const double d=(points[i].pixel-click).norm();
            weights[i]=points[i].confidence/(1+d*d/64.0)*std::min(1.0,1.5*scale/std::max(1e-12,residual[i]));
        }
    }
    if (!fitv || (*fitv)[2] <= 0) return std::nullopt;
    const double z=1.0/(*fitv)[2];
    if (z < .15 || z > 8.0 || std::abs(z-seed)>limit) return std::nullopt;
    std::vector<double> errors;
    for (size_t i=0;i<points.size();++i) {
        const double inv=rows[i][0]*(*fitv)[0]+rows[i][1]*(*fitv)[1]+rows[i][2]*(*fitv)[2];
        errors.push_back(inv<=0?std::numeric_limits<double>::infinity():std::abs(1.0/inv-points[i].z));
    }
    const double error=median(errors);
    if (error>std::max(.015,z*.015)) return std::nullopt;
    const double floor=std::max(.010,z*.01);
    std::set<EvidenceId> evidence;
    for (const auto& p:points) evidence.insert(p.evidence);
    return SurfaceFit{z,std::max(floor,1.4826*error),static_cast<int>(points.size()),error,evidence,*fitv};
}


} // namespace stablear
