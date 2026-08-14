const DEFAULT_GATE_METERS = {
  person: 1.8,
  car: 3.2,
  bird: 1.2,
  dog: 1.6,
  cat: 1.3,
  unknown: 1.8
};

export class SpatialFusionEngine {
  constructor({
    historyWindowMs = 10 * 60 * 1000,
    historyIntervalMs = 500,
    maxFrames = 1200
  } = {}) {
    this.historyWindowMs = Math.max(10_000, historyWindowMs);
    this.historyIntervalMs = Math.max(100, historyIntervalMs);
    this.maxFrames = Math.max(20, maxFrames);
    this.maps = new Map();
  }

  ingest(mapId, snapshot, now = Date.now()) {
    const state = this.#state(mapId);
    const rawTracks = Array.isArray(snapshot?.tracks) ? snapshot.tracks : [];
    const clients = Array.isArray(snapshot?.clients) ? snapshot.clients : [];
    const fusedTracks = this.#fuse(state, rawTracks, now);
    const localizedClients = clients.filter((client) => client?.pose?.position).length;
    const multiSensorTracks = fusedTracks.filter((track) => track.sourceCount > 1).length;
    const latest = {
      ...snapshot,
      mapId,
      serverTimeMs: Number(snapshot?.serverTimeMs) || now,
      fusedTracks,
      fusion: {
        rawObservationCount: rawTracks.length,
        fusedEntityCount: fusedTracks.length,
        multiSensorEntityCount: multiSensorTracks,
        connectedClientCount: clients.length,
        localizedClientCount: localizedClients,
        replayFrameCount: state.frames.length,
        replayWindowMs: this.historyWindowMs
      }
    };
    state.latest = latest;
    this.#record(state, latest, now);
    return latest;
  }

  latest(mapId) {
    return this.maps.get(mapId)?.latest ?? null;
  }

  replay(mapId, { lookbackMs = 5 * 60 * 1000, limit = 600 } = {}) {
    const state = this.maps.get(mapId);
    if (!state) return { mapId, frames: [], oldestAtMs: null, newestAtMs: null };
    const boundedLookback = Math.max(1_000, Math.min(this.historyWindowMs, Number(lookbackMs) || 0));
    const boundedLimit = Math.max(1, Math.min(2000, Math.trunc(Number(limit) || 600)));
    const newestBufferedAtMs = state.frames.at(-1)?.serverTimeMs ?? Date.now();
    const cutoff = newestBufferedAtMs - boundedLookback;
    const candidates = state.frames.filter((frame) => frame.serverTimeMs >= cutoff);
    const frames = downsample(candidates, boundedLimit);
    return {
      mapId,
      frames,
      oldestAtMs: frames[0]?.serverTimeMs ?? null,
      newestAtMs: frames.at(-1)?.serverTimeMs ?? null,
      totalBufferedFrames: state.frames.length,
      windowMs: this.historyWindowMs
    };
  }

  metrics() {
    let fusedTracks = 0;
    let replayFrames = 0;
    for (const state of this.maps.values()) {
      fusedTracks += state.latest?.fusedTracks?.length ?? 0;
      replayFrames += state.frames.length;
    }
    return { fusedTracks, replayFrames, fusionMaps: this.maps.size };
  }

  clear(mapId) {
    this.maps.delete(mapId);
  }

  retain(mapIds) {
    const keep = mapIds instanceof Set ? mapIds : new Set(mapIds ?? []);
    for (const mapId of this.maps.keys()) {
      if (!keep.has(mapId)) this.maps.delete(mapId);
    }
  }

  #state(mapId) {
    let state = this.maps.get(mapId);
    if (!state) {
      state = {
        nextId: 1,
        previous: [],
        latest: null,
        frames: [],
        lastFrameAtMs: 0
      };
      this.maps.set(mapId, state);
    }
    return state;
  }

  #fuse(state, rawTracks, now) {
    const observations = rawTracks
      .map(normalizeObservation)
      .filter(Boolean)
      .sort((a, b) => b.confidence - a.confidence);

    const clusters = [];
    for (const observation of observations) {
      let best = null;
      let bestScore = Infinity;
      for (const cluster of clusters) {
        if (cluster.label !== observation.label) continue;
        if (cluster.sourceIds.has(observation.sourceId)) continue;
        const center = weightedVector(cluster.observations, 'position');
        const distance = distance3(center, observation.position);
        const gate = fusionGate(observation.label, observation.uncertaintyMeters, clusterUncertainty(cluster));
        const score = distance / gate;
        if (score <= 1 && score < bestScore) {
          best = cluster;
          bestScore = score;
        }
      }
      if (!best) {
        best = { label: observation.label, observations: [], sourceIds: new Set() };
        clusters.push(best);
      }
      best.observations.push(observation);
      best.sourceIds.add(observation.sourceId);
    }

    const measurements = clusters.map((cluster) => fuseCluster(cluster, now));
    const previous = state.previous.filter((track) => now - track.lastSeenAtMs <= 4_000);
    const usedPrevious = new Set();

    for (const measurement of measurements) {
      let matchIndex = -1;
      let matchDistance = Infinity;
      for (let i = 0; i < previous.length; i += 1) {
        if (usedPrevious.has(i)) continue;
        const prior = previous[i];
        if (prior.label !== measurement.label) continue;
        const distance = distance3(prior.position, measurement.position);
        const gate = Math.max(1.5, fusionGate(measurement.label, prior.uncertaintyMeters, measurement.uncertaintyMeters) * 1.6);
        if (distance <= gate && distance < matchDistance) {
          matchIndex = i;
          matchDistance = distance;
        }
      }
      if (matchIndex >= 0) {
        const prior = previous[matchIndex];
        usedPrevious.add(matchIndex);
        measurement.id = prior.id;
        measurement.firstSeenAtMs = prior.firstSeenAtMs;
      } else {
        measurement.id = `f-${measurement.label}-${String(state.nextId++).padStart(3, '0')}`;
        measurement.firstSeenAtMs = now;
      }
    }

    measurements.sort((a, b) => a.id.localeCompare(b.id));
    state.previous = measurements.map((track) => ({
      id: track.id,
      label: track.label,
      position: [...track.position],
      uncertaintyMeters: track.uncertaintyMeters,
      firstSeenAtMs: track.firstSeenAtMs,
      lastSeenAtMs: now
    }));
    return measurements;
  }

  #record(state, latest, now) {
    if (now - state.lastFrameAtMs < this.historyIntervalMs) return;
    state.lastFrameAtMs = now;
    state.frames.push(compactFrame(latest));
    const cutoff = now - this.historyWindowMs;
    while (state.frames.length && state.frames[0].serverTimeMs < cutoff) state.frames.shift();
    if (state.frames.length > this.maxFrames) state.frames.splice(0, state.frames.length - this.maxFrames);
  }
}

function normalizeObservation(track) {
  if (!track || !finiteVector(track.position, 3)) return null;
  const uncertainty = finitePositive(track.uncertaintyMeters, 0.5);
  return {
    key: String(track.key ?? `${track.sourceId ?? 'unknown'}:${track.id ?? 'track'}`),
    id: String(track.id ?? 'track'),
    sourceId: String(track.sourceId ?? 'unknown'),
    label: String(track.label ?? 'unknown').toLowerCase(),
    confidence: clamp(Number(track.confidence) || 0, 0, 1),
    position: track.position.map(Number),
    velocity: finiteVector(track.velocity, 3) ? track.velocity.map(Number) : [0, 0, 0],
    uncertaintyMeters: clamp(uncertainty, 0.03, 50),
    extentMeters: finiteVector(track.extentMeters, 3) ? track.extentMeters.map(Number) : [0.65, 0.65, 0.65],
    yawRadians: Number.isFinite(Number(track.yawRadians)) ? Number(track.yawRadians) : 0,
    observedAtMs: Number.isFinite(Number(track.observedAtMs)) ? Number(track.observedAtMs) : Date.now(),
    serverReceivedAtMs: Number.isFinite(Number(track.serverReceivedAtMs)) ? Number(track.serverReceivedAtMs) : Date.now(),
    spatialMethod: String(track.spatialMethod ?? 'unknown'),
    terrainY: Number.isFinite(Number(track.terrainY)) ? Number(track.terrainY) : null,
    depthConfidence: Number.isFinite(Number(track.depthConfidence)) ? clamp(Number(track.depthConfidence), 0, 1) : null,
    hitCount: Math.max(0, Math.trunc(Number(track.hitCount) || 0))
  };
}

function fuseCluster(cluster, now) {
  const observations = cluster.observations;
  const position = weightedVector(observations, 'position');
  const velocity = weightedVector(observations, 'velocity');
  const extentMeters = weightedVector(observations, 'extentMeters');
  const sourceConfidence = 1 - observations.reduce((product, observation) => product * (1 - observation.confidence), 1);
  const scatter = weightedScatter(observations, position);
  const baseUncertainty = weightedScalar(observations, (observation) => observation.uncertaintyMeters);
  const uncertaintyMeters = clamp(Math.sqrt(baseUncertainty ** 2 + scatter ** 2), 0.03, 50);
  const gate = fusionGate(cluster.label, uncertaintyMeters, uncertaintyMeters);
  const agreement = clamp(1 - scatter / Math.max(0.2, gate), 0, 1);
  const yawRadians = circularWeightedMean(observations);
  const best = observations.toSorted((a, b) => b.confidence - a.confidence)[0];
  const sourceIds = [...new Set(observations.map((observation) => observation.sourceId))].sort();
  const latestObservedAtMs = Math.max(...observations.map((observation) => observation.observedAtMs));

  return {
    id: '',
    label: cluster.label,
    position,
    velocity,
    extentMeters,
    yawRadians,
    confidence: clamp(sourceConfidence * (0.72 + 0.28 * agreement), 0, 0.999),
    uncertaintyMeters,
    sourceCount: sourceIds.length,
    sourceIds,
    observationCount: observations.length,
    quality: sourceIds.length >= 2 ? (agreement >= 0.55 ? 'MULTI_SENSOR' : 'CONFLICT') : 'SINGLE_SENSOR',
    agreement,
    firstSeenAtMs: now,
    observedAtMs: latestObservedAtMs,
    serverReceivedAtMs: now,
    primarySpatialMethod: best?.spatialMethod ?? 'unknown',
    observations: observations.map((observation) => ({
      key: observation.key,
      sourceId: observation.sourceId,
      trackId: observation.id,
      confidence: observation.confidence,
      uncertaintyMeters: observation.uncertaintyMeters,
      spatialMethod: observation.spatialMethod,
      depthConfidence: observation.depthConfidence,
      terrainY: observation.terrainY,
      hitCount: observation.hitCount,
      position: observation.position
    }))
  };
}

function compactFrame(latest) {
  const clients = (latest.clients ?? []).map((client) => ({
    clientId: client.clientId,
    role: client.role,
    connected: client.connected,
    pose: client.pose ? {
      position: Array.isArray(client.pose.position) ? [...client.pose.position] : null,
      rotation: Array.isArray(client.pose.rotation) ? [...client.pose.rotation] : null,
      tracking: client.pose.tracking,
      atMs: client.pose.atMs,
      serverReceivedAtMs: client.pose.serverReceivedAtMs
    } : null,
    status: client.status ? { ...client.status } : null
  }));
  const fusedTracks = (latest.fusedTracks ?? []).map((track) => ({
    ...track,
    position: [...track.position],
    velocity: [...track.velocity],
    extentMeters: [...track.extentMeters],
    sourceIds: [...track.sourceIds],
    observations: track.observations.map((observation) => ({
      ...observation,
      position: [...observation.position]
    }))
  }));
  return {
    serverTimeMs: latest.serverTimeMs,
    clients,
    fusedTracks,
    rawTrackCount: latest.tracks?.length ?? 0,
    fusion: { ...latest.fusion }
  };
}

function observationWeight(observation) {
  const sigma = Math.max(0.08, observation.uncertaintyMeters);
  return Math.max(0.02, observation.confidence) / (sigma * sigma);
}

function weightedVector(observations, field) {
  let total = 0;
  const out = [0, 0, 0];
  for (const observation of observations) {
    const vector = observation[field];
    if (!finiteVector(vector, 3)) continue;
    const weight = observationWeight(observation);
    total += weight;
    out[0] += vector[0] * weight;
    out[1] += vector[1] * weight;
    out[2] += vector[2] * weight;
  }
  if (total <= 0) return [0, 0, 0];
  return out.map((value) => value / total);
}

function weightedScalar(observations, pick) {
  let weighted = 0;
  let total = 0;
  for (const observation of observations) {
    const weight = observationWeight(observation);
    weighted += pick(observation) * weight;
    total += weight;
  }
  return total > 0 ? weighted / total : 0;
}

function weightedScatter(observations, center) {
  if (observations.length <= 1) return 0;
  let weighted = 0;
  let total = 0;
  for (const observation of observations) {
    const weight = observationWeight(observation);
    const distance = distance3(observation.position, center);
    weighted += distance * distance * weight;
    total += weight;
  }
  return total > 0 ? Math.sqrt(weighted / total) : 0;
}

function clusterUncertainty(cluster) {
  return weightedScalar(cluster.observations, (observation) => observation.uncertaintyMeters) || 0.5;
}

function circularWeightedMean(observations) {
  let x = 0;
  let y = 0;
  for (const observation of observations) {
    const weight = observationWeight(observation);
    x += Math.cos(observation.yawRadians) * weight;
    y += Math.sin(observation.yawRadians) * weight;
  }
  return Math.atan2(y, x);
}

function fusionGate(label, a, b) {
  const base = DEFAULT_GATE_METERS[label] ?? DEFAULT_GATE_METERS.unknown;
  return clamp(Math.max(base, 0.8 + 1.35 * (finitePositive(a, 0.5) + finitePositive(b, 0.5))), base, 6);
}

function finiteVector(value, size) {
  return Array.isArray(value) && value.length === size && value.every((entry) => Number.isFinite(Number(entry)));
}

function finitePositive(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function distance3(a, b) {
  const dx = a[0] - b[0];
  const dy = a[1] - b[1];
  const dz = a[2] - b[2];
  return Math.hypot(dx, dy, dz);
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function downsample(values, limit) {
  if (values.length <= limit) return values;
  if (limit === 1) return [values.at(-1)];
  const out = [];
  const last = values.length - 1;
  for (let i = 0; i < limit; i += 1) {
    const index = Math.round((i * last) / (limit - 1));
    out.push(values[index]);
  }
  return out;
}
