<style>
  /* The section menu takes a fixed, narrow column from md up; the page gets the rest. min-width: 0
     keeps a wide one-line table scrolling inside its card instead of widening the page. */
  @media (min-width: 768px) {
    .market-section-aside {
      width: 11rem;
    }
  }
  .market-section-main {
    min-width: 0;
  }
</style>

{#snippet defaultLeft()}
  <PageNav>
    {#each areas as item (item.key)}
      <PageNavItem href={item.href} active={area ? item.key === area : undefined}>
        {$_(item.label)}
      </PageNavItem>
    {/each}
  </PageNav>
{/snippet}

<div class="container vstack gap-3 pb-5" bind:this={root}>
  <PageActions>
    <div slot="left">
      {@render (left || defaultLeft)()}
    </div>

    <div slot="right">
      {#if right}
        {@render right()}
      {/if}
    </div>
  </PageActions>

  {#if heading}
    <div class="d-flex align-items-center gap-2 flex-wrap">
      {@render heading()}
    </div>
  {/if}

  {#if hasSections}
    <div class="row g-3">
      <aside class="col-12 col-md-auto market-section-aside">
        <SectionNav {sections} {active} />
      </aside>
      <div class="col-12 col-md market-section-main">
        <div class="vstack gap-3">
          {@render children?.()}
        </div>
      </div>
    </div>
  {:else}
    {@render children?.()}
  {/if}
</div>

<script>
  import { PageActions, PageNav, PageNavItem } from '@panomc/sdk/components/panel';
  import { page } from '@panomc/sdk/svelte';
  import { onMount } from 'svelte';
  import { _ } from '../../i18n';
  import { visibleAreas } from '../navigation.js';
  import { can } from '../utils/permissions.js';
  import SectionNav from '../components/SectionNav.svelte';

  // area: key of the level-1 item to highlight (navigation.js); sections: level-2 items of that
  // area (navigation.js sectionsFor); active: forces the highlighted section key.
  // heading: what a detail page is about (id, status, player), shown under the area pills; detail
  // pages keep the pills with their area lit instead of a back link, as the posts editor does.
  let {
    children,
    left,
    right,
    heading,
    area = undefined,
    sections = undefined,
    active = null,
  } = $props();

  const user = $derived($page.data?.user);
  const areas = $derived(visibleAreas(user));
  const hasSections = $derived(
    Array.isArray(sections) && sections.some((s) => can(user, ...s.nodes)),
  );

  let root = $state(null);

  // The panel host bundled with the platform serves an older SearchInput that predates `autofocus`;
  // focusing the card's search field from here keeps every market list ready to type on either host.
  onMount(() => {
    root?.querySelector('.card-header input.form-control')?.focus();
  });
</script>
