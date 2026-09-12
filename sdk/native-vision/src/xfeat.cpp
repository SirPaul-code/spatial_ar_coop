#include "stablear/xfeat.hpp"
#include <algorithm>
#include <cmath>

namespace stablear::vision {
bool prepareXFeatInput(const uint8_t* gray,int width,int height,int row_stride,float* output,size_t output_elements){
 if(!gray||!output||width<=1||height<=1||row_stride<width||output_elements<(size_t)XFeatInputShape::elements)return false;
 double sum=0,sum2=0;
 for(int oy=0;oy<XFeatInputShape::height;++oy){
  double sy=(oy+.5)*height/XFeatInputShape::height-.5;int y0=std::clamp((int)std::floor(sy),0,height-1),y1=std::min(y0+1,height-1);double ty=std::clamp(sy-std::floor(sy),0.,1.);
  for(int ox=0;ox<XFeatInputShape::width;++ox){
   double sx=(ox+.5)*width/XFeatInputShape::width-.5;int x0=std::clamp((int)std::floor(sx),0,width-1),x1=std::min(x0+1,width-1);double tx=std::clamp(sx-std::floor(sx),0.,1.);
   double a=gray[(size_t)y0*row_stride+x0]*(1-tx)+gray[(size_t)y0*row_stride+x1]*tx;
   double b=gray[(size_t)y1*row_stride+x0]*(1-tx)+gray[(size_t)y1*row_stride+x1]*tx;
   double v=a*(1-ty)+b*ty;size_t i=(size_t)oy*XFeatInputShape::width+ox;output[i]=(float)v;sum+=v;sum2+=v*v;
  }
 }
 double n=XFeatInputShape::elements,mean=sum/n,var=std::max(0.,sum2/n-mean*mean),inv=1/std::sqrt(var+1e-5);
 for(int i=0;i<XFeatInputShape::elements;++i)output[i]=(float)((output[i]-mean)*inv);
 return true;
}
}
