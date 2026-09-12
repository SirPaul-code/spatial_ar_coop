#include <stablear/stablear.hpp>
#include <cstring>
#include <iostream>
int main() {
    if (std::strcmp(stablear::kVersion, "0.2.0-research") != 0) return 1;
    const stablear::Intrinsics k{800,800,320,240,640,480};
    const auto ray = k.ray({320,240});
    if (ray.x != 0 || ray.y != 0 || ray.z != 1) return 2;
    std::cout << "PASS: installed StableAR CMake package consumer\n";
    return 0;
}
