from pathlib import Path
p = Path('server/src/persistence.mjs')
text = p.read_text()
old = 'function identityMatrix() { return [1, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 1]; }'
new = 'function identityMatrix() { return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; }'
assert text.count(old) == 1, 'unexpected identityMatrix source'
p.write_text(text.replace(old, new, 1))
