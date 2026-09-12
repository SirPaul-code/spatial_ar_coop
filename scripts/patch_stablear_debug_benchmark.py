#!/usr/bin/env python3
from pathlib import Path

path = Path("android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt")
text = path.read_text(encoding="utf-8")
old = '    private val stableArBenchmarkEnabled by lazy { intent.getBooleanExtra(EXTRA_STABLEAR_BENCHMARK, false) }\n'
new = '    private val stableArBenchmarkEnabled by lazy { BuildConfig.STABLEAR_BENCHMARK_DEFAULT || intent.getBooleanExtra(EXTRA_STABLEAR_BENCHMARK, false) }\n'
if new not in text:
    if text.count(old) != 1:
        raise SystemExit("StableAR benchmark enable anchor changed")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Debug StableAR benchmark default ready")
