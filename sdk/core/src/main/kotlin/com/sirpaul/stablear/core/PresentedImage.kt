package com.sirpaul.stablear.core

/** Affine mapping of the visible camera crop, excluding letterboxing/identity footers. */
data class PresentedImage(val origin: V2,val horizontal: V2,val vertical: V2) {
    fun sensorPixel(normalized: V2,k: Intrinsics): V2? {
        if(normalized.x !in 0.0..1.0 || normalized.y !in 0.0..1.0) return null
        val p=V2(origin.x+horizontal.x*normalized.x+vertical.x*normalized.y,
            origin.y+horizontal.y*normalized.x+vertical.y*normalized.y)
        return p.takeIf(k::contains)
    }
    companion object {
        fun upright(k: Intrinsics,clockwiseRotation: Int): PresentedImage {
            val w=k.width-1.0; val h=k.height-1.0
            return when(clockwiseRotation) {
                0 -> PresentedImage(V2(0.0,0.0),V2(w,0.0),V2(0.0,h))
                90 -> PresentedImage(V2(0.0,h),V2(0.0,-h),V2(w,0.0))
                180 -> PresentedImage(V2(w,h),V2(-w,0.0),V2(0.0,-h))
                270 -> PresentedImage(V2(w,0.0),V2(0.0,h),V2(-w,0.0))
                else -> throw IllegalArgumentException("Rotation must be 0, 90, 180 or 270")
            }
        }
    }
}
