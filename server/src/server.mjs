import { createSpatialServer } from './app.mjs';

const app = createSpatialServer();
await app.start();

let stopping = false;
async function stop(signal) {
  if (stopping) return;
  stopping = true;
  app.logger.info('shutdown_requested', { signal });
  try { await app.stop(); process.exitCode = 0; }
  catch (error) { app.logger.error('shutdown_failed', { error }); process.exitCode = 1; }
}
process.on('SIGINT', () => stop('SIGINT'));
process.on('SIGTERM', () => stop('SIGTERM'));
process.on('uncaughtException', (error) => { app.logger.error('uncaught_exception', { error }); stop('uncaughtException'); });
process.on('unhandledRejection', (error) => { app.logger.error('unhandled_rejection', { error }); stop('unhandledRejection'); });
