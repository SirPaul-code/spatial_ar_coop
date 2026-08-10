import { loadConfig } from './config.mjs';
import { ServerIdentityStore } from './identity.mjs';

const config = loadConfig();
const quietLogger = { info() {}, warn() {}, error() {}, debug() {} };
const identity = new ServerIdentityStore({
  dataDir: config.dataDir,
  logger: quietLogger,
  configuredAdminToken: config.adminToken,
  configuredServerId: config.serverId,
  serverName: config.serverName
});

console.log(JSON.stringify({
  ...identity.publicInfo(),
  adminToken: identity.adminToken(),
  dataDir: config.dataDir
}, null, 2));
