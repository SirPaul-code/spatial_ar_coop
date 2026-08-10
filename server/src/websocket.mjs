import { WebSocketServer, WebSocket } from 'ws';
import { parseClientMessage, ProtocolError, validateClientIdentity, PROTOCOL_VERSION } from './protocol.mjs';

export class RealtimeHub {
  constructor({ server, logger, trackTtlMs = 1800, maxPayload = 256 * 1024, authorize }) {
    this.logger = logger;
    this.trackTtlMs = trackTtlMs;
    this.authorize = authorize;
    this.rooms = new Map();
    this.tracks = new Map();
    this.poses = new Map();
    this.wss = new WebSocketServer({ noServer: true, maxPayload });
    server.on('upgrade', (request, socket, head) => this.#upgrade(request, socket, head));
    this.wss.on('connection', (socket, request, identity) => this.#connected(socket, request, identity));
    this.timer = setInterval(() => this.#expire(), Math.max(200, Math.min(1000, Math.floor(trackTtlMs / 3))));
    this.timer.unref();
  }

  metrics() {
    return {
      rooms: this.rooms.size,
      clients: [...this.rooms.values()].reduce((sum, room) => sum + room.size, 0),
      tracks: [...this.tracks.values()].reduce((sum, tracks) => sum + tracks.size, 0),
      poses: this.poses.size
    };
  }

  close() {
    clearInterval(this.timer);
    for (const room of this.rooms.values()) for (const client of room) client.close(1001, 'server shutdown');
    this.wss.close();
  }

  #upgrade(request, socket, head) {
    try {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname !== '/ws') return socket.destroy();
      if (!this.authorize(request, url.searchParams.get('token') ?? '')) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
        return socket.destroy();
      }
      const identity = validateClientIdentity({
        mapId: url.searchParams.get('mapId'),
        clientId: url.searchParams.get('clientId'),
        role: url.searchParams.get('role')
      });
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
    socket.identity = identity;
    socket.isAlive = true;
    const room = this.rooms.get(identity.mapId) ?? new Set();
    room.add(socket);
    this.rooms.set(identity.mapId, room);
    socket.on('pong', () => { socket.isAlive = true; });
    socket.on('message', (payload, isBinary) => this.#message(socket, payload, isBinary));
    socket.on('close', (code, reason) => this.#disconnected(socket, code, reason));
    socket.on('error', (error) => this.logger.warn('ws_client_error', { ...identity, error: error.message }));

    this.#send(socket, {
      type: 'welcome',
      protocolVersion: PROTOCOL_VERSION,
      serverTimeMs: Date.now(),
      mapId: identity.mapId,
      tracks: [...(this.tracks.get(identity.mapId)?.values() ?? [])]
    });
    this.#broadcast(identity.mapId, { type: 'presence', action: 'joined', ...identity }, socket);
    this.logger.info('ws_connected', { ...identity, remote: request.socket.remoteAddress });
  }

  #message(socket, payload, isBinary) {
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
      case 'status':
        this.#broadcast(identity.mapId, { type: 'status', sourceId: identity.clientId, role: identity.role, state: message.state, detail: message.detail });
        break;
      case 'ping':
        this.#send(socket, { type: 'pong', sentAtMs: message.sentAtMs, serverTimeMs: Date.now() });
        break;
    }
  }

  #expire() {
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
    for (const room of this.rooms.values()) {
      for (const socket of room) {
        if (!socket.isAlive) socket.terminate();
        else { socket.isAlive = false; socket.ping(); }
      }
    }
  }

  #disconnected(socket, code, reason) {
    const identity = socket.identity;
    const room = this.rooms.get(identity.mapId);
    room?.delete(socket);
    if (room?.size === 0) this.rooms.delete(identity.mapId);
    this.poses.delete(`${identity.mapId}:${identity.clientId}`);
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
