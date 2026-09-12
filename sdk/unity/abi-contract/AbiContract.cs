using System;
using System.Runtime.InteropServices;
using StableAR;
class AbiContract {
 static void Eq<T>(int n){var s=Marshal.SizeOf<T>();if(s!=n)throw new Exception(typeof(T).Name+" size "+s+" != "+n);}
 static void Main(){Eq<V2>(16);Eq<V3>(24);Eq<Quat>(32);Eq<Rigid>(56);Eq<Intrinsics>(40);Eq<DepthSample>(48);Eq<FrameRef>(136);Eq<AttachmentSnapshot>(192);Eq<VisualObservation>(192);Eq<LockPolicy>(64);Eq<SessionConfig>(96);Console.WriteLine("PASS: StableAR C# ABI layout contract");}
}
