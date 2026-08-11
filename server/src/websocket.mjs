import { WebSocketServer, WebSocket } from 'ws';
import { parseClientMessage, ProtocolError, validateClientIdentity, PROTOCOL_VERSION } from './protocol.mjs';

export class RealtimeHub {
  constructor({
    server,
    logger,
    trackTtlMs = 1800,
    maxPayload = 256 * 1024,
    authorize,
    heartbeatIntervalMs = 15_000,
    heartbeatTimeoutMs = 45_000
  }) {
    this.logger = logger;
    this.trackTtlMs = trackTtlMs;
    this.authorize = authorize;
    this.heartbeatIntervalMs = heartbeatIntervalMs;
    this.heartbeatTimeoutMs = Math.max(heartbeatTimeoutMs, heartbeatIntervalMs * 2);
    this.rooms = new Map();
    this.tracks = new Map();
    this.poses = new Map();
    this.statuses = new Map();
    this.wss = new WebSocketServer({ noServer: true, maxPayload });
    server.on('upgrade', (request, socket, head) => this.#upgrade(request, socket, head));
    this.wss.on('connection', (socket, request, identity) => this.#connected(socket, request, identity));

    // Track TTL is intentionally checked much more frequently than WebSocket liveness. Mobile
    // clients behind Tailscale/cellular links can easily take >1 s to answer a ping; using the
    // object-track TTL as the heartbeat interval caused healthy phones to be terminated with 1006.
    this.expireTimer = setInterval(
      () => this.#expireTracks(),
      Math.max(200, Math.min(1000, Math.floor(trackTtlMs / 3)))
    );
    this.heartbeatTimer = setInterval(() => this.#heartbeat(), this.heartbeatIntervalMs);
    this.expireTimer.unref();
    this.heartbeatTimer.unref();
  }

  metrics() {
    return {
      rooms: this.rooms.size,
      clients: [...this.rooms.values()].reduce((sum, room) => sum + room.size, 0),
      tracks: [...this.tracks.values()].reduce((sum, tracks) => sum + tracks.size, 0),
      poses: this.poses.size
    };
  }

  snapshot(mapId) {
    const room = this.rooms.get(mapId) ?? new Set();
    const clients = [...room].map((socket) => {
      const identity = socket.identity;
      const key = `${identity.mapId}:${identity.clientId}`;
      return {
        ...identity,
        connected: socket.readyState === WebSocket.OPEN,
        connectedAtMs: socket.connectedAtMs,
        lastMessageAtMs: socket.lastMessageAtMs,
        lastPongAtMs: socket.lastPongAtMs,
        pose: this.poses.get(key) ?? null,
        status: this.statuses.get(key) ?? null
      };
    });
    return {
      mapId,
      serverTimeMs: Date.now(),
      clients,
      tracks: [...(this.tracks.get(mapId)?.values() ?? [])]
    };
  }

  /**
   * Revoke all currently-open sessions for one map. New connections are still authorized by the
   * caller's current map key, so rotating the key and then calling this makes revocation immediate.
   */
  disconnectMap(mapId, code = 4003, reason = 'map access revoked') {
    const room = this.rooms.get(mapId);
    if (!room?.size) return 0;
    const sockets = [...room];
    for (const socket of sockets) {
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        try { socket.close(code, reason); }
        catch { socket.terminate(); }
      }
    }
    this.logger.warn('ws_map_sessions_revoked', { mapId, clients: sockets.length, code, reason });
    return sockets.length;
  }

  async close() {
    clearInterval(this.expireTimer);
    clearInterval(this.heartbeatTimer);
    const sockets = [...this.wss.clients];
    for (const socket of sockets) socket.terminate();
    await new Promise((resolve) => {
      if (this.wss._state === 2) return resolve();
      this.wss.close(() => resolve());
    });
    this.rooms.clear();
    this.tracks.clear();
    this.poses.clear();
    this.statuses.clear();
  }

  #upgrade(request, socket, head) {
    try {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname !== '/ws') return socket.destroy();
      const identity = validateClientIdentity({
        mapId: url.searchParams.get('mapId'),
        clientId: url.searchParams.get('clientId'),
        role: url.searchParams.get('role')
      });
      const queryToken = url.searchParams.get('mapKey') ?? url.searchParams.get('token') ?? '';
      if (!this.authorize(request, queryToken, identity.mapId)) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
        return socket.destroy();
      }
      this.wss.handleUpgrade(request, socket, head, (webSocket) => {
        this.wss.emit('connection', webSocket, request, identity);
      });
    } catch (error) {
      this.logger.warn('ws_upgrade_rejected', { error: error.message });
      socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
      socket.destroy();
    }
  }

  #connected(socket, request, identity) {
    const now = Date.now();
    socket.identity = identity;
    socket.connectedAtMs = now;
    socket.lastMessageAtMs = now;
    socket.lastPongAtMs = now;
    const room = this.rooms.get(identity.mapId) ?? new Set();
    room.add(socket);
    this.rooms.set(identity.mapId, room);
    socket.on('pong', () => { socket.lastPongAtMs = Date.now(); });
    socket.on('message', (payload, isBinary) => this.#message(socket, payload, isBinary));
    socket.on('close', (code, reason) => this.#disconnected(socket, code, reason));
    socket.on('error', (error) => this.logger.warn('ws_client_error', { ...identity, error: error.message }));

    this.#send(socket, {
      type: 'welcome',
      protocolVersion: PROTOCOL_VERSION,
      serverTimeMs: now,
      mapId: identity.mapId,
      tracks: [...(this.tracks.get(identity.mapId)?.values() ?? [])]
    });
    this.#broadcast(identity.mapId, { type: 'presence', action: 'joined', ...identity }, socket);
    this.logger.info('ws_connected', { ...identity, remote: request.socket.remoteAddress });
  }

  #message(socket, payload, isBinary) {
    socket.lastMessageAtMs = Date.now();
    if (isBinary) return this.#error(socket, 'BINARY_NOT_SUPPORTED', 'Binary WebSocket messages are not supported');
    let message;
    try {
      message = parseClientMessage(payload.toString('utf8'));
    } catch (error) {
      if (error instanceof ProtocolError) return this.#error(socket, error.code, error.message);
      return this.#error(socket, 'INVALID_MESSAGE', error.message);
    }
    const identity = socket.identity;
    switch (message.type) {
      case 'track_batch': {
        const roomTracks = this.tracks.get(identity.mapId) ?? new Map();
        const receivedAt = Date.now();
        const normalized = message.tracks.map((track) => ({
          ...track,
          key: `${identity.clientId}:${track.id}`,
          sourceId: identity.clientId,
          serverReceivedAtMs: receivedAt
        }));
        for (const track of normalized) roomTracks.set(track.key, track);
        this.tracks.set(identity.mapId, roomTracks);
        this.#broadcast(identity.mapId, {
          type: 'track_batch',
          sourceId: identity.clientId,
          sequence: message.sequence,
          serverReceivedAtMs: receivedAt,
          tracks: normalized
        });
        break;
      }
      case 'client_pose': {
        const key = `${identity.mapId}:${identity.clientId}`;
        const pose = { ...message.pose, clientId: identity.clientId, role: identity.role, serverReceivedAtMs: Date.now() };
        this.poses.set(key, pose);
        this.#broadcast(identity.mapId, { type: 'client_pose', pose });
        break;
      }
      case 'manual_marker':
        this.#broadcast(identity.mapId, { type: 'manual_marker', sourceId: identity.clientId, marker: message.marker });
        break;
      case 'status': {
        const key = `${identity.mapId}:${identity.clientId}`;
        const status = { state: message.state, detail: message.detail, serverReceivedAtMs: Date.now() };
        this.statuses.set(key, status);
        this.#broadcast(identity.mapId, { type: 'status', sourceId: identity.clientId, role: identity.role, ...status });
        break;
      }
      case 'ping':
        this.#send(socket, { type: 'pong', sentAtMs: message.sentAtMs, serverTimeMs: Date.now() });
        break;
    }
  }

  #expireTracks() {
    const now = Date.now();
    for (const [mapId, roomTracks] of this.tracks) {
      const expired = [];
      for (const [key, track] of roomTracks) {
        if (now - track.serverReceivedAtMs > this.trackTtlMs) {
          roomTracks.delete(key);
          expired.push(key);
        }
      }
      if (expired.length) this.#broadcast(mapId, { type: 'tracks_expired', trackKeys: expired, serverTimeMs: now });
      if (roomTracks.size === 0) this.tracks.delete(mapId);
    }
  }

  #heartbeat() {
    const now = Date.now();
    for (const room of this.rooms.values()) {
      for (const socket of room) {
        if (socket.readyState !== WebSocket.OPEN) continue;
        const lastPong = Number(socket.lastPongAtMs || socket.connectedAtMs || now);
        if (now - lastPong > this.heartbeatTimeoutMs) {
          this.logger.warn('ws_heartbeat_timeout', {
            ...socket.identity,
            silenceMs: now - lastPong
          });
          socket.terminate();
          continue;
        }
        try { socket.ping(); }
        catch (error) {
          this.logger.warn('ws_ping_failed', { ...socket.identity, error: error.message });
          socket.terminate();
        }
      }
    }
  }

  #disconnected(socket, code, reason) {
    const identity = socket.identity;
    if (!identity) return;
    const room = this.rooms.get(identity.mapId);
    room?.delete(socket);
    if (room?.size === 0) this.rooms.delete(identity.mapId);
    this.poses.delete(`${identity.mapId}:${identity.clientId}`);
    this.statuses.delete(`${identity.mapId}:${identity.clientId}`);
    this.#broadcast(identity.mapId, { type: 'presence', action: 'left', ...identity });
    this.logger.info('ws_disconnected', { ...identity, code, reason: reason.toString() });
  }

  #send(socket, value) {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
  }

  #broadcast(mapId, value, exclude = null) {
    const text = JSON.stringify(value);
    for (const socket of this.rooms.get(mapId) ?? []) {
      if (socket !== exclude && socket.readyState === WebSocket.OPEN) socket.send(text);
    }
  }

  #error(socket, code, message) { this.#send(socket, { type: 'error', code, message }); }
}
