using UnityEngine;
namespace StableAR {
public static class UnityCoordinates {
    /** Reflect Unity left-handed world through Z into the StableAR right-handed world. */
    public static V3 WorldPoint(Vector3 p)=>new V3{x=p.x,y=p.y,z=-p.z};
    public static Vector3 UnityPoint(V3 p)=>new Vector3((float)p.x,(float)p.y,(float)-p.z);
    public static Rigid WorldPose(Vector3 p,Quaternion q)=>new Rigid{t=WorldPoint(p),q=new Quat{x=-q.x,y=-q.y,z=q.z,w=q.w}};
    /** Unity camera is +Y up/+Z forward. StableAR camera is +Y down/+Z forward after world handedness reflection. */
    public static Rigid CameraPose(Vector3 p,Quaternion q){var rh=new Quat{x=-q.x,y=-q.y,z=q.z,w=q.w};return new Rigid{t=WorldPoint(p),q=Mul(rh,new Quat{x=1,y=0,z=0,w=0})};}
    static Quat Mul(Quat a,Quat b)=>new Quat{x=a.w*b.x+a.x*b.w+a.y*b.z-a.z*b.y,y=a.w*b.y-a.x*b.z+a.y*b.w+a.z*b.x,z=a.w*b.z+a.x*b.y-a.y*b.x+a.z*b.w,w=a.w*b.w-a.x*b.x-a.y*b.y-a.z*b.z};
}
}
