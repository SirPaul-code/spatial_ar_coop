#!/usr/bin/env python3
from pathlib import Path

recorder = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/stablear/StableArBenchmarkRecorder.kt")
text = recorder.read_text(encoding="utf-8")
if "private val rootWritten = AtomicBoolean(false)" not in text:
    text = text.replace(
        "    private val pending = AtomicInteger(0)\n",
        "    private val pending = AtomicInteger(0)\n    private val rootWritten = AtomicBoolean(false)\n",
        1,
    )
if "fun recordRoot(" not in text:
    anchor = "    fun record(\n"
    method = '''    /** Save the exact clicked exposure/pixel so offline ArUco scoring can follow that material point. */
    fun recordRoot(gray: GrayImage, intrinsics: Intrinsics, pixel: V2) {
        if (!enabled || closed || !rootWritten.compareAndSet(false, true)) return
        pending.incrementAndGet()
        worker.execute {
            try {
                val dir = directory ?: return@execute
                val image = resizeGray(gray.bytes, gray.width, gray.height, OUT_WIDTH, OUT_HEIGHT)
                FileOutputStream(File(dir, "root.pgm")).use { out ->
                    out.write("P5\\n$OUT_WIDTH $OUT_HEIGHT\\n255\\n".toByteArray(Charsets.US_ASCII))
                    out.write(image)
                }
                val scaled = scalePoint(pixel, gray.width, gray.height)
                val sx = OUT_WIDTH.toDouble() / gray.width
                val sy = OUT_HEIGHT.toDouble() / gray.height
                File(dir, "root.json").writeText(
                    "{\\n" +
                        "  \\"image_path\\": \\"root.pgm\\",\\n" +
                        "  \\"pixel_x\\": ${fmt(scaled.x)},\\n" +
                        "  \\"pixel_y\\": ${fmt(scaled.y)},\\n" +
                        "  \\"fx\\": ${fmt(intrinsics.fx * sx)},\\n" +
                        "  \\"fy\\": ${fmt(intrinsics.fy * sy)}\\n" +
                        "}\\n",
                    Charsets.UTF_8
                )
            } finally {
                pending.decrementAndGet()
            }
        }
    }

'''
    if text.count(anchor) != 1:
        raise SystemExit("StableArBenchmarkRecorder record anchor changed")
    text = text.replace(anchor, method + anchor, 1)
recorder.write_text(text, encoding="utf-8")

coordinator = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/stablear/StableArCoordinator.kt")
text = coordinator.read_text(encoding="utf-8")
old = "        if (benchmarkAttachmentId == null) benchmarkAttachmentId = id\n"
new = "        if (benchmarkAttachmentId == null) {\n            benchmarkAttachmentId = id\n            benchmark.recordRoot(gray, sample.ref.intrinsics, pixel)\n        }\n"
if new not in text:
    if text.count(old) != 1:
        raise SystemExit("StableArCoordinator benchmark root anchor changed")
    text = text.replace(old, new, 1)
coordinator.write_text(text, encoding="utf-8")
print("StableAR exact-root benchmark capture ready")
