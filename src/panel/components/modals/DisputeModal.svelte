<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.dispute.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <MoneyInput
            bind:value={amount}
            currency={order?.currency ?? ''}
            {exponent}
            invalid={invalid.amount === true}
            placeholder={$_('modals.dispute.amount')} />
          <input
            class="form-control"
            class:is-invalid={invalid.reason}
            type="text"
            autocomplete="off"
            maxlength={DISPUTE_REASON_MAX}
            placeholder={$_('modals.dispute.reason')}
            bind:value={reason} />
        </div>
        <div class="modal-footer">
          <button class="btn btn-danger w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_('modals.dispute.cta')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import MoneyInput from '../MoneyInput.svelte';
  import { remainingCollected } from '../order-detail/model.js';
  import { DISPUTE_REASON_MAX, disputeRequest } from '../order-detail/requests.js';
  import { hideModal, showModal, submitModal } from '../order-detail/send.js';

  // order: the loaded order; ctx: GET /context (currency exponents), may be null.
  let { order = null, ctx = null, onDone = async () => {}, onStale = async () => {} } = $props();

  let modalElement = $state(null);
  let amount = $state(null);
  let reason = $state('');
  let saving = $state(false);
  let invalid = $state({});

  const exponent = $derived(
    ctx?.currencies?.find?.((c) => c.code === order?.currency)?.exponent ?? 2,
  );

  /** The amount starts at the money still collected (an empty field lets the server default apply). */
  export function open() {
    const remaining = remainingCollected(order);
    amount = remaining > 0 ? remaining : null;
    reason = '';
    invalid = {};
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !order) return;
    const built = disputeRequest(order.id, { amount, reason });
    invalid = built.error ?? {};
    if (built.error) return;
    saving = true;
    try {
      await submitModal({
        request: built.request,
        $_: $_,
        hide: () => hideModal(modalElement),
        onDone: () => onDone('modals.dispute.toast'),
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
