<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.refund.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          {#if loadError}
            <LoadError error={loadError} onRetry={retryPreview} />
          {:else if !preview}
            <div class="text-center py-4">
              <span class="spinner-border" role="status" aria-label={$_('common.loading')}></span>
            </div>
          {:else}
            <div class="vstack gap-1">
              {#each modes as value (value)}
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="radio"
                    name="refund-mode"
                    id="refund-mode-{value}"
                    {value}
                    bind:group={form.mode} />
                  <label class="form-check-label" for="refund-mode-{value}">
                    {$_(`modals.refund.mode.${value}`)}
                  </label>
                </div>
              {/each}
            </div>

            {#if form.mode === 'AMOUNT' && !overriding}
              <div>
                <MoneyInput
                  bind:value={form.amount}
                  currency={order?.currency ?? ''}
                  {exponent}
                  invalid={shown.amount === true}
                  placeholder={$_('modals.refund.amount')} />
                {#if shown.amount && amountHint}
                  <div class="invalid-feedback d-block">{amountHint}</div>
                {/if}
              </div>
            {/if}

            {#if form.mode === 'ITEMS'}
              <div class="vstack gap-2">
                {#each refundable as row (row.id)}
                  <div class="d-flex align-items-center gap-3">
                    <div class="flex-grow-1">
                      <div>{row.productName}</div>
                      {#if row.variantName}
                        <div class="text-body-secondary">{row.variantName}</div>
                      {/if}
                    </div>
                    <div class="text-body-secondary text-nowrap">
                      {$_('modals.refund.items.of', { values: { count: row.remaining } })}
                    </div>
                    <div class="w-25">
                      <input
                        class="form-control"
                        class:is-invalid={itemsInvalid[row.id] === true}
                        type="number"
                        min="0"
                        max={row.remaining}
                        step="1"
                        aria-label={$_('modals.refund.items.quantity')}
                        placeholder="0"
                        bind:value={form.quantities[row.id]} />
                    </div>
                  </div>
                {/each}
                {#if shown.items}
                  <div class="invalid-feedback d-block">{$_('modals.refund.error.items')}</div>
                {/if}
              </div>
            {/if}

            {#if amountMax !== null}
              <div class="invalid-feedback d-block">
                {$_('modals.refund.invalid-amount', {
                  values: { max: fmt.money(amountMax, order?.currency) },
                })}
              </div>
            {/if}

            {#each listed as warning, index (index)}
              <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
                <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
                <div>{warningText(warning)}</div>
              </div>
            {/each}

            {#if splitVisible}
              <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
                <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
                <div>
                  <b>{$_('modals.refund.split-warning.title')}</b>
                  <div>{$_('modals.refund.split-warning.body')}</div>
                </div>
              </div>
            {/if}

            <dl class="row mb-0">
              {#if overriding}
                <dt class="col-6">{$_('modals.refund.gateway')}</dt>
                <dd class="col-6 text-end">{fmt.money(num(form.gateway), order?.currency)}</dd>
                <dt class="col-6">{$_('modals.refund.credits')}</dt>
                <dd class="col-6 text-end">
                  {fmt.credits(num(form.credits), ctx?.creditName)}
                  <span class="text-body-secondary">
                    ({fmt.money(creditPart, order?.currency)})
                  </span>
                </dd>
              {:else}
                <dt class="col-6">{$_('modals.refund.gateway')}</dt>
                <dd class="col-6 text-end">{fmt.money(preview.gatewayAmount, order?.currency)}</dd>
                <dt class="col-6">{$_('modals.refund.credits')}</dt>
                <dd class="col-6 text-end">
                  {fmt.credits(preview.creditAmount, ctx?.creditName)}
                  <span class="text-body-secondary">
                    ({fmt.money(preview.creditValue, order?.currency)})
                  </span>
                </dd>
              {/if}
              <dt class="col-6">{$_('modals.refund.total')}</dt>
              <dd class="col-6 text-end fw-bold mb-0">{fmt.money(total, order?.currency)}</dd>
            </dl>

            {#if canOverride}
              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="refund-override"
                  bind:checked={form.override} />
                <label class="form-check-label" for="refund-override">
                  {$_('modals.refund.override')}
                </label>
              </div>
              {#if overriding}
                <div class="vstack gap-2">
                  <div>
                    <MoneyInput
                      bind:value={form.gateway}
                      currency={order?.currency ?? ''}
                      {exponent}
                      invalid={shown.gateway === true}
                      placeholder={$_('modals.refund.override-gateway', {
                        values: { max: fmt.money(remainingGateway(order), order?.currency) },
                      })} />
                    {#if shown.gateway}
                      <div class="invalid-feedback d-block">{$_('modals.refund.error.limit')}</div>
                    {/if}
                  </div>
                  <div>
                    <MoneyInput
                      bind:value={form.credits}
                      currency={ctx?.creditName ?? ''}
                      exponent={2}
                      invalid={shown.credits === true}
                      placeholder={$_('modals.refund.override-credits', {
                        values: { max: fmt.credits(remainingCredits(order), ctx?.creditName) },
                      })} />
                    {#if shown.credits}
                      <div class="invalid-feedback d-block">{$_('modals.refund.error.limit')}</div>
                    {/if}
                  </div>
                  {#if shown.total}
                    <div class="invalid-feedback d-block">
                      {$_('modals.refund.error.ZERO_TOTAL')}
                    </div>
                  {/if}
                </div>
              {/if}
            {/if}

            {#if requiresAck}
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="checkbox"
                  id="refund-ack"
                  bind:checked={form.ack} />
                <label class="form-check-label" for="refund-ack">
                  {$_('modals.refund.split-warning.ack')}
                </label>
              </div>
            {/if}

            {#if preview.recommendRevokeFirst === true}
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="checkbox"
                  id="refund-revoke-first"
                  bind:checked={form.revokeFirst} />
                <label class="form-check-label" for="refund-revoke-first">
                  {$_('modals.refund.revoke-first')}
                </label>
              </div>
            {/if}

            {#if hasUpgradeDependent}
              <div class="vstack gap-1">
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="radio"
                    name="refund-cascade"
                    id="refund-cascade-revoke"
                    value={true}
                    bind:group={form.cascade} />
                  <label class="form-check-label" for="refund-cascade-revoke">
                    {$_('modals.refund.cascade.revoke')}
                  </label>
                </div>
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="radio"
                    name="refund-cascade"
                    id="refund-cascade-keep"
                    value={false}
                    bind:group={form.cascade} />
                  <label class="form-check-label" for="refund-cascade-keep">
                    {$_('modals.refund.cascade.keep')}
                  </label>
                </div>
              </div>
            {/if}

            <div class="vstack gap-1">
              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="refund-revoke"
                  bind:checked={form.revoke} />
                <label class="form-check-label" for="refund-revoke">
                  {$_('modals.refund.revoke')}
                </label>
              </div>
              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="refund-restock"
                  bind:checked={form.restock} />
                <label class="form-check-label" for="refund-restock">
                  {$_('modals.refund.restock')}
                </label>
              </div>
              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  class:is-invalid={invalid.manual === true}
                  type="checkbox"
                  role="switch"
                  id="refund-manual"
                  checked={manual}
                  disabled={forcedManual}
                  onchange={(event) => {
                    form.manual = event.currentTarget.checked;
                    invalid = { ...invalid, manual: false };
                  }} />
                <label class="form-check-label" for="refund-manual">
                  {$_('modals.refund.manual')}
                </label>
                {#if invalid.manual}
                  <div class="invalid-feedback d-block">{$_('errors.REFUND_NOT_SUPPORTED')}</div>
                {/if}
              </div>
            </div>

            <div>
              <input
                class="form-control"
                class:is-invalid={shown.reason === true}
                type="text"
                autocomplete="off"
                maxlength={REFUND_REASON_MAX}
                placeholder={$_(manual ? 'modals.refund.reason-required' : 'modals.refund.reason')}
                bind:value={form.reason} />
              {#if shown.reason}
                <div class="invalid-feedback d-block">
                  {$_(
                    manual
                      ? 'modals.refund.error.reason-manual'
                      : 'modals.refund.error.reason-long',
                  )}
                </div>
              {/if}
            </div>
          {/if}
        </div>
        <div class="modal-footer">
          <button class="btn btn-danger w-100" type="submit" disabled={!canSubmit}>
            {#if status === 'SUBMITTING'}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {#if total !== null && total !== undefined}
              {$_('modals.refund.cta', { values: { total: fmt.money(total, order?.currency) } })}
            {:else}
              {$_('modals.refund.cta-plain')}
            {/if}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { untrack } from 'svelte';
  import { _ } from '../../../i18n';
  import { call, newIdempotency, resetIdempotency } from '../../utils/api.js';
  import { fmt } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import {
    PREVIEW_DEBOUNCE_MS,
    REFUND_REASON_MAX,
    amountError,
    buildRefundRequest,
    canOverrideSplit,
    canSubmitRefund,
    creditUnitValue,
    effectiveMax,
    initialForm,
    itemsPayload,
    latestWins,
    listedWarnings,
    manualForced,
    offeredModes,
    previewIsCurrent,
    previewPath,
    refundHeaders,
    refundOutcome,
    refundTotal,
    refundableItems,
    remainingCredits,
    remainingGateway,
    requiresSplitAck,
    splitWarningVisible,
    upgradeDependent,
  } from '../../utils/refund.js';
  import { fetchPath, hideModal, showModal } from '../order-detail/send.js';
  import LoadError from '../LoadError.svelte';
  import MoneyInput from '../MoneyInput.svelte';

  // detail: GET /orders/:id (order, items, allowed); ctx: GET /context. Contract of external.js:
  // onDone(toastKey) refreshes the order and toasts, onStale() refreshes after a stale-flag error.
  let {
    detail = null,
    ctx = null,
    user = null,
    onDone = async () => {},
    onStale = async () => {},
  } = $props();

  let modalElement = $state(null);

  // State machine of 13 §6.3: LOADING -> READY <-> PREVIEWING -> SUBMITTING -> (closed | READY)
  let status = $state('LOADING');
  let opened = $state(false);
  let form = $state(initialForm(null));
  let preview = $state.raw(null);
  let loadError = $state(null);
  let amountMax = $state(null); // INVALID_REFUND_AMOUNT: the server's maximum
  let invalid = $state({}); // server-side marks (manual switch, cascade ...)

  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);

  const gate = latestWins();
  const idempotency = newIdempotency();
  let lastPath = null;
  let previewFor = $state(null); // path the shown preview was fetched for
  let closing = $state(false); // the modal decided to close: nothing may be sent any more

  const order = $derived(detail?.order ?? null);
  const allowed = $derived(detail?.allowed ?? null);
  const refundable = $derived(refundableItems(detail?.items));
  const modes = $derived(offeredModes(allowed?.refundModes));
  const exponent = $derived(
    ctx?.currencies?.find?.((c) => c.code === order?.currency)?.exponent ?? 2,
  );
  const canOverride = $derived(canOverrideSplit(order));
  const overriding = $derived(form.override === true && canOverride);
  const forcedManual = $derived(manualForced(preview));
  const manual = $derived(forcedManual || form.manual === true);
  const splitVisible = $derived(splitWarningVisible(order, preview));
  const listed = $derived(listedWarnings(preview));
  const hasUpgradeDependent = $derived(upgradeDependent(preview));
  const requiresAck = $derived(requiresSplitAck(order, preview, form));
  const total = $derived(refundTotal(order, form, preview));
  const creditPart = $derived(Math.round(num(form.credits) * creditUnitValue(order) * 100) / 100);
  const built = $derived(buildRefundRequest(order ?? {}, form, preview, refundable, allowed));
  const errors = $derived(built.error ?? {});
  const previewCurrent = $derived(
    order ? previewIsCurrent(previewPath(order.id, form, refundable), previewFor, status) : false,
  );
  const canSubmit = $derived(
    canSubmitRefund({
      status,
      closing,
      previewCurrent,
      hasPreview: preview !== null,
      loadError,
      amountMax,
      valid: built.error === undefined,
    }),
  );

  // Marks are shown live for what the admin already typed; an untouched empty field stays quiet.
  const quantitiesEntered = $derived(
    Object.values(form.quantities).some((v) => v !== null && v !== undefined && v !== ''),
  );
  const itemsInvalid = $derived(itemsPayload(refundable, form.quantities).errors);
  const shown = $derived({
    amount: (errors.amount && form.amount !== null) || invalid.amount === true,
    items: errors.items && quantitiesEntered,
    gateway: errors.gateway === true,
    credits: errors.credits === true,
    total: errors.total && (form.gateway !== null || form.credits !== null),
    reason: errors.reason && (form.reason.trim() !== '' || manual),
  });
  const amountHint = $derived(
    amountError(form.amount, effectiveMax(allowed, preview)) === 'ABOVE_MAX'
      ? $_('modals.refund.error.ABOVE_MAX', {
          values: { max: fmt.money(effectiveMax(allowed, preview), order?.currency) },
        })
      : $_('modals.refund.error.INVALID'),
  );

  /** Opens the modal: reset the form and the idempotency state, then GET the preview. */
  export function open() {
    resetIdempotency(idempotency);
    form = initialForm(ctx);
    invalid = {};
    loadError = null;
    amountMax = null;
    preview = null;
    status = 'LOADING';
    lastPath = null;
    previewFor = null;
    closing = false;
    opened = true;
    if (order) loadPreview(previewPath(order.id, form, refundable));
    showModal(modalElement);
  }

  async function loadPreview(path) {
    if (!path) return;
    lastPath = path;
    const tag = gate.next();
    if (status !== 'SUBMITTING') status = preview ? 'PREVIEWING' : 'LOADING';
    const result = await fetchPath(path);
    if (!gate.isCurrent(tag)) return; // a newer request answered meanwhile
    if (status === 'PREVIEWING' || status === 'LOADING') status = 'READY';
    if (result.ok) {
      preview = result.body;
      previewFor = path;
      loadError = null;
      amountMax = null;
    } else if (result.error === 'INVALID_REFUND_AMOUNT') {
      loadError = null;
      amountMax = Number.isFinite(result.body?.max) ? result.body.max : 0;
    } else {
      loadError = result.error;
    }
  }

  function retryPreview() {
    loadError = null;
    if (order) loadPreview(previewPath(order.id, form, refundable));
  }

  // Every change of mode / amount / items re-fetches the preview, debounced 300 ms (13 §6.3 step 3).
  $effect(() => {
    if (!opened || !order) return;
    const path = previewPath(order.id, form, refundable);
    if (path === null || path === lastPath) return;
    const timer = setTimeout(() => loadPreview(path), PREVIEW_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  });

  function warningText(warning) {
    const money = (v) => fmt.money(num(v), order?.currency);
    const credits = (v) => fmt.credits(num(v), ctx?.creditName);
    const key = `modals.refund.warning.${warning.code}`;
    switch (warning.code) {
      case 'CASHBACK_REVERSAL':
      case 'CREDIT_CLAWBACK':
        return $_(key, {
          values: { amount: credits(warning.amount), shortfall: credits(warning.shortfall) },
        });
      case 'CREDIT_ACCOUNT_CLOSED':
        return $_(key, { values: { credits: credits(warning.forfeitedCredits) } });
      case 'UPGRADE_DEPENDENT':
        return $_(key, {
          values: {
            order: `#${warning.successorOrderId ?? ''}`,
            deduction: money(warning.deduction),
          },
        });
      case 'CREDIT_ONLY_REFUND':
      case 'GATEWAY_PARTIAL_REFUND_NOT_SUPPORTED':
      case 'OLDER_SUBSCRIPTION_PERIOD':
        return $_(key);
      default:
        return $_('modals.refund.warning.UNKNOWN');
    }
  }

  async function submit(event) {
    event.preventDefault();
    if (!canSubmit || !order) return;
    const request = buildRefundRequest(order, form, preview, refundable, allowed);
    if (request.error) return;
    status = 'SUBMITTING';
    const headers = refundHeaders(idempotency, request.request.body);
    let result;
    try {
      result = await call(
        api.panel.post({
          path: request.request.path,
          body: request.request.body,
          headers,
        }),
      );
    } finally {
      if (status === 'SUBMITTING') status = 'READY';
    }
    const outcome = refundOutcome(result);
    if (outcome.reset) resetIdempotency(idempotency);
    switch (outcome.kind) {
      case 'done':
        closing = true;
        hideModal(modalElement);
        await onDone(outcome.toast);
        break;
      case 'manual':
        form.manual = true;
        invalid = { ...invalid, manual: true };
        break;
      case 'providerError':
        closing = true;
        toastError($_, result);
        hideModal(modalElement);
        await onStale();
        break;
      case 'invalidAmount':
        amountMax = outcome.max ?? 0;
        invalid = { ...invalid, amount: true };
        break;
      case 'stale':
        closing = true;
        toastError($_, result);
        hideModal(modalElement);
        await onStale();
        break;
      default:
        toastError($_, result);
    }
  }

  // A changed amount clears the server's "most you can refund" hint until the next answer.
  $effect(() => {
    void form.amount;
    void form.mode;
    untrack(() => {
      amountMax = null;
      if (invalid.amount) invalid = { ...invalid, amount: false };
    });
  });

  $effect(() => {
    const el = modalElement;
    if (!el) return;
    const onHidden = () => {
      opened = false;
      gate.cancel();
    };
    el.addEventListener('hidden.bs.modal', onHidden);
    return () => {
      el.removeEventListener('hidden.bs.modal', onHidden);
      if (window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
