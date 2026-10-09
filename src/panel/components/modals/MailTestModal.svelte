<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.mail-test.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          <select
            id="mail-test-kind"
            class="form-select"
            class:is-invalid={shown.kind}
            aria-label={$_('modals.mail-test.kind')}
            bind:value={form.kind}>
            {#each MAIL_KINDS as kind (kind)}
              <option value={kind}>{$_(`enums.mail-kind.${kind}`)}</option>
            {/each}
          </select>
          <div>
            <input
              id="mail-test-recipient"
              type="email"
              class="form-control"
              class:is-invalid={shown.recipient}
              autocomplete="off"
              maxlength="254"
              placeholder={$_('modals.mail-test.recipient')}
              aria-label={$_('modals.mail-test.recipient')}
              bind:value={form.recipient} />
            {#if shown.recipient}
              <div class="invalid-feedback d-block">{$_(fieldErrorKey(shown.recipient))}</div>
            {/if}
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {$_('modals.mail-test.cta')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { _, showSuccessToast } from '../../../i18n';
  import { call } from '../../utils/api.js';
  import { MAIL_KINDS, fieldErrorKey } from '../../utils/settings.js';
  import { testMailRequest } from '../../utils/settings-extra.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  // "Send Test Mail" of the mail section (13 §17): POST /settings/mail/test. The recipient starts as
  // the admin's own address; MAIL_DISABLED / MAIL_SEND_FAILED answer with a toast and the modal stays.
  let { defaultRecipient = '' } = $props();

  let modalElement = $state(null);
  let form = $state({ kind: 'ORDER_CONFIRMATION', recipient: '' });
  let submitted = $state(false);
  let saving = $state(false);

  const request = $derived(testMailRequest(form));
  const shown = $derived(submitted ? request.errors : {});

  export function open() {
    form = { kind: 'ORDER_CONFIRMATION', recipient: defaultRecipient };
    submitted = false;
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    if (request.body === null) return;

    saving = true;
    let result;
    try {
      result = await call(api.panel.post({ path: '/settings/mail/test', body: request.body }));
    } finally {
      saving = false;
    }
    if (!result.ok) {
      toastError($_, result);
      return;
    }
    showSuccessToast($_('modals.mail-test.toast-sent'));
    hideModal(modalElement);
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
