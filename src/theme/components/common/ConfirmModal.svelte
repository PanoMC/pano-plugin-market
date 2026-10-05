<div
  class="modal fade"
  {id}
  tabindex="-1"
  aria-labelledby="{id}-title"
  aria-hidden="true"
  bind:this={element}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h2 class="modal-title fs-5" id="{id}-title">{title}</h2>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('theme.common.close')}></button>
      </div>
      {#if message}
        <div class="modal-body">{message}</div>
      {/if}
      <div class="modal-footer">
        <button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">
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
  import { _ } from '../../../i18n.js';

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
  let confirmed = false;

  onMount(() => {
    // Bootstrap is the host's global; the instance lives and dies with this component.
    modal = window.bootstrap?.Modal.getOrCreateInstance(element);

    const onHidden = () => {
      if (!confirmed) oncancel();
      confirmed = false;
    };
    element.addEventListener('hidden.bs.modal', onHidden);

    return () => {
      element.removeEventListener('hidden.bs.modal', onHidden);
      modal?.dispose();
      modal = undefined;
    };
  });

  export function show() {
    modal?.show();
  }

  export function hide() {
    modal?.hide();
  }

  function confirm() {
    confirmed = true;
    modal?.hide();
    onconfirm();
  }
</script>
