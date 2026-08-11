export const PROTOCOL_VERSION = 2;
const ALLOWED_ROLES = new Set(['mapper', 'participant', 'sensor', 'viewer', 'observer']);
const ALLOWED_LABEL = /^[a-zA-Z0-9_.:-]{1,48}$/;

export class ProtocolError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'ProtocolError';
    this.code = code;
  }
}

export function validateClientIdentity({ mapId, clientId, role }) {
  return {
    mapId: safeId(mapId, 'mapId'),
    clientId: safeId(clientId, 'clientId'),
    role: ALLOWED_ROLES.has(role) ? role : 'observer'
  };
}

export function parseClientMessage(raw) {
  let value;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new ProtocolError('INVALID_JSON', 'Message is not valid JSON');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new ProtocolError('INVALID_MESSAGE', 'Message must be a JSON object');
  }
  const type = String(value.type ?? '');
  switch (type) {
    case 'track_batch':
      return normalizeTrackBatch(value);
    case 'client_pose':
      return normalizeClientPose(value);
    case 'manual_marker':
      return normalizeManualMarker(value);
    case 'status':
      return {
        type,
        state: limitedString(value.state, 64, 'UNKNOWN'),
        detail: limitedString(value.detail, 256, '')
      };
    case 'ping':
      return { type: 'ping', sentAtMs: finiteInt(value.sentAtMs, Date.now()) };
    default:
      throw new ProtocolError('UNSUPPORTED_TYPE', `Unsupported message type: ${type || '(empty)'}`);
  }
}

function normalizeTrackBatch(value) {
  if (!Array.isArray(value.tracks)) throw new ProtocolError('INVALID_TRACKS', 'tracks must be an array');
  if (value.tracks.length > 64) throw new ProtocolError('TOO_MANY_TRACKS', 'A batch may contain at most 64 tracks');
  return {
    type: 'track_batch',
    sequence: finiteInt(value.sequence, 0),
    sentAtMs: finiteInt(value.sentAtMs, Date.now()),
    replaceSource: value.replaceSource === true,
    tracks: value.tracks.map((track, index) => normalizeTrack(track, index))
  };
}

function normalizeTrack(track, index) {
  if (!track || typeof track !== 'object') throw new ProtocolError('INVALID_TRACK', `track ${index} is invalid`);
  const id = safeId(track.id ?? `t${index}`, `track[${index}].id`);
  const label = limitedString(track.label, 48, 'unknown').toLowerCase();
  if (!ALLOWED_LABEL.test(label)) throw new ProtocolError('INVALID_LABEL', `track ${index} label is invalid`);
  return {
    id,
    label,
    confidence: finiteNumber(track.confidence, 0, 0, 1),
    position: vector(track.position, 3, `track[${index}].position`),
    velocity: vector(track.velocity ?? [0, 0, 0], 3, `track[${index}].velocity`),
    uncertaintyMeters: finiteNumber(track.uncertaintyMeters, 0.5, 0.01, 50),
    observedAtMs: finiteInt(track.observedAtMs, Date.now()),
    extentMeters: physicalVector(track.extentMeters ?? defaultExtent(label), 3, `track[${index}].extentMeters`),
    yawRadians: finiteNumber(track.yawRadians, 0, -Math.PI, Math.PI)
  };
}

function defaultExtent(label) {
  switch (label) {
    case 'person': return [0.60, 1.72, 0.45];
    case 'car': return [1.85, 1.50, 4.40];
    case 'bird': return [0.45, 0.45, 0.55];
    case 'dog': return [0.55, 0.70, 1.00];
    case 'cat': return [0.35, 0.42, 0.65];
    default: return [0.65, 0.65, 0.65];
  }
}

function normalizeClientPose(value) {
  const pose = value.pose;
  if (!pose || typeof pose !== 'object') throw new ProtocolError('INVALID_POSE', 'pose is required');
  return {
    type: 'client_pose',
    pose: {
      position: vector(pose.position, 3, 'pose.position'),
      rotation: vector(pose.rotation, 4, 'pose.rotation'),
      tracking: limitedString(pose.tracking, 32, 'UNKNOWN'),
      atMs: finiteInt(pose.atMs, Date.now())
    }
  };
}

function normalizeManualMarker(value) {
  const marker = value.marker;
  if (!marker || typeof marker !== 'object') throw new ProtocolError('INVALID_MARKER', 'marker is required');
  return {
    type: 'manual_marker',
    marker: {
      id: safeId(marker.id ?? `m-${Date.now()}`, 'marker.id'),
      label: limitedString(marker.label, 48, 'marker'),
      position: vector(marker.position, 3, 'marker.position'),
      expiresAtMs: Math.min(Date.now() + 24 * 60 * 60 * 1000, Math.max(Date.now() + 1000, finiteInt(marker.expiresAtMs, Date.now() + 60000)))
    }
  };
}

export function safeId(value, field = 'id') {
  const text = String(value ?? '');
  if (!/^[a-zA-Z0-9._-]{1,96}$/.test(text)) throw new ProtocolError('INVALID_ID', `${field} must match [a-zA-Z0-9._-]`);
  return text;
}

function vector(value, size, field) {
  if (!Array.isArray(value) || value.length !== size) throw new ProtocolError('INVALID_VECTOR', `${field} must have ${size} numbers`);
  return value.map((entry, index) => finiteNumber(entry, 0, -100000, 100000, `${field}[${index}]`));
}

function physicalVector(value, size, field) {
  if (!Array.isArray(value) || value.length !== size) throw new ProtocolError('INVALID_VECTOR', `${field} must have ${size} numbers`);
  return value.map((entry, index) => finiteNumber(entry, 0.5, 0.03, 15, `${field}[${index}]`));
}

function finiteNumber(value, fallback, min, max, field = 'number') {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    if (fallback !== undefined) return fallback;
    throw new ProtocolError('INVALID_NUMBER', `${field} is not finite`);
  }
  return Math.min(max, Math.max(min, number));
}

function finiteInt(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.trunc(number) : fallback;
}

function limitedString(value, maxLength, fallback) {
  const text = String(value ?? fallback ?? '');
  return text.slice(0, maxLength);
}
