<div
  class="modal fade"
  id="marketReplaceCartModal"
  tabindex="-1"
  aria-labelledby="marketReplaceCartTitle"
  aria-hidden="true"
  bind:this={element}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h2 class="modal-title fs-5" id="marketReplaceCartTitle">
          {$_('theme.cart.replace-title')}
        </h2>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('theme.common.close')}></button>
      </div>
      <div class="modal-body">{$_('theme.cart.replace-message')}</div>
      <div class="modal-footer">
        <button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">
          {$_('theme.common.cancel')}
        </button>
        <button type="button" class="btn btn-primary" onclick={confirm}>
          {$_('theme.cart.replace-confirm')}
        </button>
      </div>
    </div>
  </div>
</div>

<script>
  import { onMount } from 'svelte';
  import { _ } from '../../../i18n.js';
  import { cart } from '../../stores/cart.js';

  // The cart store holds the pending question (cart.add resolves when the buyer decides): the modal opens
  // while `replaceRequest` is set, confirm replaces the cart, closing without confirming cancels.
  const request = cart.replaceRequest;

  let element = $state();
  let modal;
  let confirmed = false;

  onMount(() => {
    modal = window.bootstrap?.Modal.getOrCreateInstance(element);

    const onHidden = () => {
      if (!confirmed) cart.cancelReplace();
      confirmed = false;
    };
    element.addEventListener('hidden.bs.modal', onHidden);

    const unsubscribe = request.subscribe((value) => {
      if (value) modal?.show();
      else if (!confirmed) modal?.hide();
    });

    return () => {
      unsubscribe();
      element.removeEventListener('hidden.bs.modal', onHidden);
      modal?.dispose();
      modal = undefined;
    };
  });

  function confirm() {
    confirmed = true;
    modal?.hide();
    cart.confirmReplace();
  }
</script>
