<div class="card h-100 shadow-sm border-0 position-relative" class:opacity-75={soldOut}>
  <div
    role="button"
    tabindex="0"
    aria-label={product.name}
    on:click={() => dispatch('select', product)}
    on:keydown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), dispatch('select', product))}
    style="cursor: pointer;">
    <div class="ratio ratio-16x9 bg-body-tertiary rounded-top overflow-hidden">
      {#if product.imageFileName}
        <img
          src="{base}/api/market/products/image/{product.imageFileName}?thumbnail=true"
          alt={product.name}
          class="w-100 h-100 object-fit-cover"
          loading="lazy" />
      {:else}
        <div class="d-flex align-items-center justify-content-center h-100">
          <i class="fa-solid {product.icon || 'fa-box'} fa-2x text-body-secondary"></i>
        </div>
      {/if}
    </div>
  </div>

  {#if product.featured}
    <span class="badge text-bg-warning position-absolute top-0 start-0 m-2">
      <i class="fa-solid fa-star me-1"></i>{$_('theme.store.featured-badge')}
    </span>
  {/if}
  {#if soldOut}
    <span class="badge text-bg-danger position-absolute top-0 end-0 m-2">
      {$_('theme.store.sold-out')}
    </span>
  {/if}

  <div class="card-body d-flex flex-column">
    <h3 class="h6 card-title mb-1 text-truncate" title={product.name}>{product.name}</h3>
    <div class="mb-3">
      <span class="fw-bold">{formatPrice(product.price, settings)}</span>
      {#if settings.creditsEnabled && product.creditPrice > 0}
        <span class="badge text-bg-info ms-1">
          <i class="fa-solid fa-coins me-1"></i>{product.creditPrice} {settings.creditName || $_('theme.store.credits')}
        </span>
      {/if}
    </div>
    <button
      type="button"
      class="btn btn-sm btn-primary mt-auto w-100"
      disabled={soldOut}
      on:click={add}>
      <i class="fa-solid fa-cart-plus me-1"></i>
      {soldOut ? $_('theme.store.sold-out') : $_('theme.store.add-to-cart')}
    </button>
  </div>
</div>

<script>
  import { createEventDispatcher } from 'svelte';
  import { base } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../i18n';
  import { pluginId } from '../../i18n';
  import { addToCart } from '../utils/cart';
  import { formatPrice } from '../utils/format';

  export let product;
  export let settings = {};

  const dispatch = createEventDispatcher();

  $: soldOut = product.stock === 0;

  function add() {
    if (soldOut) return;
    addToCart(product.id, 1, product.stock);
    showSuccessToast(`plugins.${pluginId}.theme.store.added-to-cart`);
  }
</script>
