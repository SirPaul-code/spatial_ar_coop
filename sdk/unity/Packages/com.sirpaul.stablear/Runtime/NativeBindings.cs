using System;
using System.Runtime.InteropServices;
namespace StableAR {
[StructLayout(LayoutKind.Sequential)] public struct V2 { public double x,y; }
[StructLayout(LayoutKind.Sequential)] public struct V3 { public double x,y,z; }
[StructLayout(LayoutKind.Sequential)] public struct Quat { public double x,y,z,w; }
[StructLayout(LayoutKind.Sequential)] public struct Rigid { public V3 t; public Quat q; }
[StructLayout(LayoutKind.Sequential)] public struct Intrinsics { public double fx,fy,cx,cy; public int width,height; }
[StructLayout(LayoutKind.Sequential)] public struct DepthSample { public V2 pixel; public double z,confidence; public int origin; public long sourceTimestampNs; }
[StructLayout(LayoutKind.Sequential)] public struct FrameRef { public ulong id,epoch,anchorId; public long cameraTimestampNs,capturedNs; public Rigid anchorFromCamera; public Intrinsics intrinsics; }
[StructLayout(LayoutKind.Sequential)] public struct AttachmentSnapshot { public ulong id,generation,rootFrameId,rootEpoch,rootAnchorId; public long rootCameraTimestampNs; public Rigid rootCameraInAnchor; public Intrinsics rootIntrinsics; public V2 rootPixel; public double depthM,conditionalSigmaM,travelM; public int state; }
[StructLayout(LayoutKind.Sequential)] public struct VisualObservation { public ulong frameId,epoch,anchorId,generation; public long cameraTimestampNs,capturedNs; public Rigid cameraInAnchor; public Intrinsics intrinsics; public V2 pixel; public int inliers; public double forwardBackwardPx,reprojectionPx,sigmaPx; }
[StructLayout(LayoutKind.Sequential)] public struct LockPolicy { public int minimumViews; public double minimumParallaxDeg,maxCorrectionM,totalTravelM,maxConditionalSigmaM,assumedCommonTranslationSigmaM,systematicFloorM; public long maxObservationAgeNs; }
[StructLayout(LayoutKind.Sequential)] public struct SessionConfig { public int maxFrames; public long historyNs; public int maxAnchors; public long maxFreezeNs; public LockPolicy policy; }
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] public delegate ulong AnchorCreate(IntPtr context,ref Rigid pose);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] public delegate int AnchorLocate(IntPtr context,ulong id,out Rigid pose);
[UnmanagedFunctionPointer(CallingConvention.Cdecl)] public delegate void AnchorDestroy(IntPtr context,ulong id);
[StructLayout(LayoutKind.Sequential)] public struct AnchorCallbacks { public IntPtr context; public AnchorCreate create; public AnchorLocate locate; public AnchorDestroy destroy; }
public interface IAnchorStore { ulong Create(Rigid worldFromAnchor); bool Locate(ulong id,out Rigid worldFromAnchor); void Destroy(ulong id); }
internal static class Native {
#if UNITY_IOS && !UNITY_EDITOR
 const string Lib="__Internal";
#else
 const string Lib="stablear";
#endif
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern uint stablear_abi_version();
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern SessionConfig stablear_default_session_config();
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern IntPtr stablear_session_create(AnchorCallbacks callbacks,SessionConfig config);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern void stablear_session_destroy(IntPtr session);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern ulong stablear_session_epoch(IntPtr session);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_capture(IntPtr session,ref Rigid pose,ref Intrinsics intrinsics,long timestampNs,[In] DepthSample[] depth,UIntPtr count,out FrameRef frame);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_freeze(IntPtr session,ulong frameId,out FrameRef frame);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern void stablear_session_unfreeze(IntPtr session,ulong frameId);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_place(IntPtr session,ref FrameRef frame,[In] DepthSample[] depth,UIntPtr count,V2 pixel,out AttachmentSnapshot snapshot);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_context(IntPtr session,ulong attachmentId,ref FrameRef frame,out Rigid cameraInAnchor,out ulong generation,out ulong anchorId);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_observe(IntPtr session,ulong attachmentId,ref VisualObservation observation,out AttachmentSnapshot snapshot,out int accepted);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_world_point(IntPtr session,ulong attachmentId,out V3 point);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern int stablear_session_snapshot(IntPtr session,ulong attachmentId,out AttachmentSnapshot snapshot);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern void stablear_session_visibility(IntPtr session,ulong attachmentId,int visible,int lost);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern void stablear_session_remove(IntPtr session,ulong attachmentId);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern void stablear_session_tracking_lost(IntPtr session);
 [DllImport(Lib,CallingConvention=CallingConvention.Cdecl)] internal static extern void stablear_session_reset(IntPtr session);
}
public sealed class Session : IDisposable {
 sealed class State { public readonly IAnchorStore Store; public State(IAnchorStore s){Store=s;} }
 readonly int owner=System.Threading.Thread.CurrentThread.ManagedThreadId; readonly State state; readonly GCHandle stateHandle; readonly AnchorCreate create; readonly AnchorLocate locate; readonly AnchorDestroy destroy; IntPtr native;
 public Session(IAnchorStore store){if(store==null)throw new ArgumentNullException(nameof(store));state=new State(store);stateHandle=GCHandle.Alloc(state);create=OnCreate;locate=OnLocate;destroy=OnDestroy;var cb=new AnchorCallbacks{context=GCHandle.ToIntPtr(stateHandle),create=create,locate=locate,destroy=destroy};native=Native.stablear_session_create(cb,Native.stablear_default_session_config());if(native==IntPtr.Zero){stateHandle.Free();throw new InvalidOperationException("StableAR native session create failed");}}
 void Check(){if(System.Threading.Thread.CurrentThread.ManagedThreadId!=owner)throw new InvalidOperationException("StableAR Session is owner-thread confined");if(native==IntPtr.Zero)throw new ObjectDisposedException(nameof(Session));}
 static State S(IntPtr p)=>(State)GCHandle.FromIntPtr(p).Target;
 static ulong OnCreate(IntPtr p,ref Rigid pose){try{return S(p).Store.Create(pose);}catch{return 0;}}
 static int OnLocate(IntPtr p,ulong id,out Rigid pose){try{return S(p).Store.Locate(id,out pose)?1:0;}catch{pose=default;return 0;}}
 static void OnDestroy(IntPtr p,ulong id){try{S(p).Store.Destroy(id);}catch{}}
 public ulong Epoch{get{Check();return Native.stablear_session_epoch(native);}}
 public bool Capture(Rigid worldFromCamera,Intrinsics k,long timestampNs,DepthSample[] depth,out FrameRef frame){Check();depth=depth??Array.Empty<DepthSample>();return Native.stablear_session_capture(native,ref worldFromCamera,ref k,timestampNs,depth,(UIntPtr)depth.Length,out frame)!=0;}
 public bool Freeze(ulong frameId,out FrameRef frame){Check();return Native.stablear_session_freeze(native,frameId,out frame)!=0;} public void Unfreeze(ulong id){Check();Native.stablear_session_unfreeze(native,id);}
 public bool Place(ref FrameRef frame,DepthSample[] depth,V2 pixel,out AttachmentSnapshot a){Check();depth=depth??Array.Empty<DepthSample>();return Native.stablear_session_place(native,ref frame,depth,(UIntPtr)depth.Length,pixel,out a)!=0;}
 public bool Context(ulong id,ref FrameRef frame,out Rigid cameraInAnchor,out ulong generation,out ulong anchorId){Check();return Native.stablear_session_context(native,id,ref frame,out cameraInAnchor,out generation,out anchorId)!=0;}
 public bool Observe(ulong id,ref VisualObservation observation,out AttachmentSnapshot snapshot,out bool committed){Check();int accepted;var ok=Native.stablear_session_observe(native,id,ref observation,out snapshot,out accepted)!=0;committed=accepted!=0;return ok;}
 public bool WorldPoint(ulong id,out V3 p){Check();return Native.stablear_session_world_point(native,id,out p)!=0;} public bool Snapshot(ulong id,out AttachmentSnapshot s){Check();return Native.stablear_session_snapshot(native,id,out s)!=0;}
 public void Visibility(ulong id,bool visible,bool lost=false){Check();Native.stablear_session_visibility(native,id,visible?1:0,lost?1:0);} public void Remove(ulong id){Check();Native.stablear_session_remove(native,id);} public void TrackingLost(){Check();Native.stablear_session_tracking_lost(native);} public void Reset(){Check();Native.stablear_session_reset(native);}
 public void Dispose(){if(System.Threading.Thread.CurrentThread.ManagedThreadId!=owner)throw new InvalidOperationException("Dispose on owner thread");if(native!=IntPtr.Zero){var n=native;native=IntPtr.Zero;Native.stablear_session_destroy(n);stateHandle.Free();GC.KeepAlive(create);GC.KeepAlive(locate);GC.KeepAlive(destroy);}}
}
}
