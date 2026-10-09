// What `scripts/e2e-instance.sh start` exported (17 section 8.1, 10); each value falls back to the slot variables of tools/of-slot.sh (PANO_OF_SLOT_URL, _DIR, _GATEWAY_PORT, _THEME_PORT, _PANEL_PORT). The theme / panel URLs only exist with `--ui external` and are optional. Nothing else is read.
import fs from 'node:fs';
import path from 'node:path';

function need(name, slotName) {
  const value = process.env[name] || (slotName && process.env[slotName]);

  if (!value) {
    throw new Error(
      `${name} is not set: run inside a slot (/home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh <command>) and start the isolated instance with e2e-instance.sh start`,
    );
  }

  return value.replace(/\/$/, '');
}

export function loadEnv() {
  const dir = need('MARKET_E2E_DIR', 'PANO_OF_SLOT_DIR');
  const adminEnv = {};
  const file = path.join(dir, 'admin.env');

  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      const at = line.indexOf('=');
      if (at > 0) adminEnv[line.slice(0, at)] = line.slice(at + 1);
    }
  }

  // 17 section 15 guard in spirit: only ever an isolated instance, never the dev backend
  const url = need('MARKET_E2E_URL', 'PANO_OF_SLOT_URL');

  if (/:(8088|3000|3001)(\/|$)/.test(url)) throw new Error(`refusing to run against ${url}`);

  return {
    url,
    dir,
    // only exported by `--ui external`; the runner goes through `url` (bundled UIs) either way
    themeUrl: process.env.MARKET_E2E_THEME_URL?.replace(/\/$/, '') || null,
    panelUrl: process.env.MARKET_E2E_PANEL_URL?.replace(/\/$/, '') || null,
    gatewayPort: Number(need('MARKET_E2E_GATEWAY_PORT', 'PANO_OF_SLOT_GATEWAY_PORT')),
    adminUser: adminEnv.SMOKE_ADMIN_USER || 'smokeadmin',
    adminPassword: () => {
      if (!adminEnv.SMOKE_ADMIN_PASSWORD)
        throw new Error('no admin password under MARKET_E2E_DIR/admin.env');
      return adminEnv.SMOKE_ADMIN_PASSWORD;
    },
  };
}
