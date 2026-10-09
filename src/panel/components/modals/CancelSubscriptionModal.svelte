<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.cancel-subscription.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          <div>
            <div class="form-check">
              <input
                id="cancel-sub-period-end"
                class="form-check-input"
                type="radio"
                name="cancelSubscriptionTiming"
                value="period-end"
                bind:group={form.timing} />
              <label class="form-check-label" for="cancel-sub-period-end">
                {$_('modals.cancel-subscription.period-end')}
              </label>
            </div>
            <div class="form-check">
              <input
                id="cancel-sub-now"
                class="form-check-input"
                type="radio"
                name="cancelSubscriptionTiming"
                value="now"
                bind:group={form.timing} />
              <label class="form-check-label" for="cancel-sub-now">
                {$_('modals.cancel-subscription.now')}
              </label>
            </div>
            <div class="form-text">
              {$_(`modals.cancel-subscription.hint-${form.timing}`)}
            </div>
          </div>

          <div>
            <textarea
              id="cancel-sub-reason"
              class="form-control"
              class:is-invalid={shownReasonError}
              style="height: 90px"
              maxlength={REASON_MAX + 100}
              placeholder={$_('modals.cancel-subscription.reason')}
              aria-label={$_('modals.cancel-subscription.reason')}
              bind:value={form.reason}></textarea>
            {#if shownReasonError}
              <div class="invalid-feedback d-block">
                {$_('modals.cancel-subscription.error.reason-TOO_LONG')}
              </div>
            {/if}
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-danger w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {$_('modals.cancel-subscription.submit')}
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
  import { REASON_MAX, buildCancelBody, validateCancel } from '../../utils/subscriptions.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  // Stale answers (13 section 23): the subscription was removed or changed state. Per 13 section 14
  // SUBSCRIPTION_NOT_CANCELLABLE and PAYMENT_PROVIDER_ERROR only toast and keep the modal open.
  const STALE = new Set(['NOT_FOUND', 'INVALID_STATE']);

  // onSaved(stale): called after the modal closed; stale = true when the row changed under the admin.
  let { onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let subscription = $state(null);
  let form = $state({ timing: 'period-end', reason: '' });
  let touched = $state(false);
  let saving = $state(false);

  const checked = $derived(validateCancel(form));
  const shownReasonError = $derived(touched && !checked.ok && checked.errors.reason);

  export function open(target) {
    subscription = target;
    form = { timing: 'period-end', reason: '' };
    touched = false;
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !subscription) return;
    touched = true;
    if (!checked.ok) return;

    saving = true;
    let result;
    try {
      result = await call(
        api.panel.post({
          path: `/subscriptions/${subscription.id}/cancel`,
          body: buildCancelBody(form),
        }),
      );
    } finally {
      saving = false;
    }
    if (!result.ok) {
      toastError($_, result);
      if (STALE.has(result.error)) {
        hideModal(modalElement);
        setTimeout(() => onSaved(true), 350);
      }
      return;
    }
    showSuccessToast($_('modals.cancel-subscription.toast-cancelled'));
    hideModal(modalElement);
    // The modal is hidden before the page navigates or refreshes (13 section 1.4).
    setTimeout(() => onSaved(false), 350);
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
