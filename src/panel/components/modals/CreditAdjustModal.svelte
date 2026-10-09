<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {currentMode === 'revoke'
            ? $_('modals.credit-adjust.title-revoke')
            : $_('modals.credit-adjust.title-grant')}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          {#if currentAccount}
            <div class="d-flex align-items-center justify-content-between">
              <PlayerCell username={currentAccount.username} link={false} />
              <span class="text-body-secondary">
                {$_('modals.credit-adjust.balance', {
                  values: {
                    balance: fmt.credits(currentAccount.balance ?? 0, ctx?.creditName ?? ''),
                  },
                })}
              </span>
            </div>
          {:else}
            <div>
              <input
                id="credit-adjust-username"
                type="text"
                class="form-control"
                class:is-invalid={lookup.status === 'UNKNOWN' || shown.user}
                autocomplete="off"
                maxlength="64"
                placeholder={$_('modals.credit-adjust.username')}
                bind:value={username}
                oninput={() => (lookup = { status: 'IDLE', userId: null, balance: null })}
                onblur={resolveUser}
                onkeydown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    resolveUser();
                  }
                }} />
              {#if lookup.status === 'UNKNOWN' || shown.user}
                <div class="invalid-feedback d-block">
                  {$_('modals.credit-adjust.unknown-player')}
                </div>
              {:else if lookup.status === 'FOUND'}
                <div class="form-text">
                  {$_('modals.credit-adjust.balance', {
                    values: { balance: fmt.credits(lookup.balance ?? 0, ctx?.creditName ?? '') },
                  })}
                </div>
              {:else if lookup.status === 'LOADING'}
                <div class="form-text">{$_('common.loading')}</div>
              {/if}
            </div>
          {/if}

          <div>
            <input
              id="credit-adjust-amount"
              type="text"
              inputmode="decimal"
              class="form-control"
              class:is-invalid={shown.amount}
              autocomplete="off"
              placeholder={$_('modals.credit-adjust.amount')}
              bind:value={form.amount} />
            {#if shown.amount}
              <div class="invalid-feedback d-block">
                {$_(`modals.credit-adjust.error.amount-${shown.amount}`)}
              </div>
            {/if}
          </div>

          <div>
            <textarea
              id="credit-adjust-note"
              class="form-control"
              class:is-invalid={shown.note}
              style="height: 90px"
              maxlength={NOTE_MAX + 100}
              placeholder={$_('modals.credit-adjust.note')}
              bind:value={form.note}></textarea>
            {#if shown.note}
              <div class="invalid-feedback d-block">
                {$_(`modals.credit-adjust.error.note-${shown.note}`)}
              </div>
            {:else}
              <div class="form-text">{$_('modals.credit-adjust.note-help')}</div>
            {/if}
          </div>
        </div>
        <div class="modal-footer">
          <button
            type="submit"
            class="btn w-100"
            class:btn-primary={currentMode !== 'revoke'}
            class:btn-danger={currentMode === 'revoke'}
            disabled={saving || closing}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {currentMode === 'revoke'
              ? $_('modals.credit-adjust.submit-revoke')
              : $_('modals.credit-adjust.submit-grant')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { showToast } from '@panomc/sdk/toasts';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import { call, errorKey, newIdempotency, resetIdempotency } from '../../utils/api.js';
  import {
    NOTE_MAX,
    adjustOutcome,
    buildAdjustRequest,
    validateAdjust,
  } from '../../utils/credits.js';
  import { fmt } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModalThen, showModal } from '../order-detail/send.js';
  import { submitLocked } from '../order-detail/hide-then.js';
  import PlayerCell from '../PlayerCell.svelte';

  // mode: 'grant' | 'revoke'; account: { userId, username, balance } or null (lookup mode).
  // open({ mode, account }) overrides the props for one opening (rows of a list pick both).
  let { mode = 'grant', account = null, ctx = null, onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let currentMode = $state('grant');
  let currentAccount = $state(null);
  let username = $state('');
  let lookup = $state({ status: 'IDLE', userId: null, balance: null });
  let form = $state({ amount: '', note: '' });
  let touched = $state(false);
  let amountRejected = $state(false);
  let saving = $state(false);
  // set from the successful response until open(): the form stays locked while the modal fades out
  let closing = $state(false);
  let lookupTag = 0;

  // One idempotency state per opening: same body => same key, changed body => a new one.
  const idempotency = newIdempotency();

  const userId = $derived(currentAccount ? currentAccount.userId : lookup.userId);
  const errors = $derived(
    validateAdjust({ mode: currentMode, userId, amount: form.amount, note: form.note }).error ?? {},
  );
  const shown = $derived({
    user: touched && errors.user && !currentAccount && lookup.status !== 'LOADING' ? true : false,
    amount: amountRejected ? 'INVALID' : touched ? (errors.amount ?? null) : null,
    note: touched ? (errors.note ?? null) : null,
  });

  export function open(options = {}) {
    currentMode = (options.mode ?? mode) === 'revoke' ? 'revoke' : 'grant';
    currentAccount = options.account === undefined ? account : options.account;
    username = '';
    lookup = { status: 'IDLE', userId: null, balance: null };
    form = { amount: '', note: '' };
    touched = false;
    amountRejected = false;
    saving = false;
    closing = false;
    resetIdempotency(idempotency);
    showModal(modalElement);
  }

  async function resolveUser() {
    const name = username.trim();
    if (!name) {
      lookup = { status: 'IDLE', userId: null, balance: null };
      return;
    }
    const tag = ++lookupTag;
    lookup = { status: 'LOADING', userId: null, balance: null };
    const result = await call(
      api.panel.get({ path: `/players/${encodeURIComponent(name)}/summary` }),
    );
    if (tag !== lookupTag) return;
    if (!result.ok) {
      lookup = { status: 'IDLE', userId: null, balance: null };
      toastError($_, result);
      return;
    }
    const user = result.body.user;
    lookup = user
      ? { status: 'FOUND', userId: user.id, balance: result.body.creditBalance ?? 0 }
      : { status: 'UNKNOWN', userId: null, balance: null };
  }

  async function submit(event) {
    event.preventDefault();
    if (submitLocked({ saving, closing })) return;
    touched = true;
    amountRejected = false;
    if (!currentAccount && lookup.status === 'IDLE' && username.trim()) await resolveUser();
    if (!currentAccount && lookup.status === 'LOADING') return;

    const request = buildAdjustRequest(
      { mode: currentMode, userId, amount: form.amount, note: form.note },
      idempotency,
    );
    if (request.error) return;

    saving = true;
    let result;
    try {
      result = await call(
        api.panel.post({
          path: request.path,
          body: request.body,
          headers: request.headers,
        }),
      );
    } finally {
      saving = false;
    }

    const outcome = adjustOutcome(currentMode, request.body.amount, result);
    if (outcome.reset) resetIdempotency(idempotency);

    if (outcome.kind === 'invalidAmount') {
      amountRejected = true;
      showErrorToast($_(errorKey(result.error)));
      return;
    }
    if (outcome.kind === 'error') {
      toastError($_, result);
      return;
    }

    if (outcome.kind === 'done') showSuccessToast($_(`modals.credit-adjust.${outcome.toast}`));
    else if (outcome.kind === 'nothing')
      showToast($_('modals.credit-adjust.toast-nothing'), {}, undefined, { variant: 'warning' });
    else
      showToast(
        $_('modals.credit-adjust.toast-shortfall', {
          values: {
            taken: fmt.credits(outcome.taken, ctx?.creditName ?? ''),
            shortfall: fmt.credits(outcome.shortfall, ctx?.creditName ?? ''),
          },
        }),
        {},
        undefined,
        { variant: 'warning' },
      );

    // the page refresh behind onSaved remounts the page: it waits until the modal is really gone (hide-then.js)
    const saved = result.body;
    closing = true;
    hideModalThen(modalElement, () => onSaved(saved));
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
