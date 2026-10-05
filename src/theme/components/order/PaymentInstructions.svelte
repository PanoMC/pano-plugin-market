<div class="card">
  <div class="card-body vstack gap-3">
    {#if instructions.body}
      <div>{@html instructions.body}</div>
    {/if}

    {#if rows.length > 0}
      <dl class="row mb-0">
        {#each rows as row, index (index)}
          <dt class="col-sm-4">{row.label}</dt>
          <dd class="col-sm-8 d-flex flex-wrap align-items-center gap-2">
            <span class="text-break">{row.value}</span>
            {#if row.copyable}
              <CopyButton text={row.value} label={row.label} />
            {/if}
          </dd>
        {/each}
      </dl>
    {/if}

    {#if notify}
      <form class="vstack gap-3 border-top pt-3" onsubmit={submit} novalidate>
        <h3 class="h6 mb-0">{$_('theme.order.transfer-made')}</h3>

        <div>
          <label class="form-label" for="market-order-transfer-sender"
            >{$_('theme.order.transfer-sender')}</label>
          <input
            id="market-order-transfer-sender"
            class={['form-control', errors.senderName && 'is-invalid']}
            type="text"
            maxlength={NOTE_MAX}
            autocomplete="off"
            disabled={busy}
            aria-invalid={errors.senderName ? 'true' : undefined}
            bind:value={senderName} />
          {#if errors.senderName}
            <div class="invalid-feedback">{$_('theme.checkout.field-invalid')}</div>
          {/if}
        </div>

        <div>
          <label class="form-label" for="market-order-transfer-note"
            >{$_('theme.order.transfer-note')}</label>
          <input
            id="market-order-transfer-note"
            class={['form-control', errors.note && 'is-invalid']}
            type="text"
            maxlength={NOTE_MAX}
            autocomplete="off"
            disabled={busy}
            aria-invalid={errors.note ? 'true' : undefined}
            bind:value={note} />
          {#if errors.note}
            <div class="invalid-feedback">{$_('theme.checkout.field-invalid')}</div>
          {/if}
        </div>

        {#if alertKey}
          <div class="alert alert-warning mb-0" role="alert">{$_(alertKey)}</div>
        {/if}

        <div>
          <button type="submit" class="btn btn-primary" disabled={busy}>
            {#if busy}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_('theme.order.transfer-submit')}
          </button>
        </div>
      </form>
    {/if}
  </div>
</div>

<script>
  import { _ } from '../../../i18n.js';
  import {
    NOTE_MAX,
    instructionRows,
    notifyBody,
    notifyFailure,
    showNotifyForm,
  } from '../../lib/paymentPanel.js';
  import { tokenHeaders } from '../../stores/orderTokens.js';
  import { call } from '../../utils/api.js';
  import CopyButton from '../common/CopyButton.svelte';

  /**
   * Offline payment (14 §11.4 `INSTRUCTIONS`): the sanitised body (the one {@html} of the payment panel, the
   * server escapes plain provider text), the labelled values with copy buttons and, for the built-in
   * `bank-transfer`, the "I have made the transfer" form. `readonly` (PROCESSING) hides the form.
   * `id` = publicId, `token` = access token in use or null, `onrefetch()` reloads the order.
   */
  let { id, order, instructions, token = null, readonly = false, onrefetch = () => {} } = $props();

  let senderName = $state('');
  let note = $state('');
  let errors = $state({});
  let alertKey = $state('');
  let busy = $state(false);

  const rows = $derived(instructionRows(instructions));
  const notify = $derived(showNotifyForm({ order, instructions, readonly }));

  async function submit(event) {
    event.preventDefault();

    if (busy) return;

    const built = notifyBody({ senderName, note });

    errors = built.ok ? {} : built.errors;
    alertKey = '';

    if (!built.ok) return;

    busy = true;

    const res = await call(
      'POST',
      `/api/market/orders/${encodeURIComponent(id)}/bank-transfer/notify`,
      {
        body: built.body,
        headers: tokenHeaders(id, token),
      },
    );

    busy = false;

    if (res.ok) {
      await onrefetch();
      return;
    }

    const failure = notifyFailure(res);

    alertKey = failure.alertKey;

    if (failure.refetch) await onrefetch();
  }
</script>
