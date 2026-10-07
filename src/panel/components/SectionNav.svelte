{#if visible.length}
  <!-- z-1: the sticky default (1020) would sit above open dropdown menus (1000) -->
  <div class="nav flex-column nav-pills sticky-md-top z-1">
    {#each visible as item (item.key)}
      <a
        class="nav-link"
        class:active={current === item.key}
        aria-current={current === item.key ? 'page' : undefined}
        href="{base}{item.href}">
        {$_(item.label)}
      </a>
    {/each}
  </div>
{/if}

<script>
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import { activeSection } from '../navigation.js';
  import { can } from '../utils/permissions.js';

  // sections: items of navigation.js ({ area, key, href, label, nodes }); active: forces the key
  // (needed where one section spans pages the path match cannot tell apart, e.g. a form page).
  let { sections = [], active = null } = $props();

  const user = $derived($page.data?.user);
  const visible = $derived(sections.filter((s) => can(user, ...s.nodes)));
  const pathname = $derived(
    base && $page.url.pathname.startsWith(base)
      ? $page.url.pathname.slice(base.length)
      : $page.url.pathname,
  );
  const current = $derived(active ?? activeSection(visible, pathname, $page.url.searchParams));
</script>
