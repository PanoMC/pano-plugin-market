<div
  class="modal fade"
  id="createCreatorCodeModal"
  tabindex="-1"
  aria-labelledby="createCreatorCodeModalLabel"
  aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createCreatorCodeModalLabel">
          {isEdit ? $_('modals.creator-code.heading-edit') : $_('modals.creator-code.heading-create')}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body">
          <div class="vstack gap-3">
            <div class="form-check form-switch m-0">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="creatorStatusSwitch"
                bind:checked={form.active} />
              <label class="form-check-label" for="creatorStatusSwitch">
                {$_('common.active')}
              </label>
            </div>

            <div>
              <div class="form-floating">
                <input
                  type="text"
                  class="form-control"
                  class:is-invalid={shown.creator}
                  id="creatorNameInput"
                  autocomplete="off"
                  placeholder={$_('modals.creator-code.creator-label')}
                  bind:value={form.creator}
                  onblur={lookupCreator}
                  onkeydown={(e) => {
                    if (e.key === 'Enter') lookupCreator();
                  }} />
                <label for="creatorNameInput">{$_('modals.creator-code.creator-label')}</label>
              </div>
              <ErrorText code={shown.creator} />
              {#if lookup === 'FOUND'}
                <div class="form-text">{$_('modals.creator-code.account-found')}</div>
              {:else if lookup === 'UNKNOWN'}
                <div class="form-text">{$_('modals.creator-code.account-missing')}</div>
              {/if}
            </div>

            <div>
              <div class="form-floating">
                <input
                  type="text"
                  inputmode="decimal"
                  class="form-control"
                  class:is-invalid={shown.commission}
                  id="creatorCommissionInput"
                  autocomplete="off"
                  placeholder={$_('modals.creator-code.commission-label')}
                  bind:value={form.commission} />
                <label for="creatorCommissionInput">
                  {$_('modals.creator-code.commission-label')}
                </label>
              </div>
              <ErrorText code={shown.commission} />
            </div>

            <div>
              <div class="input-group">
                <div class="form-floating flex-grow-1">
                  <input
                    type="text"
                    class="form-control"
                    class:is-invalid={shown.code}
                    id="creatorCodeInput"
                    autocomplete="off"
                    placeholder={$_('modals.creator-code.code-label')}
                    bind:value={form.code} />
                  <label for="creatorCodeInput">{$_('modals.creator-code.code-label')}</label>
                </div>
                <button
                  class="btn btn-primary"
                  type="button"
                  title={$_('modals.creator-code.generate-title')}
                  aria-label={$_('modals.creator-code.generate-title')}
                  onclick={() => (form.code = generateCode())}>
                  <i class="fas fa-wand-magic-sparkles" aria-hidden="true"></i>
                </button>
              </div>
              <ErrorText code={shown.code} />
            </div>

            <UnitValueFields
              id="creator"
              label={$_('modals.creator-code.discount-label')}
              bind:value={form.discount}
              bind:unit={form.unit}
              currency={ctx?.currency ?? ''}
              error={shown.discount} />

            <ValidityFields
              id="creatorValidity"
              label={$_('modals.discount-form.unlimited-dates')}
              bind:unlimited={form.unlimitedDates}
              bind:startDate={form.startDate}
              bind:expiryDate={form.expiryDate}
              error={shown.expiryDate} />

            <LimitField
              id="creatorLimit"
              label={$_('modals.discount-form.unlimited-usage')}
              placeholder={$_('modals.creator-code.usage-limit')}
              bind:unlimited={form.unlimitedRedeem}
              bind:value={form.redeemLimit}
              error={shown.redeemLimit} />
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {isEdit ? $_('common.save') : $_('common.create')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { page } from '@panomc/sdk/svelte';
  import ErrorText from '../discounts/ErrorText.svelte';
  import LimitField from '../discounts/LimitField.svelte';
  import UnitValueFields from '../discounts/UnitValueFields.svelte';
  import ValidityFields from '../discounts/ValidityFields.svelte';
  import { _, showSuccessToast } from '../../../i18n';
  import { call } from '../../utils/api.js';
  import {
    buildCreatorBody,
    datesToForm,
    generateCode,
    limitToForm,
    unitOf,
  } from '../../utils/discounts.js';
  import { can } from '../../utils/permissions.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal } from '../order-detail/send.js';

  let { isEdit = false, creatorCode = null, ctx = null, onSaved = () => {} } = $props();

  const blank = () => ({
    creator: '',
    code: '',
    discount: '',
    unit: 'PERCENT',
    commission: '',
    active: true,
    unlimitedDates: true,
    startDate: '',
    expiryDate: '',
    unlimitedRedeem: true,
    redeemLimit: '',
  });

  let form = $state(blank());
  let submitted = $state(false);
  let saving = $state(false);
  let codeTaken = $state(false);
  // 'IDLE' | 'LOADING' | 'FOUND' | 'UNKNOWN': whether the creator has a Pano account (13 §12.1).
  let lookup = $state('IDLE');
  let lookupTag = 0;

  const user = $derived($page.data?.user);
  const exponent = $derived(
    ctx?.currencies?.find((c) => c.code === ctx?.currency)?.exponent ?? 2,
  );
  const result = $derived(buildCreatorBody(form, exponent));
  const shown = $derived({
    ...(submitted ? (result.errors ?? {}) : {}),
    ...(codeTaken ? { code: 'TAKEN' } : {}),
  });

  // The summary needs OV or PAY; without either the hint is simply not shown. No creatorUserId is sent.
  async function lookupCreator() {
    const name = form.creator.trim();
    if (!name || !can(user, 'OV', 'PAY')) {
      lookup = 'IDLE';
      return;
    }
    const tag = ++lookupTag;
    lookup = 'LOADING';
    const response = await call(
      api.panel.get({ path: `/players/${encodeURIComponent(name)}/summary` }),
    );
    if (tag !== lookupTag) return;
    if (!response.ok) {
      lookup = 'IDLE';
      return;
    }
    lookup = response.body.user ? 'FOUND' : 'UNKNOWN';
  }

  function initForm() {
    submitted = false;
    codeTaken = false;
    lookup = 'IDLE';
    if (isEdit && creatorCode) {
      const redeem = limitToForm(creatorCode.redeemLimit);
      form = {
        ...blank(),
        creator: creatorCode.creator ?? '',
        code: creatorCode.code ?? '',
        discount: String(creatorCode.discount ?? ''),
        unit: unitOf(creatorCode.unit),
        commission: creatorCode.commissionPercent == null ? '' : String(creatorCode.commissionPercent),
        active: (creatorCode.status ?? 'ACTIVE') === 'ACTIVE',
        ...datesToForm(creatorCode),
        unlimitedRedeem: redeem.unlimited,
        redeemLimit: redeem.text,
      };
    } else if (!isEdit) {
      form = blank();
    }
  }

  $effect(initForm);
  $effect(() => {
    const el = document.getElementById('createCreatorCodeModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    codeTaken = false;
    if (result.errors) return;

    saving = true;
    let response;
    try {
      response =
        isEdit && creatorCode
          ? await call(
              api.panel.put({
                path: `/creator-codes/${creatorCode.id}`,
                body: result.body,
              }),
            )
          : await call(api.panel.post({ path: '/creator-codes', body: result.body }));
    } finally {
      saving = false;
    }
    if (!response.ok) {
      if (response.error === 'CODE_ALREADY_EXISTS') codeTaken = true;
      toastError($_, response);
      if (response.error === 'NOT_FOUND') {
        hideModal(document.getElementById('createCreatorCodeModal'));
        onSaved();
      }
      return;
    }
    showSuccessToast(
      isEdit ? $_('modals.creator-code.toast-updated') : $_('modals.creator-code.toast-created'),
    );
    hideModal(document.getElementById('createCreatorCodeModal'));
    onSaved();
  }
</script>
