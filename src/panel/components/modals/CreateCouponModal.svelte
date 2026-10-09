<div
  class="modal fade"
  id="createCouponModal"
  tabindex="-1"
  aria-labelledby="createCouponModalLabel"
  aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createCouponModalLabel">
          {isEdit ? $_('modals.coupon.edit-heading') : $_('modals.coupon.create-heading')}
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
                id="couponStatusSwitch"
                bind:checked={form.active} />
              <label class="form-check-label" for="couponStatusSwitch">{$_('common.active')}</label>
            </div>

            <div>
              <div class="form-floating">
                <input
                  type="text"
                  class="form-control"
                  class:is-invalid={shown.name}
                  id="couponNameInput"
                  autocomplete="off"
                  placeholder={$_('modals.coupon.name')}
                  bind:value={form.name} />
                <label for="couponNameInput">{$_('modals.coupon.name')}</label>
              </div>
              <ErrorText code={shown.name} />
            </div>

            <div>
              <div class="input-group">
                <div class="form-floating flex-grow-1">
                  <input
                    type="text"
                    class="form-control"
                    class:is-invalid={shown.code}
                    id="couponCodeInput"
                    autocomplete="off"
                    placeholder={$_('modals.coupon.code')}
                    bind:value={form.code} />
                  <label for="couponCodeInput">{$_('modals.coupon.code')}</label>
                </div>
                <button
                  class="btn btn-primary"
                  type="button"
                  title={$_('modals.coupon.generate-code')}
                  aria-label={$_('modals.coupon.generate-code')}
                  onclick={() => (form.code = generateCode())}>
                  <i class="fas fa-wand-magic-sparkles" aria-hidden="true"></i>
                </button>
              </div>
              <ErrorText code={shown.code} />
            </div>

            <UnitValueFields
              id="coupon"
              label={$_('modals.coupon.discount-value')}
              bind:value={form.discount}
              bind:unit={form.unit}
              currency={ctx?.currency ?? ''}
              error={shown.discount} />

            <div>
              <div class="input-group">
                <div class="form-floating">
                  <input
                    type="text"
                    inputmode="decimal"
                    class="form-control"
                    class:is-invalid={shown.minPaymentAmount}
                    id="couponMinInput"
                    autocomplete="off"
                    placeholder={$_('modals.coupon.min-cart-amount')}
                    bind:value={form.minPaymentAmount} />
                  <label for="couponMinInput">{$_('modals.coupon.min-cart-amount')}</label>
                </div>
                <span class="input-group-text">{ctx?.currency ?? ''}</span>
              </div>
              <ErrorText code={shown.minPaymentAmount} />
            </div>

            <div class="vstack gap-2">
              <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3">
                <div class="form-check m-0">
                  <input
                    class="form-check-input"
                    type="radio"
                    name="couponScope"
                    id="couponScopeAll"
                    value="ALL"
                    bind:group={form.scope} />
                  <label class="form-check-label" for="couponScopeAll">
                    {$_('modals.coupon.all-products')}
                  </label>
                </div>
                <div class="form-check m-0">
                  <input
                    class="form-check-input"
                    type="radio"
                    name="couponScope"
                    id="couponScopeSelected"
                    value="SELECTED"
                    bind:group={form.scope} />
                  <label class="form-check-label" for="couponScopeSelected">
                    {$_('modals.coupon.selected-products')}
                  </label>
                </div>
              </div>
              {#if form.scope === 'SELECTED'}
                <ProductSelector bind:selected={form.productIds} multiple={true} maxHeight="150px" />
                <CategoryChecklist bind:selected={form.categoryIds} error={shown.productIds} />
              {/if}
            </div>

            <ValidityFields
              id="couponValidity"
              label={$_('modals.discount-form.unlimited-dates')}
              bind:unlimited={form.unlimitedDates}
              bind:startDate={form.startDate}
              bind:expiryDate={form.expiryDate}
              error={shown.expiryDate} />

            <LimitField
              id="couponRedeem"
              label={$_('modals.discount-form.unlimited-usage')}
              placeholder={$_('modals.coupon.usage-limit')}
              bind:unlimited={form.unlimitedRedeem}
              bind:value={form.redeemLimit}
              error={shown.redeemLimit} />

            <LimitField
              id="couponCustomer"
              label={$_('modals.coupon.per-customer-unlimited')}
              placeholder={$_('modals.coupon.per-customer-limit')}
              bind:unlimited={form.unlimitedCustomerRedeem}
              bind:value={form.customerRedeemLimit}
              error={shown.customerRedeemLimit} />
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
  import ProductSelector from '../ProductSelector.svelte';
  import CategoryChecklist from '../discounts/CategoryChecklist.svelte';
  import ErrorText from '../discounts/ErrorText.svelte';
  import LimitField from '../discounts/LimitField.svelte';
  import UnitValueFields from '../discounts/UnitValueFields.svelte';
  import ValidityFields from '../discounts/ValidityFields.svelte';
  import { _, showSuccessToast } from '../../../i18n';
  import { call } from '../../utils/api.js';
  import {
    buildCouponBody,
    datesToForm,
    generateCode,
    limitToForm,
    unitOf,
  } from '../../utils/discounts.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal } from '../order-detail/send.js';

  let { isEdit = false, coupon = null, ctx = null, onSaved = () => {} } = $props();

  const blank = () => ({
    name: '',
    code: '',
    discount: '',
    unit: 'PERCENT',
    minPaymentAmount: '',
    scope: 'ALL',
    productIds: [],
    categoryIds: [],
    active: true,
    unlimitedDates: true,
    startDate: '',
    expiryDate: '',
    unlimitedRedeem: true,
    redeemLimit: '',
    unlimitedCustomerRedeem: true,
    customerRedeemLimit: '',
  });

  let form = $state(blank());
  let submitted = $state(false);
  let saving = $state(false);
  // A code already taken marks the code field (the server is the only one that knows).
  let codeTaken = $state(false);

  const exponent = $derived(
    ctx?.currencies?.find((c) => c.code === ctx?.currency)?.exponent ?? 2,
  );
  const result = $derived(buildCouponBody(form, exponent));
  const shown = $derived({
    ...(submitted ? (result.errors ?? {}) : {}),
    ...(codeTaken ? { code: 'TAKEN' } : {}),
  });

  function initForm() {
    submitted = false;
    codeTaken = false;
    if (isEdit && coupon) {
      const redeem = limitToForm(coupon.redeemLimit);
      const customer = limitToForm(coupon.customerRedeemLimit);
      form = {
        ...blank(),
        name: coupon.name ?? '',
        code: coupon.code ?? '',
        discount: String(coupon.discount ?? ''),
        unit: unitOf(coupon.unit),
        minPaymentAmount: coupon.minPaymentAmount == null ? '' : String(coupon.minPaymentAmount),
        scope: coupon.scope === 'SELECTED' ? 'SELECTED' : 'ALL',
        productIds: Array.isArray(coupon.productIds) ? [...coupon.productIds] : [],
        categoryIds: Array.isArray(coupon.categoryIds) ? [...coupon.categoryIds] : [],
        active: (coupon.status ?? 'ACTIVE') === 'ACTIVE',
        ...datesToForm(coupon),
        unlimitedRedeem: redeem.unlimited,
        redeemLimit: redeem.text,
        unlimitedCustomerRedeem: customer.unlimited,
        customerRedeemLimit: customer.text,
      };
    } else if (!isEdit) {
      form = blank();
    }
  }

  $effect(initForm);
  $effect(() => {
    const el = document.getElementById('createCouponModal');
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
        isEdit && coupon
          ? await call(api.panel.put({ path: `/coupons/${coupon.id}`, body: result.body }))
          : await call(api.panel.post({ path: '/coupons', body: result.body }));
    } finally {
      saving = false;
    }
    if (!response.ok) {
      if (response.error === 'CODE_ALREADY_EXISTS') {
        codeTaken = true;
        toastError($_, response);
        return;
      }
      toastError($_, response);
      if (response.error === 'NOT_FOUND') {
        hideModal(document.getElementById('createCouponModal'));
        onSaved();
      }
      return;
    }
    showSuccessToast(isEdit ? $_('modals.coupon.toast-updated') : $_('modals.coupon.toast-created'));
    hideModal(document.getElementById('createCouponModal'));
    onSaved();
  }
</script>
