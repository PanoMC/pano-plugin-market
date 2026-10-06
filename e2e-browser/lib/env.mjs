// What `scripts/e2e-instance.sh start --ui external:<theme>,<panel>` exported (17 section 8.1, 10). Nothing else is read.
import fs from 'node:fs';
import path from 'node:path';

function need(name) {
  const value = process.env[name];

  if (!value) {
    throw new Error(
      `${name} is not set: start the isolated instance with e2e-instance.sh start --ui external:<themePort>,<panelPort> and eval its output`,
    );
  }

  return value.replace(/\/$/, '');
}

export function loadEnv() {
  const dir = need('MARKET_E2E_DIR');
  const adminEnv = {};
  const file = path.join(dir, 'admin.env');

  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      const at = line.indexOf('=');
      if (at > 0) adminEnv[line.slice(0, at)] = line.slice(at + 1);
    }
  }

  // 17 section 15 guard in spirit: only ever an isolated instance, never the dev backend
  const url = need('MARKET_E2E_URL');

  if (/:(8088|3000|3001)(\/|$)/.test(url)) throw new Error(`refusing to run against ${url}`);

  return {
    url,
    dir,
    themeUrl: need('MARKET_E2E_THEME_URL'),
    panelUrl: need('MARKET_E2E_PANEL_URL'),
    gatewayPort: Number(process.env.MARKET_E2E_GATEWAY_PORT || 18189),
    adminUser: adminEnv.SMOKE_ADMIN_USER || 'smokeadmin',
    adminPassword: () => {
      if (!adminEnv.SMOKE_ADMIN_PASSWORD)
        throw new Error('no admin password under MARKET_E2E_DIR/admin.env');
      return adminEnv.SMOKE_ADMIN_PASSWORD;
    },
  };
}
