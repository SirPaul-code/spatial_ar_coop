#include "stablear/xfeat.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <map>
#include <stdexcept>
#include <vector>

namespace stablear::vision {
namespace {
constexpr int C=64;
bool fin(double v){return std::isfinite(v);}
double angle(const V3&a,const V3&b){if(!a.valid()||!b.valid()||a.norm()<1e-9||b.norm()<1e-9)return 180.; return std::acos(std::clamp(a.dot(b)/(a.norm()*b.norm()),-1.,1.))*57.29577951308232;}
bool coords(const XFeatMapView&m,V2 p,int&x0,int&x1,int&y0,int&y1,double&tx,double&ty){
 if(!p.valid()||p.x<0||p.y<0||p.x>m.image_width-1||p.y>m.image_height-1)return false;
 double x=p.x*m.cells_width/(m.image_width-1.)-.5,y=p.y*m.cells_height/(m.image_height-1.)-.5;
 if(x<0||y<0||x>m.cells_width-1||y>m.cells_height-1)return false;
 x0=(int)std::floor(x);y0=(int)std::floor(y);x1=std::min(x0+1,m.cells_width-1);y1=std::min(y0+1,m.cells_height-1);tx=x-x0;ty=y-y0;return true;
}
float at(const XFeatMapView&m,int c,int x,int y){size_t n=(size_t)m.cells_width*m.cells_height;return m.descriptors[(size_t)c*n+(size_t)y*m.cells_width+x];}
bool desc(const XFeatMapView&m,V2 p,std::array<float,C>&o){int x0,x1,y0,y1;double tx,ty;if(!coords(m,p,x0,x1,y0,y1,tx,ty))return false;double n=0;for(int c=0;c<C;++c){double a=at(m,c,x0,y0)*(1-tx)+at(m,c,x1,y0)*tx,b=at(m,c,x0,y1)*(1-tx)+at(m,c,x1,y1)*tx,v=a*(1-ty)+b*ty;if(!fin(v))return false;o[c]=(float)v;n+=v*v;}if(n<1e-12)return false;float s=(float)(1/std::sqrt(n));for(auto&v:o)v*=s;return true;}
double rel(const XFeatMapView&m,V2 p){if(!m.reliability)return 1.;int x0,x1,y0,y1;double tx,ty;if(!coords(m,p,x0,x1,y0,y1,tx,ty))return 0.;auto a=[&](int x,int y){return (double)m.reliability[(size_t)y*m.cells_width+x];};double u=a(x0,y0)*(1-tx)+a(x1,y0)*tx,v=a(x0,y1)*(1-tx)+a(x1,y1)*tx,q=u*(1-ty)+v*ty;return fin(q)?std::clamp(q,0.,1.):0.;}
double cosim(const std::array<float,C>&a,const std::array<float,C>&b){double s=0;for(int i=0;i<C;++i)s+=(double)a[i]*b[i];return std::clamp(s,-1.,1.);}
struct S{V2 off{};std::array<float,C>d{};double r{};};
struct T{uint64_t serial{};XFeatView view{};std::vector<S>s;};
struct Cand{const T*t{};V2 p{};double score{-INFINITY};double r{};int in{};};
std::optional<T> capture(uint64_t serial,const XFeatMapView&m,V2 p,const XFeatView&v,const XFeatPolicy&z){T t;t.serial=serial;t.view=v;for(int y=-z.patch_radius_cells;y<=z.patch_radius_cells;++y)for(int x=-z.patch_radius_cells;x<=z.patch_radius_cells;++x){S s;s.off={(double)x*z.patch_spacing_px,(double)y*z.patch_spacing_px};V2 q{p.x+s.off.x,p.y+s.off.y};s.r=rel(m,q);if(s.r>=z.minimum_reliability&&desc(m,q,s.d))t.s.push_back(s);}if((int)t.s.size()<z.minimum_inliers)return{};return t;}
Cand score(const T&t,const XFeatMapView&m,V2 p,const XFeatView&v,const XFeatPolicy&z){Cand c;c.t=&t;c.p=p;double ws=0,w=0,rs=0;int valid=0,in=0;double scale=v.scale/t.view.scale;for(const auto&s:t.s){V2 q{p.x+s.off.x*scale,p.y+s.off.y*scale};std::array<float,C>d{};double r=rel(m,q);if(r<z.minimum_reliability||!desc(m,q,d))continue;double cs=cosim(s.d,d),wt=std::sqrt(std::max(0.,s.r*r));ws+=wt*cs;w+=wt;rs+=r;++valid;if(cs>=z.minimum_cosine)++in;}if(valid<z.minimum_inliers||in<z.minimum_inliers||w<=1e-9)return c;c.score=(ws/w)*std::sqrt((double)valid/t.s.size()*(double)in/t.s.size());c.r=rs/valid;c.in=in;return c;}
std::vector<double> axis(double c,double r,double step){std::vector<double>v;int n=(int)std::floor(r/step);for(int i=-n;i<=n;++i)v.push_back(c+i*step);return v;}
double median(std::vector<double>v){if(v.empty())return INFINITY;auto m=v.begin()+v.size()/2;std::nth_element(v.begin(),m,v.end());return *m;}
}

bool XFeatMapView::valid()const{return descriptors&&cells_width>1&&cells_height>1&&channels==64&&image_width>1&&image_height>1;}
bool XFeatView::valid()const{return fin(scale)&&scale>.05&&scale<20&&fin(reliability)&&reliability>=0&&reliability<=1&&(!direction||(direction->valid()&&direction->norm()>1e-9));}
bool XFeatPolicy::valid()const{int side=2*patch_radius_cells+1;return patch_radius_cells>=1&&side*side>=minimum_inliers&&patch_spacing_px>0&&search_radius_px>=0&&coarse_step_px>0&&refine_radius_px>=0&&refine_step_px>0&&minimum_cosine>=-1&&minimum_cosine<=1&&minimum_reliability>=0&&minimum_reliability<=1&&minimum_inliers>=3&&minimum_score_margin>=0&&consensus_radius_px>0&&distinct_peak_radius_px>0&&maximum_templates>=1&&maximum_templates<=32;}
struct XFeatLocalTracker::Impl{explicit Impl(XFeatPolicy p):p(p){}XFeatPolicy p;std::map<uint64_t,std::vector<T>>bank;uint64_t serial{},frame{};XFeatMapView cur{};bool has{};bool put(uint64_t id,const XFeatMapView&m,V2 px,XFeatView v,bool first){if(!id||!m.valid()||!v.valid())return false;auto&b=bank[id];if(first&&!b.empty())return false;auto t=capture(++serial,m,px,v,p);if(!t){if(first&&b.empty())bank.erase(id);return false;}if(v.direction){auto it=std::find_if(b.begin(),b.end(),[&](const T&q){return q.view.direction&&angle(*v.direction,*q.view.direction)<12.;});if(it!=b.end()){if(v.reliability>=it->view.reliability)*it=std::move(*t);return true;}}if((int)b.size()<p.maximum_templates){b.push_back(std::move(*t));return true;}auto w=std::min_element(b.begin(),b.end(),[](const T&a,const T&b){return a.view.reliability<b.view.reliability;});if(v.reliability<=w->view.reliability)return false;*w=std::move(*t);return true;}};
XFeatLocalTracker::XFeatLocalTracker(XFeatPolicy p):impl_(std::make_unique<Impl>(p)){if(!p.valid())throw std::invalid_argument("invalid XFeat policy");}
XFeatLocalTracker::~XFeatLocalTracker()=default;XFeatLocalTracker::XFeatLocalTracker(XFeatLocalTracker&&)noexcept=default;XFeatLocalTracker&XFeatLocalTracker::operator=(XFeatLocalTracker&&)noexcept=default;
bool XFeatLocalTracker::add(uint64_t id,const XFeatMapView&m,V2 p,XFeatView v){if(impl_->bank.size()>=64&&!impl_->bank.count(id))return false;return impl_->put(id,m,p,v,true);}
bool XFeatLocalTracker::addTemplate(uint64_t id,const XFeatMapView&m,V2 p,XFeatView v){return impl_->bank.count(id)&&impl_->put(id,m,p,v,false);}
bool XFeatLocalTracker::beginFrame(uint64_t id,const XFeatMapView&m){if(!id||!m.valid())return false;impl_->frame=id;impl_->cur=m;impl_->has=true;return true;}
std::optional<XFeatMatch>XFeatLocalTracker::track(uint64_t id,V2 predicted,XFeatView view){auto f=impl_->bank.find(id);if(f==impl_->bank.end()||!impl_->has||!predicted.valid()||!view.valid())return{};auto&p=impl_->p;Cand best;std::vector<Cand>all;for(const auto&t:f->second)for(double y:axis(predicted.y,p.search_radius_px,p.coarse_step_px))for(double x:axis(predicted.x,p.search_radius_px,p.coarse_step_px)){auto c=score(t,impl_->cur,{x,y},view,p);if(fin(c.score)){all.push_back(c);if(c.score>best.score)best=c;}}if(!fin(best.score))return{};for(double y:axis(best.p.y,p.refine_radius_px,p.refine_step_px))for(double x:axis(best.p.x,p.refine_radius_px,p.refine_step_px)){auto c=score(*best.t,impl_->cur,{x,y},view,p);if(fin(c.score)){all.push_back(c);if(c.score>best.score)best=c;}}double second=-INFINITY;for(const auto&c:all)if((c.p-best.p).norm()>=p.distinct_peak_radius_px)second=std::max(second,c.score);if(!fin(second))second=-1;double margin=best.score-second;if(best.score<p.minimum_cosine||margin<p.minimum_score_margin)return{};double sc=view.scale/best.t->view.scale;std::vector<double>res;double rs=0;int in=0;for(const auto&s:best.t->s){V2 e{best.p.x+s.off.x*sc,best.p.y+s.off.y*sc},bp=e;double bs=-INFINITY;for(double dy=-p.consensus_radius_px;dy<=p.consensus_radius_px;dy+=1)for(double dx=-p.consensus_radius_px;dx<=p.consensus_radius_px;dx+=1){V2 q{e.x+dx,e.y+dy};std::array<float,C>d{};double r=rel(impl_->cur,q);if(r<p.minimum_reliability||!desc(impl_->cur,q,d))continue;double cs=cosim(s.d,d);if(cs>bs){bs=cs;bp=q;}}if(bs>=p.minimum_cosine){double rr=(bp-e).norm();if(rr<=p.consensus_radius_px){res.push_back(rr);rs+=rel(impl_->cur,bp);++in;}}}if(in<p.minimum_inliers)return{};double repro=median(res),mr=rs/in;if(!fin(repro))return{};double sigma=std::clamp(.35+repro+(1-mr),.5,4.);XFeatMatch out;out.image={best.p,in,repro,repro,"XFEAT_PATCH"};out.score=best.score;out.second_best_score=second;out.score_margin=margin;out.mean_reliability=mr;out.sigma_px=sigma;out.template_serial=best.t->serial;return out;}
void XFeatLocalTracker::remove(uint64_t id){impl_->bank.erase(id);}void XFeatLocalTracker::clear(){impl_->bank.clear();impl_->cur={};impl_->frame=0;impl_->has=false;}const XFeatPolicy&XFeatLocalTracker::policy()const{return impl_->p;}
}
