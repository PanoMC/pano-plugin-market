<div
  class="modal fade"
  tabindex="-1"
  aria-hidden="true"
  data-bs-backdrop="static"
  data-bs-keyboard="false"
  bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.webhook-secret.title')}</h5>
      </div>
      <div class="modal-body">
        <div class="input-group">
          <input
            type="text"
            class="form-control font-monospace"
            readonly
            autocomplete="off"
            aria-label={$_('modals.webhook-secret.title')}
            value={secret} />
          <CopyButton text={secret} />
        </div>
        <div class="form-text">{$_('modals.webhook-secret.hint')}</div>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-primary w-100" onclick={done}>
          {$_('modals.webhook-secret.done')}
        </button>
      </div>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import CopyButton from '../CopyButton.svelte';
  import { hideModal, showModal } from '../order-detail/send.js';

  // The generated signing secret of a new webhook. It is shown once and never again.
  let modalElement = $state(null);
  let secret = $state('');

  export function open(value) {
    secret = value;
    showModal(modalElement);
  }

  function done() {
    hideModal(modalElement);
    // Forget the secret once the modal is gone.
    modalElement?.addEventListener('hidden.bs.modal', () => (secret = ''), { once: true });
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
