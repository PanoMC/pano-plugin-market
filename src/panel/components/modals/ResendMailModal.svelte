<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.resend-mail.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <select
            class="form-select"
            class:is-invalid={invalid.kind}
            aria-label={$_('modals.resend-mail.kind')}
            bind:value={kind}>
            {#each RESEND_KINDS as value (value)}
              <option
                {value}
                disabled={SHIPMENT_MAIL_KINDS.includes(value) && shipments.length === 0}>
                {$_(`enums.mail-kind.${value}`)}
              </option>
            {/each}
          </select>

          {#if shipmentMail}
            <select
              class="form-select"
              class:is-invalid={invalid.shipment}
              aria-label={$_('modals.resend-mail.shipment')}
              bind:value={shipmentId}>
              {#each shipments as shipment (shipment.id)}
                <option value={shipment.id}>
                  #{shipment.id}
                  {shipment.carrierName ?? shipment.providerId ?? ''}
                  {shipment.trackingNumber ?? ''}
                </option>
              {/each}
            </select>
          {/if}

          <input
            class="form-control"
            class:is-invalid={invalid.recipient || mailError === 'MAIL_RECIPIENT_REQUIRED'}
            type="email"
            autocomplete="off"
            maxlength="255"
            placeholder={order?.email || $_('modals.resend-mail.recipient')}
            bind:value={recipient} />
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_('modals.resend-mail.cta')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { RESEND_KINDS, SHIPMENT_MAIL_KINDS, resendRequest } from '../order-detail/requests.js';
  import { hideModal, showModal, submitModal } from '../order-detail/send.js';

  // order: the loaded order (email); shipments: detail.shipments (for the two shipment mails).
  let {
    order = null,
    shipments = [],
    onDone = async () => {},
    onStale = async () => {},
  } = $props();

  let modalElement = $state(null);
  let kind = $state('ORDER_CONFIRMATION');
  let recipient = $state('');
  let shipmentId = $state(null);
  let saving = $state(false);
  let invalid = $state({});
  let mailError = $state('');

  const shipmentMail = $derived(SHIPMENT_MAIL_KINDS.includes(kind));

  export function open() {
    kind = 'ORDER_CONFIRMATION';
    recipient = '';
    // The latest shipment is the likeliest target of a re-sent shipment mail.
    shipmentId = shipments.length > 0 ? shipments[shipments.length - 1].id : null;
    invalid = {};
    mailError = '';
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !order) return;
    const built = resendRequest(order.id, {
      kind,
      recipient,
      shipmentId: shipmentMail ? Number(shipmentId) : null,
    });
    invalid = built.error ?? {};
    mailError = '';
    if (built.error) return;
    saving = true;
    try {
      const result = await submitModal({
        request: built.request,
        $_: $_,
        hide: () => hideModal(modalElement),
        onDone: () => onDone('modals.resend-mail.toast'),
        onStale,
      });
      // MAIL_RECIPIENT_REQUIRED / MAIL_DISABLED / MAIL_NOT_APPLICABLE: the toast named it, stay open.
      if (!result.ok) mailError = result.error;
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
