<div class={['card', 'h-100', { 'opacity-75': !product.inStock }]}>
  <div class="position-relative">
    <a {href} class="d-block">
      <div class="ratio ratio-1x1 bg-body-tertiary rounded-top overflow-hidden">
        {#if product.imageFileName}
          <img
            src="{base}/api/market/products/image/{encodeURIComponent(
              product.imageFileName,
            )}?thumbnail=true"
            alt={product.name}
            class="object-fit-cover"
            loading="lazy" />
        {:else}
          <div class="d-flex align-items-center justify-content-center">
            <i
              class={['fa-solid', product.icon || 'fa-box', 'fa-3x', 'text-body-secondary']}
              aria-hidden="true"></i>
          </div>
        {/if}
      </div>
    </a>

    <div class="position-absolute top-0 start-0 m-2">
      <SaleBadge {product} {settings} />
    </div>
    {#if product.featured}
      <span class="badge text-bg-warning position-absolute top-0 end-0 m-2">
        <i class="fa-solid fa-star me-1" aria-hidden="true"></i>{$_('theme.store.featured-badge')}
      </span>
    {/if}
    {#if !product.inStock}
      <span class="badge text-bg-secondary position-absolute bottom-0 start-0 m-2">
        {$_('theme.store.sold-out')}
      </span>
    {/if}
  </div>

  <div class="card-body d-flex flex-column gap-1">
    <h3 class="h6 card-title mb-0 text-truncate">
      <a {href} class="link-body-emphasis text-decoration-none" title={product.name}
        >{product.name}</a>
    </h3>
    {#if product.shortDescription}
      <p class="small text-body-secondary text-truncate mb-1">{product.shortDescription}</p>
    {/if}
    <PriceTag {product} {settings} />
    <StockNote {product} />
    <SaleCountdown {product} {settings} />
    {#if product.owned === true}
      <div class="small text-success">
        <i class="fa-solid fa-check me-1" aria-hidden="true"></i>{$_('theme.store.owned')}
      </div>
    {/if}

    <div class="mt-auto pt-2">
      {#if !product.inStock}
        <button type="button" class="btn btn-secondary btn-sm w-100" disabled>
          {$_('theme.store.sold-out')}
        </button>
      {:else if options}
        <a {href} class="btn btn-outline-primary btn-sm w-100">
          {$_('theme.store.choose-options')}
        </a>
      {:else}
        <button type="button" class="btn btn-primary btn-sm w-100" onclick={add}>
          <i class="fa-solid fa-cart-plus me-1" aria-hidden="true"></i>{$_(
            'theme.store.add-to-cart',
          )}
        </button>
      {/if}
    </div>
  </div>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import { cart } from '../../stores/cart.js';
  import PriceTag from './PriceTag.svelte';
  import SaleBadge from './SaleBadge.svelte';
  import SaleCountdown from './SaleCountdown.svelte';
  import StockNote from './StockNote.svelte';

  let { product, settings = {} } = $props();

  const href = $derived(`${base}/store/${encodeURIComponent(product.slug)}`);
  const options = $derived(
    product.needsOptions ?? (product.hasVariants || product.billingMode === 'SUBSCRIPTION'),
  );

  function add() {
    // the cart store shows the "added" toast itself
    cart.add({ productId: product.id, quantity: 1 }, product);
  }
</script>
