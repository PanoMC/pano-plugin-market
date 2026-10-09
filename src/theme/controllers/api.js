// `market/api`: the plugin's request wrapper over `host.request`. No state. Never throws; callers branch on `ok` / `code`.
import { defineController } from '@panomc/plugin-kit/controller';
import { createApi } from '../lib/api.js';

export default defineController({
  name: 'api',
  version: 1,
  actions: ({ host }) => createApi(host),
});
