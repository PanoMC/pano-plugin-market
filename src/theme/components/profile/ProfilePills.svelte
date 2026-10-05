<nav class="nav nav-pills flex-wrap mb-3" aria-label={$_('theme.profile.block.title')}>
  {#each links as link (link.id)}
    <a
      class={['nav-link', { active: link.id === current }]}
      href="{base}{link.href}"
      aria-current={link.id === current ? 'page' : undefined}>
      <i class="{link.icon} fa-fw me-1" aria-hidden="true"></i>{$_(link.key)}
      {#if link.badge}
        <span class="badge text-bg-secondary ms-1">{link.badge}</span>
      {/if}
    </a>
  {/each}
</nav>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import { visibleLinks } from '../../lib/profileModel.js';
  import { formatCredits } from '../../utils/format.js';

  /**
   * The same links as the profile navigation, for a host without page sidebars (14 §12.1).
   * summary: the `me/summary` read (null shows only the purchases link); current: id of the open page.
   */
  let { summary = null, current = '' } = $props();

  const links = $derived(visibleLinks(summary, (n) => formatCredits(n, summary?.creditName ?? '')));
</script>
