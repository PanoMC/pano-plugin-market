{#if items.length}
  <section class="market-bundle-list">
    <h2 class="market-bundle-list__title h5">{$_('theme.product.bundle-contents')}</h2>
    <ul class="market-bundle-list__list list-group">
      {#each items as item (`${item.productId}-${item.variantId ?? 0}`)}
        <li class="market-bundle-list__item list-group-item d-flex align-items-center gap-3">
          {#if item.imageFileName}
            <img
              src="{base}/api/plugins/pano-plugin-market/products/image/{encodeURIComponent(
                item.imageFileName,
              )}?thumbnail=true"
              alt=""
              width="40"
              height="40"
              class="market-bundle-list__image object-fit-cover rounded"
              loading="lazy" />
          {:else}
            <i class="fa-solid fa-box text-body-secondary fa-2x" aria-hidden="true"></i>
          {/if}
          <span class="flex-grow-1">{item.name}</span>
          <span class="text-body-secondary">&times; {item.quantity}</span>
        </li>
      {/each}
    </ul>
  </section>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { base } from '@panomc/sdk/svelte';

  const market = plugin('market');
  const _ = market._;

  /** Children of a BUNDLE product (ProductDetail.bundleItems[]). */
  let { items = [] } = $props();
</script>
