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

const command = process.argv[2] ?? 'show';
if (command === 'rotate-admin') identity.rotateAdminToken();
else if (command !== 'show') {
  console.error('Usage: node src/identity-cli.mjs [show|rotate-admin]');
  process.exit(2);
}

console.log(JSON.stringify({
  ...identity.publicInfo(),
  adminToken: identity.adminToken(),
  dataDir: config.dataDir
}, null, 2));
