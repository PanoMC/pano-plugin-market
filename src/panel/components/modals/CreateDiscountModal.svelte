<div
  class="modal fade"
  id="createDiscountModal"
  tabindex="-1"
  aria-labelledby="createDiscountModalLabel"
  aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createDiscountModalLabel">
          {isEdit ? $_('modals.discount.title-edit') : $_('modals.discount.title-create')}
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
            <div class="d-flex flex-wrap gap-4">
              <div class="form-check form-switch m-0">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="discountStatusSwitch"
                  bind:checked={form.active} />
                <label class="form-check-label" for="discountStatusSwitch">
                  {$_('common.active')}
                </label>
              </div>
              <div class="form-check form-switch m-0">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="discountBadgeSwitch"
                  bind:checked={form.showBadge} />
                <label class="form-check-label" for="discountBadgeSwitch">
                  {$_('modals.discount.show-badge')}
                </label>
              </div>
            </div>

            <div>
              <div class="form-floating">
                <input
                  type="text"
                  class="form-control"
                  class:is-invalid={shown.name}
                  id="discountNameInput"
                  autocomplete="off"
                  placeholder={$_('modals.discount.name-label')}
                  bind:value={form.name} />
                <label for="discountNameInput">{$_('modals.discount.name-label')}</label>
              </div>
              <ErrorText code={shown.name} />
            </div>

            <UnitValueFields
              id="discount"
              label={$_('modals.discount.value-label')}
              bind:value={form.value}
              bind:unit={form.unit}
              currency={ctx?.currency ?? ''}
              error={shown.value} />

            <div>
              <div class="input-group">
                <div class="form-floating">
                  <input
                    type="text"
                    inputmode="decimal"
                    class="form-control"
                    class:is-invalid={shown.minPaymentAmount}
                    id="discountMinPaymentInput"
                    autocomplete="off"
                    placeholder={$_('modals.discount.min-payment')}
                    bind:value={form.minPaymentAmount} />
                  <label for="discountMinPaymentInput">{$_('modals.discount.min-payment')}</label>
                </div>
                <span class="input-group-text">{ctx?.currency ?? ''}</span>
              </div>
              <ErrorText code={shown.minPaymentAmount} />
            </div>

            <div class="vstack gap-2">
              <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3">
                {#each SCOPES as scope (scope)}
                  <div class="form-check m-0">
                    <input
                      class="form-check-input"
                      type="radio"
                      name="discountScope"
                      id="discountScope{scope}"
                      value={scope}
                      bind:group={form.scope} />
                    <label class="form-check-label" for="discountScope{scope}">
                      {$_(`modals.discount.scope-${scope.toLowerCase()}`)}
                    </label>
                  </div>
                {/each}
              </div>
              {#if form.scope === 'PRODUCTS'}
                <ProductSelector bind:selected={form.productIds} multiple={true} maxHeight="150px" />
                <ErrorText code={shown.productIds} />
              {:else if form.scope === 'CATEGORIES'}
                <CategoryChecklist bind:selected={form.categoryIds} error={shown.categoryIds} />
              {/if}
            </div>

            <ValidityFields
              id="discountValidity"
              label={$_('modals.discount-form.unlimited-dates')}
              bind:unlimited={form.unlimitedDates}
              bind:startDate={form.startDate}
              bind:expiryDate={form.expiryDate}
              error={shown.expiryDate} />

            <LimitField
              id="discountLimit"
              label={$_('modals.discount-form.unlimited-usage')}
              placeholder={$_('modals.discount.usage-limit')}
              bind:unlimited={form.unlimitedUsage}
              bind:value={form.usageLimit}
              error={shown.usageLimit} />
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
  import ApiUtil from '@panomc/sdk/utils/api';
  import ProductSelector from '../ProductSelector.svelte';
  import CategoryChecklist from '../discounts/CategoryChecklist.svelte';
  import ErrorText from '../discounts/ErrorText.svelte';
  import LimitField from '../discounts/LimitField.svelte';
  import UnitValueFields from '../discounts/UnitValueFields.svelte';
  import ValidityFields from '../discounts/ValidityFields.svelte';
  import { _, showSuccessToast } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import { buildDiscountBody, datesToForm, limitToForm, unitOf } from '../../utils/discounts.js';
  import { hideModal } from '../order-detail/send.js';
  import { toastError } from '../../utils/toast.js';

  const SCOPES = ['ALL', 'PRODUCTS', 'CATEGORIES'];

  let { isEdit = false, discount = null, ctx = null, onSaved = () => {} } = $props();

  const blank = () => ({
    name: '',
    value: '',
    unit: 'PERCENT',
    minPaymentAmount: '',
    scope: 'ALL',
    productIds: [],
    categoryIds: [],
    active: true,
    unlimitedDates: true,
    startDate: '',
    expiryDate: '',
    unlimitedUsage: true,
    usageLimit: '',
    showBadge: false,
  });

  let form = $state(blank());
  let submitted = $state(false);
  let saving = $state(false);

  const exponent = $derived(
    ctx?.currencies?.find((c) => c.code === ctx?.currency)?.exponent ?? 2,
  );
  const result = $derived(buildDiscountBody(form, exponent));
  const shown = $derived(submitted ? (result.errors ?? {}) : {});

  function initForm() {
    submitted = false;
    if (isEdit && discount) {
      const limit = limitToForm(discount.usageLimit);
      form = {
        ...blank(),
        name: discount.name ?? '',
        value: String(discount.value ?? ''),
        unit: unitOf(discount.unit),
        minPaymentAmount: discount.minPaymentAmount == null ? '' : String(discount.minPaymentAmount),
        scope: SCOPES.includes(discount.scope) ? discount.scope : 'ALL',
        productIds: Array.isArray(discount.productIds) ? [...discount.productIds] : [],
        categoryIds: Array.isArray(discount.categoryIds) ? [...discount.categoryIds] : [],
        active: (discount.status ?? 'ACTIVE') === 'ACTIVE',
        showBadge: discount.showBadge === true,
        ...datesToForm(discount),
        unlimitedUsage: limit.unlimited,
        usageLimit: limit.text,
      };
    } else if (!isEdit) {
      form = blank();
    }
  }

  // Re-init when the target discount changes, and on every open (create -> create keeps its props).
  $effect(initForm);
  $effect(() => {
    const el = document.getElementById('createDiscountModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    if (result.errors) return;

    saving = true;
    let response;
    try {
      response =
        isEdit && discount
          ? await call(ApiUtil.put({ path: marketPath(`/discounts/${discount.id}`), body: result.body }))
          : await call(ApiUtil.post({ path: marketPath('/discounts'), body: result.body }));
    } finally {
      saving = false;
    }
    if (!response.ok) {
      toastError($_, response);
      if (response.error === 'NOT_FOUND') {
        hideModal(document.getElementById('createDiscountModal'));
        onSaved();
      }
      return;
    }
    showSuccessToast(
      isEdit ? $_('modals.discount.toast-updated') : $_('modals.discount.toast-created'),
    );
    hideModal(document.getElementById('createDiscountModal'));
    onSaved();
  }
</script>
