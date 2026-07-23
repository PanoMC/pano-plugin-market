<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalEl}>
  <div class="modal-dialog modal-lg modal-dialog-centered modal-dialog-scrollable">
    <div class="modal-content">
      {#if product}
        <div class="modal-header">
          <h5 class="modal-title text-truncate">{product.name}</h5>
          <button
            type="button"
            class="btn-close"
            data-bs-dismiss="modal"
            aria-label={$_('theme.store.close')}></button>
        </div>
        <div class="modal-body">
          <div class="row g-4">
            <div class="col-md-5">
              <div class="ratio ratio-1x1 bg-body-tertiary rounded overflow-hidden">
                {#if product.imageFileName}
                  <img
                    src="{base}/api/market/products/image/{product.imageFileName}"
                    alt={product.name}
                    class="w-100 h-100 object-fit-cover" />
                {:else}
                  <div class="d-flex align-items-center justify-content-center h-100">
                    <i class="fa-solid {product.icon || 'fa-box'} fa-3x text-body-secondary"></i>
                  </div>
                {/if}
              </div>
            </div>
            <div class="col-md-7 vstack gap-3">
              <div class="d-flex flex-wrap gap-2 align-items-center">
                <span class="fs-4 fw-bold">{formatPrice(product.price, settings.currencySymbol)}</span>
                {#if settings.creditsEnabled && product.creditPrice > 0}
                  <span class="badge text-bg-info">
                    <i class="fa-solid fa-coins me-1"></i>{product.creditPrice} {settings.creditName}
                  </span>
                {/if}
                {#if product.featured}
                  <span class="badge text-bg-warning">
                    <i class="fa-solid fa-star me-1"></i>{$_('theme.store.featured-badge')}
                  </span>
                {/if}
              </div>

              <div class="d-flex flex-wrap gap-3 small text-body-secondary">
                {#if categoryName}
                  <span><i class="fa-solid fa-folder me-1"></i>{categoryName}</span>
                {/if}
                <span>
                  <i class="fa-solid fa-box me-1"></i>
                  {#if product.stock === 0}
                    {$_('theme.store.sold-out')}
                  {:else if product.stock == null}
                    {$_('theme.store.in-stock')}
                  {:else}
                    {$_('theme.store.stock-count', { values: { count: product.stock } })}
                  {/if}
                </span>
              </div>

              {#if product.description}
                <div>{@html product.description}</div>
              {/if}

              <button
                type="button"
                class="btn btn-primary mt-auto"
                disabled={product.stock === 0}
                on:click={add}>
                <i class="fa-solid fa-cart-plus me-1"></i>
                {product.stock === 0 ? $_('theme.store.sold-out') : $_('theme.store.add-to-cart')}
              </button>
            </div>
          </div>
        </div>
      {/if}
    </div>
  </div>
</div>

<script>
  import { createEventDispatcher, onMount } from 'svelte';
  import { base, browser } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { _ } from '../../i18n';
  import { pluginId } from '../../i18n';
  import { addToCart } from '../utils/cart';
  import { formatPrice } from '../utils/format';

  export let product = null;
  export let settings = {};
  export let categoryName = null;

  const dispatch = createEventDispatcher();

  let modalEl;
  let modalInstance = null;

  onMount(() => {
    if (browser && window.bootstrap) {
      modalInstance = new window.bootstrap.Modal(modalEl);
      modalEl.addEventListener('hidden.bs.modal', () => dispatch('close'));
    }
    return () => modalInstance?.dispose();
  });

  // Show whenever a product is selected; the parent nulls `product` on close.
  $: if (modalInstance && product) modalInstance.show();

  function add() {
    if (!product || product.stock === 0) return;
    addToCart(product.id, 1);
    showToast(`plugins.${pluginId}.theme.store.added-to-cart`);
  }
</script>
