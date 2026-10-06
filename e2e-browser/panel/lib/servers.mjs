// A Minecraft server the platform has accepted but that never connects: what a COMMAND action needs to be saved against (the same REST call the
// Minecraft plugin makes first, then the panel accepts the request). Mirrors DeliveryE2E.grantedServer; the row is removed again by `removeServer`.
import crypto from 'node:crypto';
import { Api, must } from '../../lib/api.mjs';

export async function grantedServer(env, admin) {
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  // the key is a number in the JSON; the connect call wants it as text
  const platformCode = String(
    must(await admin.get('/api/panel/basicData'), 'basic data').json.platformServerMatchKey,
  );
  const name = `e2e-srv-${Date.now().toString(36).slice(-6)}`;
  const mc = new Api(env.url, 'mc');

  must(
    await mc.post('/api/server/connect', {
      platformCode,
      serverName: name,
      host: '127.0.0.1',
      port: 25565,
      playerCount: 0,
      maxPlayerCount: 20,
      serverType: 'PAPER',
      serverVersion: '1.21',
      startTime: Date.now(),
      publicKey: keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    }),
    'server connect request',
  );

  const pending = must(await admin.get('/api/panel/servers/pending'), 'pending servers').json;
  const row = (pending.servers ?? pending.serverConnectRequests ?? []).find((s) => s.name === name);
  const id = row?.id ?? (await findServerId(admin, name));
  if (!id) throw new Error(`the connect request of ${name} created no server row`);

  must(await admin.post(`/api/panel/servers/${id}/accept`, {}), 'accept the server');

  return { id, name };
}

async function findServerId(admin, name) {
  const res = await admin.get('/api/panel/market/servers');
  return (res.json?.servers ?? []).find((s) => s.name === name)?.id ?? null;
}

export async function removeServer(env, admin, server) {
  must(
    await admin.post(`/api/panel/servers/${server.id}/delete`, {
      currentPassword: env.adminPassword(),
    }),
    'remove the test server',
  );
}
