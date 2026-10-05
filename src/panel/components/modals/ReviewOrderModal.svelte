<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.review.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <div class="vstack gap-1">
            {#each DECISIONS as value (value)}
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="radio"
                  name="review-decision"
                  id="review-decision-{value}"
                  {value}
                  bind:group={decision} />
                <label class="form-check-label" for="review-decision-{value}">
                  {$_(`modals.review.decision.${value}`)}
                </label>
              </div>
            {/each}
          </div>

          {#if offersRefund}
            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="review-refund"
                bind:checked={refund} />
              <label class="form-check-label" for="review-refund">
                {$_('modals.review.refund')}
              </label>
            </div>
          {/if}

          {#if forceable && decision === 'ACCEPT'}
            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="review-force"
                bind:checked={force} />
              <label class="form-check-label" for="review-force">
                {$_('modals.review.force')}
              </label>
            </div>
          {/if}

          <textarea
            class="form-control"
            class:is-invalid={invalid.note}
            rows="3"
            maxlength={NOTE_MAX}
            placeholder={$_('modals.review.note')}
            bind:value={note}></textarea>
        </div>
        <div class="modal-footer">
          <button
            class="btn btn-{decision === 'REJECT' ? 'danger' : 'primary'} w-100"
            type="submit"
            disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_(`modals.review.cta.${decision}`)}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import {
    FORCEABLE_REVIEW_ERRORS,
    NOTE_MAX,
    reviewOffersRefund,
    reviewRequest,
  } from '../order-detail/requests.js';
  import { hideModal, showModal, submitModal } from '../order-detail/send.js';

  const DECISIONS = ['ACCEPT', 'REJECT'];

  // order: the loaded order (id, paidAmount).
  let { order = null, onDone = async () => {}, onStale = async () => {} } = $props();

  let modalElement = $state(null);
  let decision = $state('ACCEPT');
  let refund = $state(true);
  let force = $state(false);
  let forceable = $state(false);
  let note = $state('');
  let saving = $state(false);
  let invalid = $state({});

  const offersRefund = $derived(reviewOffersRefund(order, decision));

  export function open() {
    decision = 'ACCEPT';
    refund = true;
    force = false;
    forceable = false;
    note = '';
    invalid = {};
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !order) return;
    const built = reviewRequest(order, { decision, refund, note, force });
    invalid = built.error ?? {};
    if (built.error) return;
    saving = true;
    try {
      const result = await submitModal({
        request: built.request,
        $_: $_,
        hide: () => hideModal(modalElement),
        onDone: () => onDone(`modals.review.toast.${decision}`),
        onStale,
      });
      // OUT_OF_STOCK and friends keep the modal open (the order stays in REVIEW); the admin may force.
      if (!result.ok && FORCEABLE_REVIEW_ERRORS.has(result.error)) forceable = true;
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
