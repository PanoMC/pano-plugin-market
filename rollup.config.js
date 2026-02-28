import svelte from 'rollup-plugin-svelte';
import resolve from '@rollup/plugin-node-resolve';
import del from 'rollup-plugin-delete';
import terser from '@rollup/plugin-terser';
import fs from 'node:fs';
import path from 'node:path';

const production = !process.env.DEV;

const bundleSdk = process.env.BUNDLE_SDK === 'true';

function manifestPlugin() {
  return {
    name: 'manifest',
    writeBundle(options, bundle) {
      const dir = options.dir;
      const manifestPath = path.join(dir, 'manifest.json');
      const files = Object.keys(bundle);
      fs.writeFileSync(manifestPath, JSON.stringify(files, null, 2));
    },
  };
}

const baseConfig = {
  input: 'src/main.js',
  output: {
    format: 'es',
    chunkFileNames: '[name]-[hash].js', // Chunk file naming
  },
  plugins: [
    del({
      targets: ['src/main/resources/plugin-ui/*'], // Always clean the resources folder
      runOnce: true, // Run only once
    }),
    production && terser(),
    manifestPlugin(),
  ],
  preserveEntrySignatures: 'strict'
};

export default [
  // Server configuration
  {
    ...baseConfig,
    output: {
      ...baseConfig.output,
      dir: 'src/main/resources/plugin-ui/server', // Server directory
      entryFileNames: 'server.mjs', // Server entry file
    },
    plugins: [
      ...baseConfig.plugins,
      resolve({
        dedupe: ['svelte'],
      }),
      svelte({
        compilerOptions: {
          generate: 'server',
          css: 'external',
        },
        emitCss: false,
      }),
    ],
  },
  // Client configuration
  {
    ...baseConfig,
    output: {
      ...baseConfig.output,
      dir: 'src/main/resources/plugin-ui/client', // Client directory
      entryFileNames: 'client.mjs', // Client entry file
    },
    // We should externalize svelte here too so that SSR uses the host's svelte instance
    // external: (id) => id.startsWith('@panomc/sdk') || id.startsWith('svelte'),
    // In production SSR builds, bundle SDK into the plugin so it's self-contained
    // In dev mode, keep SDK external for faster rebuilds
    external: (id) => {
      if (bundleSdk) return false;
      // Always externalize svelte as SSR needs to use the host's svelte instance
      if (id.startsWith('svelte')) return true;
      // In dev mode, also externalize SDK for faster rebuilds
      // In production (non-dev), bundle SDK to avoid module resolution issues
      if (id.startsWith('@panomc/sdk')) return !production;
      return false;
    },
    plugins: [
      ...baseConfig.plugins,
      resolve({
        browser: true,
        dedupe: ['svelte', '@panomc/sdk'],
      }),
      svelte({
        compilerOptions: {
          generate: 'client',
          css: 'external',
          dev: !production,
        },
        emitCss: false,
      }),
    ],
  },
];
