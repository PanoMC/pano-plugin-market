<div class="market-market-profile-block card">
  <div class="market-market-profile-block__header card-header fw-semibold">
    <i class="fa-solid fa-store me-2" aria-hidden="true"></i>{$_('theme.profile.block.title')}
  </div>
  <div class="market-market-profile-block__list list-group list-group-flush">
    {#each links as link (link.id)}
      <a
        class="market-market-profile-block__item list-group-item list-group-item-action d-flex align-items-center"
        href="{base}{link.href}">
        <i class="{iconClass(link.icon)} fa-fw me-2" aria-hidden="true"></i>
        <span>{$_(link.key)}</span>
        {#if link.badge}
          <span class="market-market-profile-block__badge badge text-bg-primary ms-auto"
            >{link.badge}</span>
        {/if}
      </a>
    {/each}
  </div>
  {#if summary?.creditsEnabled}
    <div class="market-market-profile-block__footer card-footer small text-body-secondary">
      {$_('theme.profile.block.balance')}
      <strong class="text-body">{balanceText}</strong>
    </div>
  {/if}
</div>

<script module>
  // injected from register.js (only on a theme without profile-nav), so no inject key here
  export const view = {};

  import { plugin } from '@panomc/sdk/controllers';
  import { readSummary } from '../../lib/profileModel.js';

  /** Slot load (the engine merges the result into `data`): the summary decides which links show. */
  export async function load(event) {
    const res = await plugin('market').require('api', { event }).actions.call('GET', '/me/summary');

    return { summary: readSummary(res) };
  }
</script>

<script>
  import { onMount } from 'svelte';
  import { base } from '@panomc/sdk/svelte';
  import { visibleLinks } from '../../lib/profileModel.js';
  import { iconClass } from '../../lib/classes.js';

  const market = plugin('market');
  const { _ } = market;
  const { call } = market.require('api').actions;
  const { formatCredits } = market.require('format').actions;

  /** Slot component of `profile-content` for themes without the profile-nav slot (14 §12.1). */
  let { data = {} } = $props();

  // Some hosts hand a profile-content slot only the page data and drop what the slot's load() returned (theme-core's ProfileView renders
  // `data={data}` without the item's merged props), so the summary is asked for once more in the browser when `data` has none.
  let fetched = $state(null);

  onMount(async () => {
    if (data?.summary) return;

    fetched = readSummary(await call('GET', '/me/summary'));
  });

  const summary = $derived(data?.summary ?? fetched);
  const links = $derived(visibleLinks(summary, (n) => formatCredits(n, summary?.creditName ?? '')));
  const balanceText = $derived(
    formatCredits(summary?.creditBalance ?? 0, summary?.creditName ?? ''),
  );
</script>
