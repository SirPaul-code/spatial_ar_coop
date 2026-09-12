import Foundation
import StableARNative

public protocol StableARAnchorStore: AnyObject {
    func create(_ pose: stablear_rigid) -> UInt64
    func locate(_ id: UInt64) -> stablear_rigid?
    func destroy(_ id: UInt64)
}
private final class AnchorBox { let store: StableARAnchorStore; init(_ s: StableARAnchorStore){store=s} }
private let createAnchor: stablear_anchor_create_fn = { context, pose in
    guard let context, let pose else { return 0 }; return Unmanaged<AnchorBox>.fromOpaque(context).takeUnretainedValue().store.create(pose.pointee)
}
private let locateAnchor: stablear_anchor_locate_fn = { context, id, out in
    guard let context, let out, let p=Unmanaged<AnchorBox>.fromOpaque(context).takeUnretainedValue().store.locate(id) else { return 0 }; out.pointee=p; return 1
}
private let destroyAnchor: stablear_anchor_destroy_fn = { context, id in
    guard let context else { return }; Unmanaged<AnchorBox>.fromOpaque(context).takeUnretainedValue().store.destroy(id)
}

public final class StableARNativeSession {
    private let owner=Thread.current; private let box:AnchorBox; private var handle:OpaquePointer?
    public init(anchorStore:StableARAnchorStore){box=AnchorBox(anchorStore);let ctx=Unmanaged.passUnretained(box).toOpaque();let cb=stablear_anchor_callbacks(context:ctx,create:createAnchor,locate:locateAnchor,destroy:destroyAnchor);let cfg=stablear_default_session_config();handle=stablear_session_create(cb,cfg);precondition(handle != nil,"StableAR session create failed")}
    deinit { assert(handle == nil, "StableARNativeSession must be explicitly closed on its owner thread") }
    private func h()->OpaquePointer{precondition(Thread.current===owner,"StableAR owner thread violation");guard let h=handle else{preconditionFailure("StableAR session closed")};return h}
    public var epoch:UInt64 { stablear_session_epoch(h()) }
    public func capture(pose:stablear_rigid,intrinsics:stablear_intrinsics,timestampNs:Int64,depth:[stablear_depth_sample])->stablear_frame_ref? {var p=pose,k=intrinsics,out=stablear_frame_ref();return depth.withUnsafeBufferPointer{buf in stablear_session_capture(h(),&p,&k,timestampNs,buf.baseAddress,buf.count,&out) != 0 ? out:nil}}
    public func freeze(_ id:UInt64)->stablear_frame_ref?{var out=stablear_frame_ref();return stablear_session_freeze(h(),id,&out) != 0 ? out:nil}
    public func unfreeze(_ id:UInt64){stablear_session_unfreeze(h(),id)}
    public func place(frame:stablear_frame_ref,depth:[stablear_depth_sample],pixel:stablear_v2)->stablear_attachment_snapshot?{var f=frame,out=stablear_attachment_snapshot();return depth.withUnsafeBufferPointer{b in stablear_session_place(h(),&f,b.baseAddress,b.count,pixel,&out) != 0 ? out:nil}}
    public func context(attachmentId:UInt64,frame:stablear_frame_ref)->(stablear_rigid,UInt64,UInt64)?{var f=frame,p=stablear_rigid(),g:UInt64=0,a:UInt64=0;return stablear_session_context(h(),attachmentId,&f,&p,&g,&a) != 0 ? (p,g,a):nil}
    public func observe(attachmentId:UInt64,observation:stablear_visual_observation)->(stablear_attachment_snapshot,Bool)?{var o=observation,s=stablear_attachment_snapshot(),accepted:Int32=0;return stablear_session_observe(h(),attachmentId,&o,&s,&accepted) != 0 ? (s,accepted != 0):nil}
    public func worldPoint(_ id:UInt64)->stablear_v3?{var p=stablear_v3();return stablear_session_world_point(h(),id,&p) != 0 ? p:nil}
    public func snapshot(_ id:UInt64)->stablear_attachment_snapshot?{var s=stablear_attachment_snapshot();return stablear_session_snapshot(h(),id,&s) != 0 ? s:nil}
    public func visibility(_ id:UInt64,visible:Bool,lost:Bool=false){stablear_session_visibility(h(),id,visible ? 1:0,lost ? 1:0)}
    public func remove(_ id:UInt64){stablear_session_remove(h(),id)}
    public func trackingLost(){stablear_session_tracking_lost(h())}
    public func reset(){stablear_session_reset(h())}
    public func close(){precondition(Thread.current===owner,"StableAR close must be on owner thread");if let x=handle{handle=nil;stablear_session_destroy(x)}}
}
