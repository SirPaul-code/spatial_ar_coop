#!/usr/bin/env python3
from pathlib import Path

path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/stablear/StableArCoordinator.kt")
text = path.read_text(encoding="utf-8")
needle = "    fun status(): StableArRuntimeStatus = StableArRuntimeStatus(\n"
insert = "    /** Clear pending geometric transactions when host ARCore tracking pauses. GL owner thread only. */\n    fun trackingLost() { adapter.trackingLost() }\n\n" + needle
if "fun trackingLost()" not in text:
    if text.count(needle) != 1:
        raise SystemExit("StableArCoordinator status anchor changed")
    text = text.replace(needle, insert, 1)
path.write_text(text, encoding="utf-8")
print("StableAR coordinator lifecycle hook ready")
