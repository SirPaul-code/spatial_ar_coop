#include "stablear/stablear.hpp"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <map>
#include <random>
#include <stdexcept>
using namespace stablear;
static int assertions=0;
static void ok(bool v,const char* m){++assertions;if(!v)throw std::runtime_error(m);}static void near(double a,double b,double t=1e-8){++assertions;if(std::abs(a-b)>=t)throw std::runtime_error("near failed");}
struct FakeAnchors:AnchorStore{uint64_t next=0;std::map<uint64_t,Rigid> p;std::map<uint64_t,int> destroyed;std::optional<uint64_t>create(const Rigid&r)override{auto id=++next;p[id]=r;return id;}std::optional<Rigid>locate(uint64_t id)override{auto i=p.find(id);return i==p.end()?std::nullopt:std::optional<Rigid>(i->second);}void destroy(uint64_t id)override{destroyed[id]++;p.erase(id);}};
static Intrinsics K{800,800,320,240,640,480};static V2 C{320,240};
static SurfaceFit fit(double z){return {z,.04,20,.002,{{DepthOrigin::Raw,1}},{{0,0,1/z}}};}
static VisualObservation observation(const AttachmentSnapshot&s,int index,double x,double truth=2,double offset=0){Rigid pose{{x,.02*std::sin((double)index),0},Q::identity()};auto px=K.project(pose.inverse().point({0,0,truth})).value();auto ts=1000000000LL+index*100000000LL;return {(uint64_t)index+1,s.root.epoch,s.root.anchor_id,s.generation,ts,ts,pose,K,{px.x+offset,px.y},30,.2,.3,.5,{}};}
static void transforms(){std::mt19937_64 rng(20260910);std::normal_distribution<double>d;auto pose=[&](){return Rigid{{d(rng),d(rng),d(rng)},Q::normalized(d(rng),d(rng),d(rng),d(rng))};};for(int i=0;i<1000;i++){auto a=pose(),c=pose(),r=pose();auto p=K.ray({200,180})*2;auto local=(a.inverse()*c).point(p);near(((r*c).point(p)-(r*a).point(local)).norm(),0);near((a.inverse().point(a.point(p))-p).norm(),0);}}
static void surface(){std::vector<DepthSample>s;for(int y=-10;y<=10;y+=2)for(int x=-10;x<=10;x+=2){double z=1/(.5+.0005*x-.0003*y);s.push_back({{320.+x,240.+y},z,1,{DepthOrigin::Raw,1}});}auto f=SurfaceFitter::fit(K,C,s);ok(f.has_value(),"surface missing");near(f->depth,2,1e-6);ok(f->conditional_sigma>=.01,"sigma floor");auto one=s;one.erase(std::remove_if(one.begin(),one.end(),[](auto&p){return p.pixel.x<=320;}),one.end());ok(!SurfaceFitter::fit(K,C,one),"one sided");auto edge=s;for(auto&x:edge)if(x.pixel.x>320)x.z=4;ok(!SurfaceFitter::fit(K,C,edge),"depth edge");}
static void ledger(){int64_t now=1000000000;FakeAnchors a;FrameLedger l(a,[&]{return now;},{3,100,2,1000});auto f=l.capture(Rigid::identity(),K,1).value();ok(l.freeze(f.id).has_value(),"freeze");for(int i=0;i<10;i++){now+=10;l.capture(Rigid::identity(),K,i+2);}ok(l.frameCount()<=3,"bounded");now+=101;ok(l.get(f.id).has_value(),"frozen evicted");a.p[f.anchor_id]={{.1,.2,.3},Q::normalized(.01,.02,.03,1)};auto w=l.currentWorldFromCamera(f);ok(w.has_value(),"world camera");near((w->point({0,0,2})-a.p[f.anchor_id].point({0,0,2})).norm(),0);auto second=l.capture({{2,0,0},Q::identity()},K,30).value();ok(l.freeze(second.id).has_value(),"second freeze");ok(!l.capture({{4,0,0},Q::identity()},K,31),"capacity reject");auto old=l.epoch();l.reset();ok(l.epoch()==old+1,"epoch");ok(!l.currentWorldFromCamera(second),"stale frame");for(auto&p:a.destroyed)ok(p.second==1,"double destroy");}
static void transaction(){int64_t now=1000000000;AttachmentEngine e([&]{return now;},LockPolicy{4,2,.08,.15,.03,.001,.01,2000000000});RootReference r{1,1,1,1,Rigid::identity(),K,C};auto s=e.create(r,fit(2.06));std::optional<LockProposal>p;for(int i=1;i<=6;i++){auto o=observation(s,i,i*.1);now=o.captured_ns;auto q=e.offer(s.id,o);if(q)p=q;ok(!e.offer(s.id,o),"duplicate");}ok(p.has_value(),"proposal");ok(std::abs(p->depth_m-2)<.02,"improve");ok(!e.commit(*p,p->observations.back()).accepted,"training heldout");auto bad=observation(s,8,.68,2,25);now=bad.captured_ns;ok(!e.commit(*p,bad).accepted,"bad heldout");auto good=observation(s,9,.70);now=good.captured_ns;auto forged=*p;forged.depth_m=2.7;ok(!e.commit(forged,good).accepted,"forged");auto d=e.commit(*p,good);ok(d.accepted,"valid commit");ok(d.snapshot->generation==2,"generation");ok(d.snapshot->root.frame_id==r.frame_id&&d.snapshot->root.pixel.x==r.pixel.x,"root immutable");ok(!e.commit(*p,good).accepted,"stale proposal");}
static void noParallax(){RootReference r{1,1,1,1,Rigid::identity(),K,C};AttachmentSnapshot s{1,1,r,2.06,.04,LockState::Unverified,0};std::vector<VisualObservation>o;for(int i=1;i<=6;i++){auto x=observation(s,i,0);x.camera_in_anchor=Rigid::identity();x.pixel=C;o.push_back(x);}ok(!RayRefiner::propose(s,o,LockPolicy{}),"no parallax");}
static void mapping(){for(int r:{0,90,180,270}){auto m=PresentedImage::upright(K,r);auto p=m.sensorPixel({.5,.5},K);ok(p.has_value(),"mapping");near(p->x,319.5);near(p->y,239.5);ok(!m.sensorPixel({-0.01,0},K),"outside");}}
static void session(){
    int64_t now=1000000000; FakeAnchors a; Session s(a,[&]{return now;});
    std::vector<DepthSample>d;
    for(int y=-10;y<=10;y+=2)for(int x=-10;x<=10;x+=2)d.push_back({{320.+x,240.+y},2,1,{DepthOrigin::Raw,1}});
    auto f=s.capture(Rigid::identity(),K,1,d);ok(f.has_value(),"capture");

    // FrameRef is a capability-like token. FFI callers may copy it, but altered numerical pose/
    // intrinsics must never override the canonical retained frame stored inside the session.
    auto forged=*f; forged.frame.anchor_from_camera.t={10,20,30}; forged.frame.intrinsics={80,80,1,1,640,480};
    auto canonicalPlacement=s.place(*f,C);ok(canonicalPlacement.has_value(),"canonical place");
    auto forgedPlacement=s.place(forged,C);ok(forgedPlacement.has_value(),"forged numeric fields must be ignored after token validation");
    auto canonicalWorld=s.initialWorldPoint(canonicalPlacement->attachment.id);
    auto forgedWorld=s.initialWorldPoint(forgedPlacement->attachment.id);
    ok(canonicalWorld.has_value()&&forgedWorld.has_value(),"initial points");
    near((*canonicalWorld-*forgedWorld).norm(),0,1e-8);
    s.remove(canonicalPlacement->attachment.id);s.remove(forgedPlacement->attachment.id);

    auto p=s.place(*f,C);ok(p.has_value(),"place");auto wp=s.worldPoint(p->attachment.id);ok(wp.has_value(),"world point");near(wp->z,2,1e-6);
    a.p[p->attachment.root.anchor_id]={{1,0,0},Q::identity()};auto moved=s.worldPoint(p->attachment.id);near(moved->x,1,1e-6);
    s.remove(p->attachment.id);ok(!s.worldPoint(p->attachment.id),"remove");
}
static void entitlement(){
    const char* B="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    auto enc=[&](const std::string&in){std::string o;uint32_t v=0;int bits=-6;for(unsigned char c:in){v=(v<<8)|c;bits+=8;while(bits>=0){o+=B[(v>>bits)&63];bits-=6;}}if(bits>-6)o+=B[((v<<8)>>(bits+8))&63];return o;};
    auto make=[&](const std::string&payload,const std::string&sig="sig"){return std::string("STABLEAR1.")+enc(payload)+"."+enc(sig);};
    auto verifier=[](const std::string&,const std::vector<uint8_t>&s){return std::string(s.begin(),s.end())=="sig";};
    const std::string payload="product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking,vision\nnbf=100\nexp=200\ngrace=10\n";
    const auto token=make(payload);
    auto verify=[&](const std::string&t,const std::string&product="stablear",const std::string&platform="android",const std::string&app="com.acme.app",const std::string&feature="tracking",int64_t now=150){return EntitlementGate::verify(t,product,platform,app,feature,now,verifier);};
    ok(verify(token).usable&&!verify(token).in_grace,"entitlement active");
    ok(verify(token,"stablear","android","com.acme.app","tracking",205).usable&&verify(token,"stablear","android","com.acme.app","tracking",205).in_grace,"grace");
    ok(!verify(token,"stablear","ios").usable,"platform bind");
    auto tampered=token;tampered.back()='A';ok(!verify(tampered).usable,"tamper");
    ok(!verify(token,"other").usable,"product bind");
    ok(!verify(token,"stablear","android","com.other").usable,"app bind");
    ok(!verify(token,"stablear","android","com.acme.app","mesh").usable,"feature bind");
    ok(!verify(token,"stablear","android","com.acme.app","tracking",99).usable,"not before");
    ok(!verify(token,"stablear","android","com.acme.app","tracking",211).usable,"after grace");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=100\nexp=200\ngrace=-1\n")).usable,"negative grace");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=100\nexp=200\ngrace=2678401\n")).usable,"grace cap");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=200\nexp=200\ngrace=0\n")).usable,"exp after nbf");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=-1\nexp=200\ngrace=0\n")).usable,"negative nbf");
    ok(!verify(make(payload+"platform=android\n")).usable,"duplicate claim");
    ok(!verify(make(payload+"admin=true\n")).usable,"unknown claim");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=100\nexp=200\n")).usable,"missing claim");
    ok(!verify(make("product_id=stable ar\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=100\nexp=200\ngrace=0\n")).usable,"invalid token value");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=\nnbf=100\nexp=200\ngrace=0\n")).usable,"empty features");
    ok(!verify("WRONG."+enc(payload)+"."+enc("sig")).usable,"wrong format");
    ok(!verify(token+".extra").usable,"extra token segment");
    ok(!verify("STABLEAR1.@@@."+enc("sig")).usable,"bad payload base64");
    ok(!verify("STABLEAR1."+enc(payload)+".").usable,"empty signature");
    ok(!EntitlementGate::verify(token,"stablear","android","com.acme.app","tracking",150,[](const std::string&,const std::vector<uint8_t>&){return false;}).usable,"signature callback false");
    ok(!verify(std::string(4097,'x')).usable,"token size cap");
    const std::string hugeCustomer(193,'a');
    ok(!verify(make("product_id=stablear\ncustomer_id="+hugeCustomer+"\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=100\nexp=200\ngrace=0\n")).usable,"claim size cap");
    const std::string hugeFeatures(513,'a');
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures="+hugeFeatures+"\nnbf=100\nexp=200\ngrace=0\n")).usable,"feature list size cap");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=nope\nexp=200\ngrace=0\n")).usable,"integer parsing");
    ok(!verify(make("product_id=stablear\ncustomer_id=acme\napp_id=com.acme.app\nplatform=android\nfeatures=tracking\nnbf=100\nexp=9223372036854775802\ngrace=10\n"),"stablear","android","com.acme.app","tracking",9223372036854775807LL).usable,"grace overflow safe");
    ok(verify(token,"stablear","android","com.acme.app","",150).usable,"empty required feature allowed");
}

int main(){for(auto f:{transforms,surface,ledger,transaction,noParallax,mapping,session,entitlement})f();std::cout<<"PASS: 8 native contract suites, "<<assertions<<" assertions. Synthetic tests, not physical accuracy.\n";}
