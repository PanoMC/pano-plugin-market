{#each components as Hooked, index (index)}
  <div>
    <Hooked {...props} />
  </div>
{/each}

<script>
  import { get } from 'svelte/store';
  import { page } from '@panomc/sdk/svelte';
  import { getPano } from '../utils/runtime.js';
  import { canNode } from '../utils/permissions.js';

  // Renders UI that other plugins contribute to a hook of this plugin (13 §3.6). Client side only:
  // effects do not run on the server, so SSR renders nothing.
  let { name, props = {} } = $props();

  let components = $state.raw([]);

  async function resolve(entry) {
    try {
      const candidate = entry.component ?? entry;
      // an importer is a function without a prototype; anything else is the module / component itself
      const module =
        typeof candidate === 'function' && !candidate.prototype ? await candidate() : candidate;
      return module?.default ?? module;
    } catch (error) {
      console.warn('[market] hook', name, error);
      return null;
    }
  }

  $effect(() => {
    const store = getPano()?.ui?.hook?.get?.(name);
    if (!store || typeof store.subscribe !== 'function') {
      components = [];
      return;
    }
    let active = true;
    let run = 0;
    const unsubscribe = store.subscribe(async (list) => {
      const current = ++run;
      const user = get(page)?.data?.user;
      const allowed = (list || []).filter((entry) => canNode(user, entry.permission));
      const resolved = (await Promise.all(allowed.map(resolve))).filter(Boolean);
      if (active && current === run) components = resolved;
    });
    return () => {
      active = false;
      unsubscribe();
    };
  });
</script>
