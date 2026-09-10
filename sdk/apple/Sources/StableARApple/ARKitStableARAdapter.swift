import ARKit
import CoreVideo
import Foundation
import Darwin
import simd
import StableARNative

private func rigid(_ m:simd_float4x4)->stablear_rigid {
    let r=simd_float3x3(columns:(SIMD3(m.columns.0.x,m.columns.0.y,m.columns.0.z),SIMD3(m.columns.1.x,m.columns.1.y,m.columns.1.z),SIMD3(m.columns.2.x,m.columns.2.y,m.columns.2.z)))
    let q=simd_quatf(r);return stablear_rigid(t:stablear_v3(x:Double(m.columns.3.x),y:Double(m.columns.3.y),z:Double(m.columns.3.z)),q:stablear_quat(x:Double(q.vector.x),y:Double(q.vector.y),z:Double(q.vector.z),w:Double(q.vector.w)))
}
private func matrix(_ p:stablear_rigid)->simd_float4x4 {let q=simd_quatf(vector:SIMD4(Float(p.q.x),Float(p.q.y),Float(p.q.z),Float(p.q.w)));var m=simd_float4x4(q);m.columns.3=SIMD4(Float(p.t.x),Float(p.t.y),Float(p.t.z),1);return m}
private let cvToGL=simd_float4x4(columns:(SIMD4<Float>(1,0,0,0),SIMD4<Float>(0,-1,0,0),SIMD4<Float>(0,0,-1,0),SIMD4<Float>(0,0,0,1)))

private final class ARKitAnchorStore:StableARAnchorStore {
    let session:ARSession;var next:UInt64=0;var anchors:[UInt64:ARAnchor]=[:]
    init(_ s:ARSession){session=s}
    func create(_ pose:stablear_rigid)->UInt64{let a=ARAnchor(transform:matrix(pose));session.add(anchor:a);next+=1;anchors[next]=a;return next}
    func locate(_ id:UInt64)->stablear_rigid?{guard let old=anchors[id] else{return nil};if let updated=session.currentFrame?.anchors.first(where:{$0.identifier==old.identifier}){anchors[id]=updated;return rigid(updated.transform)};return rigid(old.transform)}
    func destroy(_ id:UInt64){if let a=anchors.removeValue(forKey:id){session.remove(anchor:a)}}
    func clear(){for a in anchors.values{session.remove(anchor:a)};anchors.removeAll()}
}

public struct StableARAppleFrame { public let ref:stablear_frame_ref; public let depth:[stablear_depth_sample]; public let luma:[UInt8]?; public let lumaWidth:Int; public let lumaHeight:Int }

/** Host-owned ARKit adapter. The application still owns ARSession configuration/run/pause. */
public final class ARKitStableARAdapter {
    private let owner=Thread.current;private let store:ARKitAnchorStore;public let native:StableARNativeSession
    public init(session:ARSession){store=ARKitAnchorStore(session);native=StableARNativeSession(anchorStore:store)}
    private func check(){precondition(Thread.current===owner,"ARKitStableARAdapter owner thread violation")}
    public func capture(_ frame:ARFrame,copyLuma:Bool=true)->StableARAppleFrame?{check();let t=frame.camera.transform*cvToGL;let i=frame.camera.intrinsics,res=frame.camera.imageResolution;let k=stablear_intrinsics(fx:Double(i.columns.0.x),fy:Double(i.columns.1.y),cx:Double(i.columns.2.x),cy:Double(i.columns.2.y),width:Int32(res.width),height:Int32(res.height));let ns=Int64(frame.timestamp*1_000_000_000.0);let d=depth(frame,imageWidth:Int(res.width),imageHeight:Int(res.height),timestampNs:ns);guard let ref=native.capture(pose:rigid(t),intrinsics:k,timestampNs:ns,depth:d)else{return nil};let lum=copyLuma ? luma(frame.capturedImage):nil;return StableARAppleFrame(ref:ref,depth:d,luma:lum?.0,lumaWidth:lum?.1 ?? 0,lumaHeight:lum?.2 ?? 0)}
    public func close(){check();native.close();store.clear()}

    private func depth(_ frame:ARFrame,imageWidth:Int,imageHeight:Int,timestampNs:Int64)->[stablear_depth_sample]{
        let data=frame.sceneDepth ?? frame.smoothedSceneDepth;guard let d=data else{return []};let pb=d.depthMap,cb=d.confidenceMap;CVPixelBufferLockBaseAddress(pb,.readOnly);CVPixelBufferLockBaseAddress(cb,.readOnly);defer{CVPixelBufferUnlockBaseAddress(cb,.readOnly);CVPixelBufferUnlockBaseAddress(pb,.readOnly)}
        let w=CVPixelBufferGetWidth(pb),h=CVPixelBufferGetHeight(pb),dr=CVPixelBufferGetBytesPerRow(pb),cr=CVPixelBufferGetBytesPerRow(cb);guard let db=CVPixelBufferGetBaseAddress(pb),let conf=CVPixelBufferGetBaseAddress(cb)else{return []};let step=max(1,Int(ceil(sqrt(Double(w*h)/8000.0))));var out:[stablear_depth_sample]=[];out.reserveCapacity(8000);let origin:Int32=(frame.sceneDepth != nil) ? 3:1
        for y in stride(from:step/2,to:h,by:step){let dz=db.advanced(by:y*dr).assumingMemoryBound(to:Float32.self);let cz=conf.advanced(by:y*cr).assumingMemoryBound(to:UInt8.self);for x in stride(from:step/2,to:w,by:step){let z=Double(dz[x]);let c=min(1.0,Double(Int(cz[x])+1)/3.0);if(!z.isFinite||z<0.15||z>8||c<0.5){continue};let px=(Double(x)+0.5)*Double(imageWidth)/Double(w),py=(Double(y)+0.5)*Double(imageHeight)/Double(h);out.append(stablear_depth_sample(pixel:stablear_v2(x:px,y:py),z:z,confidence:c,origin:origin,source_timestamp_ns:timestampNs))}}
        return out
    }
    private func luma(_ pb:CVPixelBuffer)->([UInt8],Int,Int)?{CVPixelBufferLockBaseAddress(pb,.readOnly);defer{CVPixelBufferUnlockBaseAddress(pb,.readOnly)};let w=CVPixelBufferGetWidthOfPlane(pb,0),h=CVPixelBufferGetHeightOfPlane(pb,0),stride=CVPixelBufferGetBytesPerRowOfPlane(pb,0);guard let base=CVPixelBufferGetBaseAddressOfPlane(pb,0)else{return nil};var out=[UInt8](repeating:0,count:w*h);for y in 0..<h{out.withUnsafeMutableBytes{dst in memcpy(dst.baseAddress!.advanced(by:y*w),base.advanced(by:y*stride),w)}};return(out,w,h)}
}
