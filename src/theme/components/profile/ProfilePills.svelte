<nav
  class="market-profile-pills nav nav-pills flex-wrap mb-3"
  aria-label={$_('theme.profile.block.title')}>
  {#each links as link (link.id)}
    <a
      class={['market-profile-pills__link', 'nav-link', { active: link.id === current }]}
      href="{base}{link.href}"
      aria-current={link.id === current ? 'page' : undefined}>
      <i class="{iconClass(link.icon)} fa-fw me-1" aria-hidden="true"></i>{$_(link.key)}
      {#if link.badge}
        <span class="market-profile-pills__badge badge text-bg-secondary ms-1">{link.badge}</span>
      {/if}
    </a>
  {/each}
</nav>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { base } from '@panomc/sdk/svelte';
  import { visibleLinks } from '../../lib/profileModel.js';
  import { iconClass } from '../../lib/classes.js';

  const market = plugin('market');
  const { _ } = market;
  const { formatCredits } = market.require('format').actions;

  /**
   * The same links as the profile navigation, for a host without page sidebars (14 §12.1).
   * summary: the `me/summary` read (null shows only the purchases link); current: id of the open page.
   */
  let { summary = null, current = '' } = $props();

  const links = $derived(visibleLinks(summary, (n) => formatCredits(n, summary?.creditName ?? '')));
</script>
