{#snippet defaultLeft()}
  <PageNav>
    {#each areas as item (item.key)}
      <PageNavItem href={item.href} active={area ? item.key === area : undefined}>
        {$_(item.label)}
      </PageNavItem>
    {/each}
  </PageNav>
{/snippet}

<div class="container vstack gap-3">
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

  {#if hasSections}
    <div class="row g-3">
      <aside class="col-12 col-md-3">
        <SectionNav {sections} {active} />
      </aside>
      <div class="col-12 col-md-9">
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
  import { _ } from '../../i18n';
  import { visibleAreas } from '../navigation.js';
  import { can } from '../utils/permissions.js';
  import SectionNav from '../components/SectionNav.svelte';

  // area: key of the level-1 item to highlight (navigation.js); sections: level-2 items of that
  // area (navigation.js sectionsFor); active: forces the highlighted section key.
  let { children, left, right, area = undefined, sections = undefined, active = null } = $props();

  const user = $derived($page.data?.user);
  const areas = $derived(visibleAreas(user));
  const hasSections = $derived(
    Array.isArray(sections) && sections.some((s) => can(user, ...s.nodes)),
  );
</script>
