from pathlib import Path

path = Path('android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt')
text = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f'missing marker: {old[:160]!r}')
    text = text.replace(old, new, 1)

replace_once(
    '    private var lastDetectionCaptureAtMs = 0L\n    private var lastDetectorStatusAtMs = 0L\n',
    '    private var lastDetectionCaptureAtMs = 0L\n    private var lastTrackPublishAtMs = 0L\n    private var lastDetectorStatusAtMs = 0L\n'
)

replace_once(
    '''            remoteTracks.replaceSource(spatialApp.preferences.deviceId, tracks)
            realtime?.sendTracks(sequence++, tracks)
            latestInferenceMs = pending.inferenceMs
            latestLocalTrackCount = tracks.size
            val now = System.currentTimeMillis()
''',
    '''            remoteTracks.replaceSource(spatialApp.preferences.deviceId, tracks)
            realtime?.sendTracks(sequence++, tracks)
            val now = System.currentTimeMillis()
            lastTrackPublishAtMs = now
            latestInferenceMs = pending.inferenceMs
            latestLocalTrackCount = tracks.size
'''
)

replace_once(
    '''        val now = System.currentTimeMillis()
        if (now - lastDetectionCaptureAtMs >= DETECTION_INTERVAL_MS) {
''',
    '''        val now = System.currentTimeMillis()
        // Keep the filtered/predicted tracker flowing even between detector callbacks. This makes
        // motion look continuous on every participant and guarantees that a tracker timeout is
        // published as an empty complete-source snapshot instead of waiting for server TTL.
        if (now - lastTrackPublishAtMs >= TRACK_PUBLISH_INTERVAL_MS) {
            val tracks = localTracker.current(now)
            remoteTracks.replaceSource(spatialApp.preferences.deviceId, tracks)
            realtime?.sendTracks(sequence++, tracks)
            latestLocalTrackCount = tracks.size
            lastTrackPublishAtMs = now
        }
        if (now - lastDetectionCaptureAtMs >= DETECTION_INTERVAL_MS) {
'''
)

replace_once(
    '''    private fun setReporting(enabled: Boolean) {
        if (mode != ArMode.LIVE) return
        reporting = enabled
        if (enabled) ensureDetector() else stopDetector()
        reportButton?.text = if (enabled) "Stop reporting" else "Start reporting"
''',
    '''    private fun setReporting(enabled: Boolean) {
        if (mode != ArMode.LIVE) return
        reporting = enabled
        if (enabled) {
            lastTrackPublishAtMs = 0L
            ensureDetector()
        } else {
            stopDetector()
            localTracker.clear()
            remoteTracks.replaceSource(spatialApp.preferences.deviceId, emptyList())
            realtime?.sendTracks(sequence++, emptyList())
            lastTrackPublishAtMs = 0L
        }
        reportButton?.text = if (enabled) "Stop reporting" else "Start reporting"
'''
)

replace_once(
    '        private const val DETECTION_INTERVAL_MS = 120L\n',
    '        private const val DETECTION_INTERVAL_MS = 120L\n        private const val TRACK_PUBLISH_INTERVAL_MS = 120L\n'
)

path.write_text(text, encoding='utf-8')
print('finalized continuous Live track publishing')
