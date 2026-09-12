#include "stablear/stablear.hpp"
#include <cmath>
#include <stdexcept>
namespace stablear {
namespace { bool finite(double v){return std::isfinite(v);} }
bool V2::valid() const { return finite(x) && finite(y); }
V2 V2::operator-(const V2& b) const { return {x - b.x, y - b.y}; }
double V2::norm() const { return std::hypot(x, y); }

bool V3::valid() const { return finite(x) && finite(y) && finite(z); }
V3 V3::operator+(const V3& b) const { return {x + b.x, y + b.y, z + b.z}; }
V3 V3::operator-(const V3& b) const { return {x - b.x, y - b.y, z - b.z}; }
V3 V3::operator*(double s) const { return {x * s, y * s, z * s}; }
double V3::dot(const V3& b) const { return x * b.x + y * b.y + z * b.z; }
V3 V3::cross(const V3& b) const { return {y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x}; }
double V3::norm() const { return std::sqrt(dot(*this)); }
V3 V3::unit() const { const double n = norm(); if (!(n > 1e-12)) throw std::invalid_argument("zero vector"); return (*this) * (1.0 / n); }

bool Q::valid() const { const double n=x*x+y*y+z*z+w*w; return finite(x)&&finite(y)&&finite(z)&&finite(w)&&std::abs(n-1.0)<1e-5; }
Q Q::identity() { return {}; }
Q Q::normalized(double x, double y, double z, double w) {
    const double n = std::sqrt(x*x + y*y + z*z + w*w);
    if (!finite(n) || n <= 1e-12) throw std::invalid_argument("invalid quaternion");
    return {x/n, y/n, z/n, w/n};
}
Q Q::inverse() const { return {-x, -y, -z, w}; }
V3 Q::rotate(const V3& p) const {
    const V3 v{x, y, z};
    const V3 t = v.cross(p) * 2.0;
    return p + t * w + v.cross(t);
}
Q Q::operator*(const Q& b) const {
    return normalized(w*b.x + x*b.w + y*b.z - z*b.y,
                      w*b.y - x*b.z + y*b.w + z*b.x,
                      w*b.z + x*b.y - y*b.x + z*b.w,
                      w*b.w - x*b.x - y*b.y - z*b.z);
}

bool Rigid::valid() const { return t.valid() && q.valid(); }
Rigid Rigid::identity() { return {}; }
V3 Rigid::point(const V3& p) const { return q.rotate(p) + t; }
Rigid Rigid::inverse() const { const Q r = q.inverse(); return {r.rotate(t * -1.0), r}; }
Rigid Rigid::operator*(const Rigid& b) const { return {point(b.t), q * b.q}; }

bool Intrinsics::valid() const {
    return finite(fx) && finite(fy) && finite(cx) && finite(cy) && fx > 0 && fy > 0 && width > 0 && height > 0;
}
bool Intrinsics::contains(const V2& p) const { return finite(p.x) && finite(p.y) && p.x >= 0 && p.y >= 0 && p.x < width && p.y < height; }
V3 Intrinsics::ray(const V2& p) const { return {(p.x - cx) / fx, (p.y - cy) / fy, 1.0}; }
std::optional<V2> Intrinsics::project(const V3& p) const {
    if (!finite(p.x) || !finite(p.y) || !finite(p.z) || p.z < .05) return std::nullopt;
    V2 v{fx*p.x/p.z + cx, fy*p.y/p.z + cy};
    if (!finite(v.x) || !finite(v.y)) return std::nullopt;
    return v;
}
Intrinsics Intrinsics::scaled(double sx, double sy, int w, int h) const { return {fx*sx, fy*sy, cx*sx, cy*sy, w, h}; }

bool EvidenceId::operator<(const EvidenceId& b) const {
    if (origin != b.origin) return static_cast<int>(origin) < static_cast<int>(b.origin);
    return source_timestamp_ns < b.source_timestamp_ns;
}
bool EvidenceId::operator==(const EvidenceId& b) const { return origin == b.origin && source_timestamp_ns == b.source_timestamp_ns; }

std::optional<V2> PresentedImage::sensorPixel(const V2& normalized, const Intrinsics& k) const {
    if (normalized.x < 0 || normalized.x > 1 || normalized.y < 0 || normalized.y > 1) return std::nullopt;
    V2 p{origin.x+horizontal.x*normalized.x+vertical.x*normalized.y,
         origin.y+horizontal.y*normalized.x+vertical.y*normalized.y};
    return k.contains(p) ? std::optional<V2>(p) : std::nullopt;
}
PresentedImage PresentedImage::upright(const Intrinsics& k,int r) {
    const double w=k.width-1.0,h=k.height-1.0;
    switch(r) {
        case 0:return {{0,0},{w,0},{0,h}};
        case 90:return {{0,h},{0,-h},{w,0}};
        case 180:return {{w,h},{-w,0},{0,-h}};
        case 270:return {{w,0},{0,h},{-w,0}};
        default:throw std::invalid_argument("rotation must be 0/90/180/270");
    }
}


} // namespace stablear
