# Surface anchoring research

Read [REPORT.md](REPORT.md) for the audit, research, mathematical design, results,
limitations and integration sequence. Product code has not been changed.

## Reproduce

Use Python 3.13 in an isolated environment, install `requirements.txt`, then:

```sh
python research_sdk/experiments.py
python research_sdk/surface_lock.py
node --test showme/web/geometry.test.mjs
```

Compile the unchanged baseline pure Kotlin geometry (a Kotlin/JVM compiler is required):

```sh
kotlinc android/app/src/main/java/com/sirpaul/spatialnomap/Models.kt \
  android/showme/src/main/java/com/sirpaul/showme/ShowMeGeometry.kt \
  research_sdk/kotlin/GeometryProbe.kt -include-runtime -d /tmp/geometry-probe.jar
java -jar /tmp/geometry-probe.jar
```

`results.json` and `surface_lock_results.json` contain the executed synthetic results.
The two Python programs regenerate their result files. The surface-ray prototype
requires supplied matches and poses; it is not a complete image tracker or Android
SDK. Its favourable results assume accurate poses, and its negative experiment
shows how small shared pose error defeats an overconfident estimator. Always read
accepted-case errors together with acceptance counts.

`generated/branch_inventory.*` records 41 original branch heads. Structural coverage
is not a full semantic audit of every historical line. See report for actual scope.

No pretrained neural weights, recorded user camera footage or physical sensor
measurements are included. No model download, model training or paid service is
needed for these experiments. No APK was built in this research pass.
