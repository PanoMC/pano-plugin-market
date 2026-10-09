<div
  class="market-replace-cart-modal modal fade"
  id="marketReplaceCartModal"
  tabindex="-1"
  aria-labelledby="marketReplaceCartTitle"
  aria-hidden="true"
  bind:this={element}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="market-replace-cart-modal__header modal-header">
        <h2 class="market-replace-cart-modal__title modal-title fs-5" id="marketReplaceCartTitle">
          {$_('theme.cart.replace-title')}
        </h2>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('theme.common.close')}></button>
      </div>
      <div class="market-replace-cart-modal__body modal-body">
        {$_('theme.cart.replace-message')}
      </div>
      <div class="market-replace-cart-modal__footer modal-footer">
        <button
          type="button"
          class="market-replace-cart-modal__action btn btn-outline-secondary"
          data-bs-dismiss="modal">
          {$_('theme.common.cancel')}
        </button>
        <button
          type="button"
          class="market-replace-cart-modal__replace-confirm btn btn-primary"
          onclick={confirm}>
          {$_('theme.cart.replace-confirm')}
        </button>
      </div>
    </div>
  </div>
</div>

<script>
  import { onMount } from 'svelte';
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const { _ } = market;
  const cart = market.require('cart');

  // The cart controller holds the pending question (cart.add resolves when the buyer decides): the modal opens
  // while `replaceRequest` is set, confirm replaces the cart, closing without confirming cancels.
  const request = $derived(cart.state.replaceRequest);

  let element = $state();
  let modal;
  let confirmed = false;

  onMount(() => {
    modal = window.bootstrap?.Modal.getOrCreateInstance(element);

    const onHidden = () => {
      if (!confirmed) cart.actions.cancelReplace();
      confirmed = false;
    };
    element.addEventListener('hidden.bs.modal', onHidden);

    return () => {
      element.removeEventListener('hidden.bs.modal', onHidden);
      modal?.dispose();
      modal = undefined;
    };
  });

  function confirm() {
    confirmed = true;
    modal?.hide();
    cart.actions.confirmReplace();
  }

  // opens the modal while a question is pending; the derived value only changes with the question itself
  $effect(() => {
    if (request) modal?.show();
    else if (!confirmed) modal?.hide();
  });
</script>
