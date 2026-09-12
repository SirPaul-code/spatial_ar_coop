#include "stablear/openxr_adapter.hpp"
#include <cmath>
#include <iostream>
int main(){
    XrPosef p{{0,0,0,1},{1,2,3}};auto c=stablear::openxr::stableCameraPose(p);
    auto right=c.q.rotate({1,0,0});auto down=c.q.rotate({0,1,0});auto forward=c.q.rotate({0,0,1});
    if(std::abs(right.x-1)>1e-8||std::abs(down.y+1)>1e-8||std::abs(forward.z+1)>1e-8)return 1;
    auto round=stablear::openxr::stableWorldPose(stablear::openxr::xrPose({{4,5,6},stablear::Q::identity()}));
    if((round.t-stablear::V3{4,5,6}).norm()>1e-6)return 2;
    std::cout<<"PASS: OpenXR/StableAR coordinate contract\n";
}
