#!/usr/bin/env python3
from pathlib import Path

# One-shot sanitizer for the final recovery-generated lifecycle source.
path = Path('android/app/src/main/java/com/sirpaul/spatialarcoop/ArActivity.kt')
text = path.read_text(encoding='utf-8')

broken_error = '''arErrorText.text = "AR session could not start

$category"'''
fixed_error = 'arErrorText.text = "AR session could not start\\n\\n$category"'
if broken_error in text:
    text = text.replace(broken_error, fixed_error, 1)

text = text.replace(
    'if (!::backgroundRenderer.isInitialized) return"',
    'if (!::backgroundRenderer.isInitialized) return',
    1
)
text = text.replace(
    'apiToken = spatialApp.preferences.apiToken,',
    'apiToken = map.accessKey.ifBlank { spatialApp.preferences.apiToken },',
    1
)

assert broken_error not in text
assert 'return"' not in text
assert 'apiToken = spatialApp.preferences.apiToken,' not in text

path.write_text(text, encoding='utf-8')
print('Generated Android source sanitized')
