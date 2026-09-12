import CryptoKit
import Foundation
import StableARNative

private let acceptSignature: stablear_signature_verify_fn = { context,_,_,_,_ in
    guard let context else{return 0};return context.assumingMemoryBound(to:Bool.self).pointee ? 1:0
}
public enum StableAREntitlementState { case invalid, valid, offlineGrace }
public enum StableAREntitlement {
    public static func verify(token:String,publicKeyPEM:String,appId:String,feature:String="tracking",nowUnixS:Int64=Int64(Date().timeIntervalSince1970))->StableAREntitlementState {
        let parts=token.split(separator:".",omittingEmptySubsequences:false);guard parts.count==3,parts[0]=="STABLEAR1",let sig=Data(base64URLEncoded:String(parts[2])) else{return .invalid}
        let key: P256.Signing.PublicKey;do{key=try P256.Signing.PublicKey(pemRepresentation:publicKeyPEM)}catch{return .invalid}
        let signature:P256.Signing.ECDSASignature;do{signature=try .init(derRepresentation:sig)}catch{return .invalid}
        let valid=key.isValidSignature(signature,for:Data("\(parts[0]).\(parts[1])".utf8));if(!valid){return .invalid}
        var yes=true
        return withUnsafeMutablePointer(to:&yes){ctx in token.withCString{t in "stablear".withCString{p in "ios".withCString{platform in appId.withCString{app in feature.withCString{f in let r=stablear_entitlement_verify(t,p,platform,app,f,nowUnixS,ctx,acceptSignature);return r.usable==0 ? .invalid:(r.in_grace != 0 ? .offlineGrace:.valid)}}}}}}
    }
}
private extension Data { init?(base64URLEncoded s:String){var x=s.replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/");x+=String(repeating:"=",count:(4-x.count%4)%4);self.init(base64Encoded:x)} }
