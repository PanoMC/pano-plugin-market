<div
  class="offcanvas offcanvas-end"
  tabindex="-1"
  id="marketCartOffcanvas"
  aria-labelledby="marketCartLabel">
  <div class="offcanvas-header border-bottom">
    <h5 class="offcanvas-title" id="marketCartLabel">
      <i class="fa-solid fa-cart-shopping me-2"></i>{$_('theme.store.cart.title')}
    </h5>
    <button
      type="button"
      class="btn-close"
      data-bs-dismiss="offcanvas"
      aria-label={$_('theme.store.close')}></button>
  </div>
  <div class="offcanvas-body d-flex flex-column">
    {#if resolvedItems.length === 0}
      <div class="my-auto">
        <NoContent icon="fa-solid fa-cart-shopping fa-3x" text={$_('theme.store.cart.empty')} />
      </div>
    {:else}
      <ul class="list-group list-group-flush mb-3">
        {#each resolvedItems as line (line.product.id)}
          <li class="list-group-item px-0 d-flex align-items-center gap-2">
            <div
              class="bg-body-tertiary rounded overflow-hidden flex-shrink-0 d-flex align-items-center justify-content-center"
              style="width: 48px; height: 48px;">
              {#if line.product.imageFileName}
                <img
                  src="{base}/api/market/products/image/{line.product.imageFileName}?thumbnail=true"
                  alt={line.product.name}
                  class="w-100 h-100 object-fit-cover" />
              {:else}
                <i class="fa-solid {line.product.icon || 'fa-box'} text-body-secondary"></i>
              {/if}
            </div>
            <div class="flex-grow-1" style="min-width: 0;">
              <div class="text-truncate fw-semibold">{line.product.name}</div>
              <div class="small text-body-secondary">
                {formatPrice(line.product.price, settings)}
              </div>
            </div>
            <div class="input-group input-group-sm flex-nowrap" style="width: 104px;">
              <button
                type="button"
                class="btn btn-outline-secondary"
                aria-label={$_('theme.store.cart.decrease')}
                onclick={() => setQuantity(line.product.id, line.quantity - 1)}>
                <i class="fa-solid fa-minus"></i>
              </button>
              <span class="input-group-text flex-grow-1 justify-content-center bg-body">{line.quantity}</span>
              <button
                type="button"
                class="btn btn-outline-secondary"
                aria-label={$_('theme.store.cart.increase')}
                disabled={line.product.stock != null && line.quantity >= line.product.stock}
                onclick={() =>
                  setQuantity(
                    line.product.id,
                    line.product.stock != null
                      ? Math.min(line.quantity + 1, line.product.stock)
                      : line.quantity + 1
                  )}>
                <i class="fa-solid fa-plus"></i>
              </button>
            </div>
            <button
              type="button"
              class="btn btn-sm btn-link text-danger"
              aria-label={$_('theme.store.cart.remove')}
              onclick={() => removeFromCart(line.product.id)}>
              <i class="fa-solid fa-trash"></i>
            </button>
          </li>
        {/each}
      </ul>

      <div class="mt-auto">
        <div class="d-flex justify-content-between align-items-center fw-bold fs-5 mb-3">
          <span>{$_('theme.store.cart.total')}</span>
          <span>{formatPrice(total, settings)}</span>
        </div>
        <button type="button" class="btn btn-primary w-100" disabled>
          <i class="fa-solid fa-lock me-2"></i>{$_('theme.store.cart.checkout')}
        </button>
        <div class="text-center small text-body-secondary mt-2">
          <i class="fa-solid fa-circle-info me-1"></i>{$_('theme.store.cart.checkout-soon')}
        </div>
        <button
          type="button"
          class="btn btn-link btn-sm text-danger w-100 mt-1"
          onclick={clearCart}>
          {$_('theme.store.cart.clear')}
        </button>
      </div>
    {/if}
  </div>
</div>

<script>
  import { onMount } from 'svelte';
  import { base } from '@panomc/sdk/svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../i18n';
  import { cart, setQuantity, removeFromCart, clearCart } from '../utils/cart';
  import { formatPrice } from '../utils/format';

  let { productMap = {}, settings = {} } = $props();

  // The offcanvas opens via data-bs-toggle, so Bootstrap owns its backdrop and
  // the body scroll lock. If the user navigates away while it is open, the
  // element is torn out mid-hide and those globals leak onto the next page —
  // dispose synchronously and scrub them by hand. Cleanup lives in onMount's
  // return (NOT onDestroy): onMount never runs during SSR, while onDestroy
  // DOES run on the server and crashes the host's server renderer.
  onMount(() => {
    return () => {
      if (!window.bootstrap) return;
      const el = document.getElementById('marketCartOffcanvas');
      const instance = el && window.bootstrap.Offcanvas.getInstance(el);
      try {
        instance?.dispose();
      } catch (e) {
        /* element already detached */
      }
      document.querySelectorAll('.offcanvas-backdrop').forEach((node) => node.remove());
      document.body.style.removeProperty('overflow');
      document.body.style.removeProperty('padding-right');
    };
  });

  // Resolve cart lines against the known products; drop items whose product is no
  // longer visible (e.g. removed since it was added).
  let resolvedItems = $derived(
    $cart
      .map((item) => ({ product: productMap[item.productId], quantity: item.quantity }))
      .filter((line) => line.product)
  );

  let total = $derived(
    resolvedItems.reduce((sum, line) => sum + Number(line.product.price || 0) * line.quantity, 0)
  );
</script>
