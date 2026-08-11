from pathlib import Path

path = Path('android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt')
text = path.read_text(encoding='utf-8')

broken = 'arErrorText.text = "AR session could not start\n\n$category"'
# In the generated source, Python interpreted the escapes and produced an actual multiline Kotlin
# string. Replace that exact multiline sequence with a normal escaped Kotlin string literal.
if broken not in text:
    raise SystemExit('broken AR error literal not found')
text = text.replace(broken, 'arErrorText.text = "AR session could not start\\n\\n$category"', 1)
text = text.replace('        retryArButton?.isEnabled = !sessionCloseInFlight.get()\n', '', 1)
path.write_text(text, encoding='utf-8')
print('sanitized ArActivity.kt')
