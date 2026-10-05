{#if items.length}
  <section>
    <h2 class="h5">{$_('theme.product.bundle-contents')}</h2>
    <ul class="list-group">
      {#each items as item (`${item.productId}-${item.variantId ?? 0}`)}
        <li class="list-group-item d-flex align-items-center gap-3">
          {#if item.imageFileName}
            <img
              src="{base}/api/market/products/image/{encodeURIComponent(
                item.imageFileName,
              )}?thumbnail=true"
              alt=""
              width="40"
              height="40"
              class="object-fit-cover rounded"
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
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';

  /** Children of a BUNDLE product (ProductDetail.bundleItems[]). */
  let { items = [] } = $props();
</script>
