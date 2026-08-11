from pathlib import Path

path = Path('android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt')
text = path.read_text(encoding='utf-8')
changed = False

# Repair the one-shot generator's multiline literal if an earlier intermediate commit is checked out.
broken = 'arErrorText.text = "AR session could not start\n\n$category"'
fixed = 'arErrorText.text = "AR session could not start\\n\\n$category"'
if broken in text:
    text = text.replace(broken, fixed, 1)
    changed = True
elif fixed not in text:
    raise SystemExit('AR error literal marker not found')

retry_line = '        retryArButton?.isEnabled = !sessionCloseInFlight.get()\n'
if retry_line in text:
    text = text.replace(retry_line, '', 1)
    changed = True

old_snapshot = '''            val tracks = localTracker.update(observations)
            remoteTracks.update(tracks)
            realtime?.sendTracks(sequence++, tracks)
'''
new_snapshot = '''            val tracks = localTracker.update(observations)
            // The reporting phone renders the exact same stable spatial tracker state it publishes.
            // Replace only this source so locally-expired birds disappear immediately while remote
            // participants remain untouched until their own batch/expiry events arrive.
            remoteTracks.replaceSource(spatialApp.preferences.deviceId, tracks)
            realtime?.sendTracks(sequence++, tracks)
'''
if old_snapshot in text:
    text = text.replace(old_snapshot, new_snapshot, 1)
    changed = True
elif new_snapshot not in text:
    raise SystemExit('local tracker snapshot marker not found')

path.write_text(text, encoding='utf-8')
print('updated ArActivity.kt' if changed else 'ArActivity.kt already final')
