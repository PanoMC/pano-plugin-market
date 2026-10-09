<div
  class="market-confirm-modal modal fade"
  {id}
  tabindex="-1"
  aria-labelledby="{id}-title"
  aria-hidden="true"
  bind:this={element}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="market-confirm-modal__header modal-header">
        <h2 class="market-confirm-modal__title modal-title fs-5" id="{id}-title">{title}</h2>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('theme.common.close')}></button>
      </div>
      {#if message}
        <div class="market-confirm-modal__body modal-body">{message}</div>
      {/if}
      <div class="market-confirm-modal__footer modal-footer">
        <button
          type="button"
          class="market-confirm-modal__action btn btn-outline-secondary"
          data-bs-dismiss="modal">
          {cancelLabel || $_('theme.common.cancel')}
        </button>
        <button type="button" class={confirmClass} onclick={confirm}>
          {confirmLabel || $_('theme.common.confirm')}
        </button>
      </div>
    </div>
  </div>
</div>

<script>
  import { onMount } from 'svelte';
  import { plugin } from '@panomc/sdk/controllers';
  import { createConfirmController } from '../../lib/confirmModal.js';

  const { _ } = plugin('market');

  /** id: unique DOM id; title / message / labels: already translated text; open it with bind:this + show(). */
  let {
    id,
    title,
    message = '',
    confirmLabel = '',
    cancelLabel = '',
    variant = 'danger' /* 'danger' | 'primary' */,
    onconfirm = () => {},
    oncancel = () => {},
  } = $props();

  const confirmClass = $derived(variant === 'primary' ? 'btn btn-primary' : 'btn btn-danger');

  let element = $state();
  let modal;

  // confirm / dismiss: the action runs once, from the single hidden event (see lib/confirmModal.js)
  const controller = createConfirmController({
    getModal: () => modal,
    getElement: () => element,
    onconfirm: () => onconfirm(),
    oncancel: () => oncancel(),
  });

  onMount(() => {
    // Bootstrap is the host's global; the instance lives and dies with this component.
    modal = window.bootstrap?.Modal.getOrCreateInstance(element);

    const onHide = () => controller.hideStarted();
    const onHidden = () => controller.hidden();
    element.addEventListener('hide.bs.modal', onHide);
    element.addEventListener('hidden.bs.modal', onHidden);

    return () => {
      element.removeEventListener('hide.bs.modal', onHide);
      element.removeEventListener('hidden.bs.modal', onHidden);
      modal?.dispose();
      modal = undefined;
    };
  });

  export function show() {
    // a click that lands before onMount has run (a list that has only just rendered) still opens the modal
    modal ??= window.bootstrap?.Modal.getOrCreateInstance(element);
    controller.reset();
    modal?.show();
  }

  export function hide() {
    modal?.hide();
  }

  const confirm = () => controller.confirm();
</script>
