import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';

const levels = { debug: 10, info: 20, warn: 30, error: 40 };

export class JsonLogger {
  constructor({ dataDir, level = 'info', ringSize = 500, stdout = true }) {
    this.level = levels[level] ?? levels.info;
    this.stdout = stdout;
    this.ringSize = ringSize;
    this.entries = [];
    this.events = new EventEmitter();
    this.logDir = path.join(dataDir, 'logs');
    fs.mkdirSync(this.logDir, { recursive: true });
    this.logFile = path.join(this.logDir, 'server.jsonl');
  }

  debug(message, fields = {}) { this.write('debug', message, fields); }
  info(message, fields = {}) { this.write('info', message, fields); }
  warn(message, fields = {}) { this.write('warn', message, fields); }
  error(message, fields = {}) { this.write('error', message, fields); }

  write(level, message, fields = {}) {
    const entry = {
      ts: new Date().toISOString(),
      level,
      message,
      ...sanitizeFields(fields)
    };
    this.entries.push(entry);
    if (this.entries.length > this.ringSize) this.entries.splice(0, this.entries.length - this.ringSize);
    const line = `${JSON.stringify(entry)}\n`;
    fs.appendFileSync(this.logFile, line, 'utf8');
    if (this.stdout && (levels[level] ?? 100) >= this.level) {
      const method = level === 'error' ? console.error : level === 'warn' ? console.warn : console.log;
      method(line.trimEnd());
    }
    this.events.emit('entry', entry);
  }

  recent(limit = 100) {
    return this.entries.slice(-Math.min(500, Math.max(1, limit)));
  }

  subscribe(listener) {
    this.events.on('entry', listener);
    return () => this.events.off('entry', listener);
  }
}

function sanitizeFields(fields) {
  const output = {};
  for (const [key, value] of Object.entries(fields ?? {})) {
    if (value instanceof Error) {
      output[key] = { name: value.name, message: value.message, stack: value.stack };
    } else if (typeof value === 'bigint') {
      output[key] = value.toString();
    } else {
      output[key] = value;
    }
  }
  return output;
}
