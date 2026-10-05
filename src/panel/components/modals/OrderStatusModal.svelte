<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_(`modals.order-status.title.${mode}`)}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body">
          <textarea
            class="form-control"
            class:is-invalid={invalid.note}
            rows="3"
            maxlength={NOTE_MAX}
            placeholder={$_('modals.order-status.note')}
            bind:value={note}></textarea>
        </div>
        <div class="modal-footer">
          <button class="btn btn-{variant} w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_(`modals.order-status.cta.${mode}`)}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { NOTE_MAX, STATUS_MODES, statusRequest } from '../order-detail/requests.js';
  import { hideModal, showModal, submitModal } from '../order-detail/send.js';

  // onDone(toastKey) refreshes the page and toasts; onStale() refreshes after a stale-flag error.
  let { orderId, onDone = async () => {}, onStale = async () => {} } = $props();

  let modalElement = $state(null);
  let mode = $state('markPaid');
  let note = $state('');
  let saving = $state(false);
  let invalid = $state({});

  const variant = $derived(STATUS_MODES[mode]?.variant ?? 'primary');

  /** mode: markPaid | cancel | markFailed | approveTransfer | rejectTransfer (13 §6.2). */
  export function open(nextMode) {
    mode = STATUS_MODES[nextMode] ? nextMode : 'markPaid';
    note = '';
    invalid = {};
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    const built = statusRequest(orderId, mode, note);
    invalid = built.error ?? {};
    if (built.error) return;
    saving = true;
    try {
      await submitModal({
        request: built.request,
        $_: $_,
        hide: () => hideModal(modalElement),
        onDone: () => onDone(`modals.order-status.toast.${mode}`),
        onStale,
      });
    } finally {
      saving = false;
    }
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
