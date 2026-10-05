<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.payout.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          {#if creator}
            <div class="d-flex flex-wrap align-items-center justify-content-between gap-2">
              <PlayerCell username={creator.creator} link={false} />
              <span class="text-body-secondary">
                {$_('modals.payout.available', {
                  values: { amount: fmt.money(available, currency) },
                })}
              </span>
            </div>
          {/if}

          <div>
            <div class="input-group">
              <input
                id="payout-amount"
                type="text"
                inputmode="decimal"
                class="form-control"
                class:is-invalid={shown.amount}
                autocomplete="off"
                placeholder={$_('modals.payout.amount')}
                aria-label={$_('modals.payout.amount')}
                bind:value={form.amount} />
              <span class="input-group-text">{currency}</span>
            </div>
            {#if shown.amount}
              <div class="invalid-feedback d-block">
                {$_(`modals.payout.error.${shown.amount}`)}
              </div>
            {/if}
          </div>

          <div>
            <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-4">
              {#each PAYOUT_METHODS as method (method)}
                <div class="form-check m-0">
                  <input
                    class="form-check-input"
                    class:is-invalid={method === 'CREDIT' && noAccount}
                    type="radio"
                    name="payoutMethod"
                    id="payout-method-{method}"
                    value={method}
                    bind:group={form.method} />
                  <label class="form-check-label" for="payout-method-{method}">
                    {$_(`enums.payout-method.${method}`)}
                  </label>
                </div>
              {/each}
            </div>
            {#if noAccount}
              <div class="invalid-feedback d-block">{$_('errors.CREATOR_HAS_NO_ACCOUNT')}</div>
            {/if}
            {#if form.method === 'CREDIT'}
              <div class="form-text">
                {#if credits === null}
                  {$_('modals.payout.credits-unknown')}
                {:else}
                  {$_('modals.payout.credits', {
                    values: { credits: fmt.credits(credits, ctx?.creditName ?? '') },
                  })}
                {/if}
              </div>
            {/if}
          </div>

          {#if form.method === 'ACTION'}
            <div class="vstack gap-3">
              {#each form.actions as action, index (index)}
                <ActionEditor
                  bind:action={form.actions[index]}
                  {servers}
                  phases={['GRANT']}
                  types={PAYOUT_ACTION_TYPES}
                  variables={PAYOUT_VARIABLES}
                  showPhase={false}
                  errors={errorsOf(index)}
                  path="actions.{index}"
                  disabled={actionLocked(action, user)}
                  onRemove={() => removeAction(index)} />
              {/each}
              {#if shown.actions}
                <div class="invalid-feedback d-block">{$_(`modals.payout.error.${shown.actions}`)}</div>
              {/if}
              <div class="d-flex flex-wrap gap-2">
                {#each addable as type (type)}
                  <button
                    type="button"
                    class="btn btn-outline-secondary btn-sm"
                    onclick={() => addAction(type)}>
                    <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
                    {$_(`enums.action-type.${type}`)}
                  </button>
                {/each}
              </div>
            </div>
          {/if}

          <div>
            <textarea
              id="payout-note"
              class="form-control"
              class:is-invalid={shown.note}
              style="height: 80px"
              placeholder={$_('modals.payout.note')}
              aria-label={$_('modals.payout.note')}
              bind:value={form.note}></textarea>
            <ErrorText code={shown.note} />
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {$_('modals.payout.submit')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { page } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../../i18n';
  import { actionLocked, addableActionTypes, newAction } from '../../utils/actions.js';
  import { call, marketPath, newIdempotency, resetIdempotency } from '../../utils/api.js';
  import {
    PAYOUT_ACTION_TYPES,
    PAYOUT_METHODS,
    PAYOUT_VARIABLES,
    buildPayoutBody,
    buildPayoutRequest,
    payoutCredits,
    payoutFailure,
  } from '../../utils/discounts.js';
  import { fmt } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import ActionEditor from '../ActionEditor.svelte';
  import ErrorText from '../discounts/ErrorText.svelte';
  import { hideModal, showModal } from '../order-detail/send.js';
  import PlayerCell from '../PlayerCell.svelte';

  // open({ creator: { id, creator, code, available }, currency }) for one creator code row of the report.
  let { ctx = null, onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let creator = $state(null);
  let currency = $state('');
  let availableNow = $state(0);
  let form = $state({ amount: '', method: 'MANUAL', note: '', actions: [] });
  let submitted = $state(false);
  let saving = $state(false);
  let amountRejected = $state(false);
  let noAccount = $state(false);
  let servers = $state.raw(null);

  // One idempotency state per opening: same body => same key, changed body => a new one.
  const idempotency = newIdempotency();

  const user = $derived($page.data?.user);
  const available = $derived(availableNow);
  const exponent = $derived(ctx?.currencies?.find((c) => c.code === currency)?.exponent ?? 2);
  const addable = $derived(addableActionTypes(user).filter((t) => PAYOUT_ACTION_TYPES.includes(t)));
  const credits = $derived(payoutCredits(parseAmount(form.amount), ctx?.creditValue));
  const built = $derived(buildPayoutBody(form, { available, servers, exponent }));
  const errors = $derived(built.errors ?? {});
  const shown = $derived({
    amount: amountRejected ? 'EXCEEDS_AVAILABLE' : submitted ? (errors.amount ?? null) : null,
    note: submitted ? (errors.note ?? null) : null,
    actions: submitted ? (errors.actions ?? null) : null,
  });

  function parseAmount(text) {
    const n = Number(String(text ?? '').replace(',', '.'));
    return Number.isFinite(n) ? n : NaN;
  }

  // Errors of one action with the `actions.<i>.` prefix removed (ActionEditor keys are relative).
  function errorsOf(index) {
    if (!submitted) return {};
    const prefix = `actions.${index}.`;
    const out = {};
    for (const [path, code] of Object.entries(errors)) {
      if (path.startsWith(prefix)) out[path.slice(prefix.length)] = code;
    }
    return out;
  }

  async function loadServers() {
    if (servers !== null) return;
    const result = await call(ApiUtil.get({ path: marketPath('/servers') }));
    servers = result.ok ? (result.body.servers ?? []) : null;
  }

  $effect(() => {
    if (form.method === 'ACTION') loadServers();
  });

  function addAction(type) {
    const action = newAction(type, { phase: 'GRANT' });
    if (type === 'COMMAND' && Array.isArray(servers) && servers.length === 1)
      action.targetServers = [servers[0].id];
    form.actions.push(action);
  }

  function removeAction(index) {
    form.actions = form.actions.filter((_row, i) => i !== index);
  }

  export function open(options) {
    creator = options.creator;
    currency = options.currency ?? ctx?.currency ?? '';
    availableNow = Number(options.creator?.available) || 0;
    form = {
      amount: availableNow > 0 ? String(availableNow) : '',
      method: 'MANUAL',
      note: '',
      actions: [],
    };
    submitted = false;
    saving = false;
    amountRejected = false;
    noAccount = false;
    resetIdempotency(idempotency);
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    amountRejected = false;
    noAccount = false;

    const request = buildPayoutRequest(
      creator?.id,
      form,
      { available, servers, exponent },
      idempotency,
    );
    if (request.errors) return;

    saving = true;
    let result;
    try {
      result = await call(
        ApiUtil.post({ path: marketPath(request.path), body: request.body, headers: request.headers }),
      );
    } finally {
      saving = false;
    }

    if (!result.ok) {
      const failure = payoutFailure(result);
      if (failure.reset) resetIdempotency(idempotency);
      if (failure.field === 'amount') {
        amountRejected = true;
        if (failure.available !== null) availableNow = failure.available;
      } else if (failure.field === 'method') {
        noAccount = true;
      }
      toastError($_, result);
      return;
    }

    resetIdempotency(idempotency);
    showSuccessToast($_('modals.payout.toast-paid'));
    hideModal(modalElement);
    onSaved(result.body);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
