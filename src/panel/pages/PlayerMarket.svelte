{#if data.error}
  <LoadError error={data.error} />
{:else}
  <PlayerMarketPanel summary={data.summary} username={data.username} ctx={data.ctx} error={null} />
{/if}

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { loadContext } from '../utils/context.js';
  import { loadPlayerSummary } from '../components/player/summary.js';

  /**
   * Market tab of the player page (13 section 24); rendered inside PlayerDetailLayout.
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadPlayerSummary({ get: (o) => ApiUtil.get(o), loadContext }, event, {
      title: 'nav-market',
    });
  }
</script>

<script>
  import LoadError from '../components/LoadError.svelte';
  import PlayerMarketPanel from '../components/player/PlayerMarketPanel.svelte';

  let { data } = $props();
</script>
