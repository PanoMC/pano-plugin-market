<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_('modals.invoice-sequence.title', { values: { series } })}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body">
          <input
            id="invoice-sequence-next"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            class="form-control"
            class:is-invalid={shownError}
            placeholder={$_('modals.invoice-sequence.next-number')}
            aria-label={$_('modals.invoice-sequence.next-number')}
            bind:value={text} />
          {#if shownError}
            <div class="invalid-feedback d-block">{$_(fieldErrorKey(shownError))}</div>
          {/if}
          <div class="form-text">
            {$_('modals.invoice-sequence.current', { values: { current } })}
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {$_('common.save')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showSuccessToast } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import { fieldErrorKey, sequenceError } from '../../utils/settings.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  // "Edit" of a number sequence (13 §17 billing). Only upwards: the server refuses anything else with
  // 400 INVALID_INVOICE_SEQUENCE, which marks the field.
  let { onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let series = $state('');
  let current = $state(1);
  let text = $state('');
  let touched = $state(false);
  let saving = $state(false);
  let refused = $state.raw(null);

  const clientError = $derived(sequenceError(text, current));
  const shownError = $derived(
    refused && refused.text === text ? 'NOT_UPWARDS' : touched ? clientError : null,
  );

  export function open(sequence) {
    series = sequence.series;
    current = Number(sequence.nextNumber);
    text = String(current + 1);
    touched = false;
    saving = false;
    refused = null;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    touched = true;
    if (clientError) return;

    saving = true;
    let result;
    try {
      result = await call(
        ApiUtil.put({
          path: marketPath('/settings/invoice-sequence'),
          body: { series, nextNumber: Number(text) },
        }),
      );
    } finally {
      saving = false;
    }
    if (!result.ok) {
      if (result.error === 'INVALID_INVOICE_SEQUENCE') refused = { text };
      toastError($_, result);
      return;
    }
    showSuccessToast($_('modals.invoice-sequence.toast-saved'));
    hideModal(modalElement);
    onSaved(result.body);
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
