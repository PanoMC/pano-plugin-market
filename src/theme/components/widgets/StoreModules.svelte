{#if modules.length}
  <div class="vstack gap-3">
    {#if modules.includes('goals')}<GoalWidget data={widgets} />{/if}
    {#if modules.includes('topSupporters')}
      <TopSupportersWidget data={widgets} currency={settings?.currency ?? ''} />
    {/if}
    {#if modules.includes('recentBuyers')}<RecentBuyersWidget data={widgets} />{/if}
  </div>
{/if}

<script>
  import GoalWidget from './GoalWidget.svelte';
  import TopSupportersWidget from './TopSupportersWidget.svelte';
  import RecentBuyersWidget from './RecentBuyersWidget.svelte';
  import { storeModules } from './widgetsModel.js';

  /** settings: store settings (`modules.*` flags, `currency`); widgets: the payload of /api/market/widgets. */
  let { settings = {}, widgets = {} } = $props();

  const modules = $derived(storeModules(settings, widgets));
</script>
