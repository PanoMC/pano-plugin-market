<div
  class="offcanvas offcanvas-end"
  tabindex="-1"
  id="marketCartOffcanvas"
  aria-labelledby="marketCartLabel"
  bind:this={element}>
  <div class="offcanvas-header border-bottom">
    <h5 class="offcanvas-title" id="marketCartLabel">
      <i class="fa-solid fa-cart-shopping me-2" aria-hidden="true"></i>{$_('theme.cart.title')}
    </h5>
    <span class="visually-hidden" aria-live="polite" aria-atomic="true"
      >{$_('theme.cart.items-in-cart', { values: { count: $cart.count } })}</span>
    <button
      type="button"
      class="btn-close"
      data-bs-dismiss="offcanvas"
      aria-label={$_('theme.common.close')}></button>
  </div>

  <div class="offcanvas-body d-flex flex-column">
    {#if view === 'LOADING'}
      <div class="placeholder-glow" aria-busy="true">
        <span class="visually-hidden">{$_('theme.common.loading')}</span>
        {#each [0, 1, 2] as index (index)}
          <div class="placeholder col-12 mb-3 py-4" aria-hidden="true"></div>
        {/each}
      </div>
    {:else if view === 'EMPTY'}
      <div class="my-auto text-center">
        <NoContent icon="fa-solid fa-cart-shopping fa-3x" text={$_('theme.cart.empty')} />
        <a class="btn btn-primary mt-3" href="{base}/store" onclick={close}>
          {$_('theme.cart.browse')}
        </a>
      </div>
    {:else if view === 'ERROR'}
      <ErrorAlert message={$_('theme.errors.NETWORK')} onretry={() => cart.retry()} />
    {:else}
      {#if view === 'ERROR_ROWS'}
        <ErrorAlert message={$_('theme.errors.NETWORK')} onretry={() => cart.retry()} />
      {/if}

      <ul class="list-group list-group-flush mb-3">
        {#each rows as row (row.key)}
          <CartLineRow {row} {money} {creditsEnabled} {creditName} onnavigate={close} />
        {/each}
      </ul>

      <div class="mt-auto">
        <div class="d-flex justify-content-between align-items-center fw-bold fs-5">
          <span>{$_('theme.cart.subtotal')}</span>
          {#if view === 'QUOTE_ROWS'}
            <span>{formatMoney(subtotalOf($cart.quote), $cart.quote.currency)}</span>
          {:else}
            <span class="placeholder-glow" aria-busy="true">
              <span class="placeholder col-12 px-5"></span>
              <span class="visually-hidden">{$_('theme.common.loading')}</span>
            </span>
          {/if}
        </div>
        <div class="small text-body-secondary mb-3">{$_('theme.cart.fees-at-checkout')}</div>
      </div>
    {/if}

    {#if view !== 'LOADING' && view !== 'EMPTY' && view !== 'ERROR'}
      <a class="btn btn-primary w-100" href="{base}/store/checkout" onclick={close}>
        <i class="fa-solid fa-lock me-2" aria-hidden="true"></i>{$_('theme.cart.checkout')}
      </a>
      <button
        type="button"
        class="btn btn-link btn-sm text-danger w-100 mt-1"
        onclick={() => confirmClear?.show()}>
        {$_('theme.cart.clear')}
      </button>
    {:else if view === 'EMPTY'}
      <button type="button" class="btn btn-primary w-100" disabled>
        <i class="fa-solid fa-lock me-2" aria-hidden="true"></i>{$_('theme.cart.checkout')}
      </button>
    {/if}
  </div>
</div>

<ConfirmModal
  bind:this={confirmClear}
  id="marketClearCartModal"
  title={$_('theme.cart.clear-title')}
  message={$_('theme.cart.clear-message')}
  confirmLabel={$_('theme.cart.clear')}
  onconfirm={() => cart.clear()} />

<ReplaceCartModal />

<script>
  import { onMount } from 'svelte';
  import { base } from '@panomc/sdk/svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../../i18n.js';
  import { cart } from '../../stores/cart.js';
  import { storeSettings } from '../../stores/storeSettings.js';
  import { formatMoney } from '../../utils/format.js';
  import ConfirmModal from '../common/ConfirmModal.svelte';
  import ErrorAlert from '../common/ErrorAlert.svelte';
  import CartLineRow from './CartLineRow.svelte';
  import ReplaceCartModal from './ReplaceCartModal.svelte';
  import { metaRows, quoteRows, subtotalOf, viewState } from './cartView.js';

  let element = $state();
  let confirmClear = $state();

  const view = $derived(viewState($cart));
  const rows = $derived(view === 'QUOTE_ROWS' ? quoteRows($cart.quote) : metaRows($cart.lines));
  const currency = $derived(
    view === 'QUOTE_ROWS' ? $cart.quote.currency : $storeSettings?.currency,
  );
  const creditsEnabled = $derived(
    Boolean($storeSettings?.creditsEnabled || $cart.quote?.credits?.enabled),
  );
  const creditName = $derived($cart.quote?.credits?.name || $storeSettings?.creditName || '');

  const money = (amount) =>
    formatMoney(amount, currency, { removeCents: Boolean($storeSettings?.removeCents) });

  /** Closes the offcanvas before navigating (a link inside it keeps its own default action). */
  function close() {
    window.bootstrap?.Offcanvas.getInstance(element)?.hide();
  }

  // Bootstrap objects are created in onMount and disposed in its return function (NOT onDestroy: that runs
  // during SSR too and crashes the host's server renderer). The offcanvas can be torn out of the page while
  // it is open, which leaves Bootstrap's backdrop and the body scroll lock behind, so those are scrubbed.
  onMount(() => {
    window.bootstrap?.Offcanvas.getOrCreateInstance(element);

    let unwatch = null;

    // opening always loads the cart and asks for a fresh quote (14 §7.3); it is watched while it is open
    const onShow = () => {
      cart.init().then(() => cart.requestQuote());
      unwatch?.();
      unwatch = cart.watchQuotes();
    };
    const onHidden = () => {
      unwatch?.();
      unwatch = null;
    };

    element.addEventListener('show.bs.offcanvas', onShow);
    element.addEventListener('hidden.bs.offcanvas', onHidden);

    return () => {
      element.removeEventListener('show.bs.offcanvas', onShow);
      element.removeEventListener('hidden.bs.offcanvas', onHidden);
      unwatch?.();
      try {
        window.bootstrap?.Offcanvas.getInstance(element)?.dispose();
      } catch (e) {
        /* element already detached */
      }
      document.querySelectorAll('.offcanvas-backdrop').forEach((node) => node.remove());
      document.body.style.removeProperty('overflow');
      document.body.style.removeProperty('padding-right');
    };
  });
</script>
