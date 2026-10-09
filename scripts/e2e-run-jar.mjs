#!/usr/bin/env bun
// Prints the path of the run jar for a test slot: the built Pano jar with the built panel-ui and vanilla-theme (current engine) in
// place of the UI zips embedded in it. Same procedure as boot-gate.sh and pano-showcase (scripts/lib/runjar.mjs); cached by input hash.
// Why: a Pano booted from the plain build/libs jar serves its embedded, stale UI zips; its install hangs after "setup-ui stopped"
// and the guest pages are not the current engine (rule 16 of the open front-end run).
// Environment: PANO_OF_PANEL_BUILD, PANO_OF_VANILLA_BUILD override the build folders.
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const umbrella = path.resolve(here, '../../../..');
const { buildRunJar } = await import(path.join(umbrella, 'pano-showcase/scripts/lib/runjar.mjs'));

const jar = await buildRunJar({
  source: path.join(umbrella, 'pano-web-platform/build/libs/Pano-local-build.jar'),
  panelBuild: process.env.PANO_OF_PANEL_BUILD || path.join(umbrella, 'panel-ui/build'),
  vanillaBuild:
    process.env.PANO_OF_VANILLA_BUILD || path.join(umbrella, 'themes/vanilla-theme/build'),
  outDir: path.join(umbrella, '.open-frontend-run/work'),
  log: (line) => console.error(line),
});

console.log(jar);
