<!-- pano-design-guidelines:start -->
## Panel design guidelines

Before designing or changing any panel UI (pages, components, modals, alerts, forms…), read
`design/README.md` and then **only** the topic file it points to for what you are building. These
rules are mandatory. Do not load every file in `design/`.
<!-- pano-design-guidelines:end -->

<!-- pano-agent-guide:start -->
## Agent guide

You are in a **Pano plugin** (Kotlin backend, Svelte site views, panel UI).

Read `agent-guide/README.md` first, then **only** the topic file it points to for your task. Decide from the
request and the code; ask only where the guide says a wrong guess is costly. `agent-guide/` is a synced copy: edit it
in `theme-core/agent-guide/` (repo `PanoMC/sdk`) and run `bun scripts/sync-agent-guide.js` there.

The three rules you will break first:

1. Every site view is a named view `<ns>:<ViewName>` with a contract version: one `.svelte` file with `export const view = {...}`, never a registration with `component` (`plugin-views.md`).
2. Declare relative API paths only; Pano serves them at `/api/plugins/<pluginId>/...` and `/api/plugins/<pluginId>/panel/...`. Lists answer `{ items }` (+ `{ page }` when paged), errors `{ error: { code } }` (`plugin-api.md`).
3. Look goes on semantic classes `<ns>-<view>__<part>` and `--pano-*` tokens, logic into controllers; no `<style>` that leaks, no `getContext` in a view (`plugin-views.md`, `plugin-controllers.md`).
<!-- pano-agent-guide:end -->
