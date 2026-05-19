<script>
  import { PageActions, PageNav, PageNavItem } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';

  let { children, left, right } = $props();
</script>

{#snippet defaultLeft()}
  <PageNav>
    <PageNavItem href="/market">{$_('nav-stats')}</PageNavItem>
    <PageNavItem href="/market/orders" startsWith={true}>
      {$_('nav-orders')}
    </PageNavItem>
    <PageNavItem href="/market/products" startsWith={true}>
      {$_('nav-products')}
    </PageNavItem>
    <PageNavItem href="/market/categories" startsWith={true}>
      {$_('nav-categories')}
    </PageNavItem>
    <PageNavItem href="/market/comparisons" startsWith={true}>
      {$_('nav-comparisons')}
    </PageNavItem>
    <PageNavItem href="/market/gifts" startsWith={true}>
      {$_('nav-gifts')}
    </PageNavItem>
    <PageNavItem href="/market/discounts" startsWith={true}>
      {$_('nav-discounts')}
    </PageNavItem>
    <PageNavItem href="/market/settings" startsWith={true}>
      {$_('nav-settings')}
    </PageNavItem>
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

  {@render children?.()}
</div>
