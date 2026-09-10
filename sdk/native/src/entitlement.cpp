#include "stablear/stablear.hpp"
#include <cctype>
#include <limits>
#include <map>
#include <set>
#include <sstream>
namespace stablear { namespace {
std::optional<std::vector<uint8_t>> base64urlDecode(const std::string& in){
    if(in.empty() || in.size()%4==1) return std::nullopt;
    auto value=[](unsigned char c)->int {
        if(c>='A'&&c<='Z') return c-'A';
        if(c>='a'&&c<='z') return c-'a'+26;
        if(c>='0'&&c<='9') return c-'0'+52;
        if(c=='-'||c=='+') return 62;
        if(c=='_'||c=='/') return 63;
        return -1;
    };
    std::vector<uint8_t> out;
    out.reserve(in.size()*3/4+2);
    uint32_t acc=0; int bits=0;
    for(unsigned char c:in){
        const int v=value(c); if(v<0) return std::nullopt;
        acc=(acc<<6)|static_cast<uint32_t>(v); bits+=6;
        if(bits>=8){bits-=8; out.push_back(static_cast<uint8_t>((acc>>bits)&0xffu));}
    }
    if(bits && (acc & ((1u<<bits)-1u)) != 0) return std::nullopt;
    return out;
}
bool tokenValue(const std::string&s){if(s.empty()||s.size()>192)return false;for(unsigned char c:s)if(!(std::isalnum(c)||c=='.'||c=='_'||c=='-'||c==':'||c=='/'||c=='+'||c=='@'))return false;return true;}
bool featureList(const std::string&s){if(s.empty()||s.size()>512)return false;size_t p=0;while(p<s.size()){auto e=s.find(',',p);if(e==std::string::npos)e=s.size();if(!tokenValue(s.substr(p,e-p)))return false;p=e+1;}return true;}
std::optional<int64_t> parseInt64(const std::string&s){if(s.empty()||s.size()>20)return std::nullopt;size_t used=0;try{long long v=std::stoll(s,&used,10);if(used!=s.size())return std::nullopt;return static_cast<int64_t>(v);}catch(...){return std::nullopt;}}
} // namespace
EntitlementStatus EntitlementGate::verify(const std::string& token,const std::string& expected_product,const std::string& expected_platform,const std::string& expected_app_id,const std::string& required_feature,int64_t now,const SignatureVerifier& verifier){
    auto fail=[](std::string r){return EntitlementStatus{false,false,std::move(r),std::nullopt};};
    if(!verifier||token.size()>4096)return fail("Malformed entitlement");
    const std::string prefix="STABLEAR1.";if(token.rfind(prefix,0)!=0)return fail("Unsupported entitlement format");size_t dot=token.find('.',prefix.size());if(dot==std::string::npos||token.find('.',dot+1)!=std::string::npos)return fail("Malformed entitlement");std::string payload64=token.substr(prefix.size(),dot-prefix.size()),sig64=token.substr(dot+1);auto payload_bytes=base64urlDecode(payload64),sig=base64urlDecode(sig64);if(!payload_bytes||!sig||sig->empty())return fail("Malformed entitlement encoding");std::string signing_input=token.substr(0,dot);if(!verifier(signing_input,*sig))return fail("Invalid entitlement signature");std::string payload(payload_bytes->begin(),payload_bytes->end());if(payload.size()>2048)return fail("Entitlement payload too large");std::map<std::string,std::string> values;std::istringstream stream(payload);std::string line;while(std::getline(stream,line)){if(line.empty())continue;size_t eq=line.find('=');if(eq==std::string::npos||eq==0||line.find('=',eq+1)!=std::string::npos)return fail("Malformed entitlement claims");std::string key=line.substr(0,eq),val=line.substr(eq+1);if(!values.emplace(key,val).second)return fail("Duplicate entitlement claim");}
    static const std::set<std::string> allowed{"product_id","customer_id","app_id","platform","features","nbf","exp","grace"};for(const auto&p:values)if(!allowed.count(p.first))return fail("Unknown entitlement claim");for(const auto& req:{"product_id","customer_id","app_id","platform","features","nbf","exp","grace"})if(!values.count(req))return fail("Missing entitlement claim");if(!tokenValue(values["product_id"])||!tokenValue(values["customer_id"])||!tokenValue(values["app_id"])||!tokenValue(values["platform"])||!featureList(values["features"]))return fail("Invalid entitlement claim value");auto nbf=parseInt64(values["nbf"]),exp=parseInt64(values["exp"]),grace=parseInt64(values["grace"]);if(!nbf||!exp||!grace||*nbf<0||*exp<=*nbf||*grace<0||*grace>31LL*24*3600)return fail("Invalid entitlement time bounds");EntitlementClaims claims{values["product_id"],values["customer_id"],values["app_id"],values["platform"],{},*nbf,*exp,*grace};size_t start=0;while(start<values["features"].size()){size_t end=values["features"].find(',',start);if(end==std::string::npos)end=values["features"].size();claims.features.insert(values["features"].substr(start,end-start));start=end+1;}if(claims.product_id!=expected_product)return fail("Wrong product");if(claims.platform!=expected_platform)return fail("Wrong platform");if(claims.app_id!=expected_app_id)return fail("Wrong application identity");if(!required_feature.empty()&&!claims.features.count(required_feature))return fail("Feature not licensed");if(now<claims.not_before_unix_s)return fail("Entitlement not active yet");bool in_grace=false;if(now>claims.expires_unix_s){if(claims.offline_grace_s>std::numeric_limits<int64_t>::max()-claims.expires_unix_s||now>claims.expires_unix_s+claims.offline_grace_s)return fail("Entitlement expired");in_grace=true;}return{true,in_grace,in_grace?"Usable in offline grace":"Usable",claims};
}
} // namespace stablear
