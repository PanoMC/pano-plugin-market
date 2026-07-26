<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import ProductSelector from '../ProductSelector.svelte';
  import { _, showSuccessToast, showErrorToast } from '../../../i18n';

  let { isEdit = false, coupon = null, currencySymbol = '', onSaved = () => {} } = $props();

  let couponCode = $state('');
  let couponName = $state('');
  let productSelection = $state('all'); // 'all' or 'selected'

  let isExpiryUnlimited = $state(true);
  let startDate = $state('');
  let expiryDate = $state('');

  let isRedeemUnlimited = $state(true);
  let redeemLimit = $state('');

  let isCustomerRedeemUnlimited = $state(true);
  let customerRedeemLimit = $state('');

  let discount = $state('');
  let discountUnit = $state('%');
  let minPaymentAmount = $state('');
  let status = $state('active'); // 'active' or 'inactive'

  let selectedProducts = $state([]);
  let loading = $state(false);

  function generateCouponCode() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let result = '';
    for (let i = 0; i < 8; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    couponCode = result;
  }

  // Epoch millis -> date-input (YYYY-MM-DD) in local time, matching how the
  // value is reparsed (as local midnight) on save.
  function toDateInput(epoch) {
    try {
      const d = new Date(epoch);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    } catch {
      return '';
    }
  }

  // date-input (YYYY-MM-DD) -> epoch millis at local midnight.
  function dateInputToEpoch(value) {
    const [year, month, day] = value.split('-').map(Number);
    return new Date(year, month - 1, day).getTime();
  }

  function resetForm() {
    couponCode = '';
    couponName = '';
    productSelection = 'all';
    isExpiryUnlimited = true;
    startDate = '';
    expiryDate = '';
    isRedeemUnlimited = true;
    redeemLimit = '';
    isCustomerRedeemUnlimited = true;
    customerRedeemLimit = '';
    discount = '';
    discountUnit = '%';
    minPaymentAmount = '';
    status = 'active';
    selectedProducts = [];
  }

  function initForm() {
    if (isEdit && coupon) {
      couponName = coupon.name || '';
      couponCode = coupon.code || '';
      discount = coupon.discount ?? '';
      discountUnit = coupon.unit === 'FIXED' ? '₺' : '%';
      minPaymentAmount = coupon.minPaymentAmount ?? '';
      productSelection = coupon.scope === 'SELECTED' ? 'selected' : 'all';
      selectedProducts = Array.isArray(coupon.productIds) ? [...coupon.productIds] : [];
      status = (coupon.status || 'ACTIVE') === 'ACTIVE' ? 'active' : 'inactive';

      if (coupon.startDate || coupon.expiryDate) {
        isExpiryUnlimited = false;
        startDate = coupon.startDate ? toDateInput(coupon.startDate) : '';
        expiryDate = coupon.expiryDate ? toDateInput(coupon.expiryDate) : '';
      } else {
        isExpiryUnlimited = true;
        startDate = '';
        expiryDate = '';
      }

      if (coupon.redeemLimit != null) {
        isRedeemUnlimited = false;
        redeemLimit = coupon.redeemLimit;
      } else {
        isRedeemUnlimited = true;
        redeemLimit = '';
      }

      if (coupon.customerRedeemLimit != null) {
        isCustomerRedeemUnlimited = false;
        customerRedeemLimit = coupon.customerRedeemLimit;
      } else {
        isCustomerRedeemUnlimited = true;
        customerRedeemLimit = '';
      }
    } else if (!isEdit) {
      resetForm();
    }
  }

  // Re-init when the target coupon prop changes.
  $effect(initForm);

  // Also re-init on every open: reopening with unchanged prop values (create -> create)
  // would not re-trigger the prop-keyed effect, leaving a stale draft in the form.
  $effect(() => {
    const el = document.getElementById('createCouponModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  function closeModal() {
    const el = document.getElementById('createCouponModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  async function saveCoupon() {
    if (!couponCode || couponCode.trim() === '') {
      showErrorToast($_('modals.coupon.toast-code-required'));
      return;
    }
    if (discount === '' || discount == null || isNaN(Number(discount))) {
      showErrorToast($_('modals.coupon.toast-discount-required'));
      return;
    }

    loading = true;

    try {
      const body = {
        name: couponName.trim(),
        code: couponCode.trim(),
        discount: Number(discount),
        unit: discountUnit === '₺' ? 'FIXED' : 'PERCENT',
        scope: productSelection === 'selected' ? 'SELECTED' : 'ALL',
        status: status === 'active' ? 'ACTIVE' : 'INACTIVE',
      };

      if (minPaymentAmount !== '' && minPaymentAmount != null) {
        body.minPaymentAmount = Number(minPaymentAmount);
      }
      if (productSelection === 'selected') {
        body.productIds = selectedProducts;
      }
      if (!isExpiryUnlimited) {
        if (startDate) body.startDate = dateInputToEpoch(startDate);
        if (expiryDate) body.expiryDate = dateInputToEpoch(expiryDate);
      }
      if (!isRedeemUnlimited && redeemLimit !== '' && redeemLimit != null) {
        body.redeemLimit = Number(redeemLimit);
      }
      if (!isCustomerRedeemUnlimited && customerRedeemLimit !== '' && customerRedeemLimit != null) {
        body.customerRedeemLimit = Number(customerRedeemLimit);
      }

      let result;
      if (isEdit && coupon) {
        result = await ApiUtil.put({ path: `/api/panel/market/coupons/${coupon.id}`, body });
      } else {
        result = await ApiUtil.post({ path: '/api/panel/market/coupons', body });
      }

      if (result.error) {
        if (result.error === 'CODE_ALREADY_EXISTS') {
          showErrorToast($_('modals.coupon.toast-code-exists'));
        } else {
          showErrorToast($_('modals.coupon.toast-save-error'));
        }
        return;
      }

      showSuccessToast(
        isEdit ? $_('modals.coupon.toast-updated') : $_('modals.coupon.toast-created'),
      );
      closeModal();
      onSaved();
    } catch (e) {
      console.error('[Market] Failed to save coupon', e);
      showErrorToast($_('modals.coupon.toast-save-error'));
    } finally {
      loading = false;
    }
  }
</script>

<div class="modal fade" id="createCouponModal" tabindex="-1" aria-labelledby="createCouponModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header p-3">
        <h5 class="modal-title" id="createCouponModalLabel">
          {#if isEdit}{$_('modals.coupon.edit-heading')}{:else}{$_('modals.coupon.create-heading')}{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body p-3">

        <!-- Status Switch -->
        <div class="mb-3">
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="couponStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="couponStatusSwitch">
              {$_('common.active')}
            </label>
          </div>
        </div>

        <!-- Coupon Name -->
        <div class="form-floating mb-3">
          <input type="text" class="form-control" id="couponNameInput" bind:value={couponName} placeholder={$_('modals.coupon.name')} />
          <label for="couponNameInput">{$_('modals.coupon.name')}</label>
        </div>

        <!-- Coupon Code -->
        <div class="input-group mb-3">
          <div class="form-floating">
            <input type="text" class="form-control" id="couponCodeInput" bind:value={couponCode} placeholder={$_('modals.coupon.code-placeholder')} />
            <label for="couponCodeInput">{$_('modals.coupon.code')}</label>
          </div>
          <button class="btn btn-primary px-3 px-sm-4" type="button" onclick={generateCouponCode} title={$_('modals.coupon.generate-code')}>
            <i class="fas fa-wand-magic-sparkles"></i>
            <span class="d-none d-sm-inline ms-2">{$_('common.create')}</span>
          </button>
        </div>

        <!-- Products Selection -->
        <div class="mb-3">
          <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3 mb-2 mt-1">
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="productSelection" id="allProducts" value="all" bind:group={productSelection}>
              <label class="form-check-label cursor-pointer" for="allProducts">{$_('modals.coupon.all-products')}</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="productSelection" id="selectedProducts" value="selected" bind:group={productSelection}>
              <label class="form-check-label cursor-pointer" for="selectedProducts">{$_('modals.coupon.selected-products')}</label>
            </div>
          </div>

          {#if productSelection === 'selected'}
            <ProductSelector bind:selected={selectedProducts} multiple={true} maxHeight="200px" />
          {/if}
        </div>

        <!-- Discount and Min Payment Amount -->
        <div class="row g-3 mb-3">
          <div class="col-8">
            <div class="form-floating">
              <input type="number" class="form-control" id="discountInput" bind:value={discount} placeholder={$_('modals.coupon.discount-value')} min="0" />
              <label for="discountInput">{$_('modals.coupon.discount-value')}</label>
            </div>
          </div>
          <div class="col-4">
            <div class="form-floating">
              <select class="form-select" id="discountUnitSelect" bind:value={discountUnit}>
                <option value="%">{$_('modals.coupon.unit-percent')}</option>
                <option value="₺">{$_('modals.coupon.unit-fixed', { values: { symbol: currencySymbol } })}</option>
              </select>
              <label for="discountUnitSelect">{$_('modals.coupon.unit')}</label>
            </div>
          </div>
        </div>

        <!-- Minimum Sepet Tutarı -->
        <div class="input-group mb-3">
          <div class="form-floating">
            <input type="number" class="form-control" id="minPaymentInput" bind:value={minPaymentAmount} placeholder={$_('modals.coupon.min-cart-amount')} min="0" />
            <label for="minPaymentInput">{$_('modals.coupon.min-cart-amount')}</label>
          </div>
          <span class="input-group-text">{currencySymbol}</span>
        </div>

        <!-- Date / Expiry Settings -->
        <div class="vstack gap-2 mb-3">
          <div class="form-check form-switch m-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="expirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-1 cursor-pointer mt-1" for="expirySwitch">{$_('modals.coupon.no-expiry')}</label>
          </div>
          {#if !isExpiryUnlimited}
            <div class="row g-3 animate__animated animate__fadeIn">
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="startDateInput" bind:value={startDate} placeholder={$_('modals.coupon.start-date')} />
                  <label for="startDateInput">{$_('modals.coupon.start-date')}</label>
                </div>
              </div>
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="expiryInput" bind:value={expiryDate} placeholder={$_('modals.coupon.end-date')} />
                  <label for="expiryInput">{$_('modals.coupon.end-date')}</label>
                </div>
              </div>
            </div>
          {/if}
        </div>

        <!-- Redeem Limit -->
        <div class="row g-3 align-items-center mb-3">
          <div class="col-6">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="redeemLimitSwitch" bind:checked={isRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="redeemLimitSwitch">{$_('modals.coupon.no-limit')}</label>
            </div>
          </div>
          <div class="col-6">
            {#if !isRedeemUnlimited}
              <div class="form-floating animate__animated animate__fadeIn">
                <input type="number" class="form-control" id="redeemLimitInput" bind:value={redeemLimit} placeholder={$_('modals.coupon.usage-limit')} min="1" />
                <label for="redeemLimitInput">{$_('modals.coupon.usage-limit')}</label>
              </div>
            {/if}
          </div>
        </div>

        <!-- Redeem Limit Per Customer -->
        <div class="row g-3 align-items-center mb-0">
          <div class="col-6">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="customerLimitSwitch" bind:checked={isCustomerRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="customerLimitSwitch">{$_('modals.coupon.per-customer-unlimited')}</label>
            </div>
          </div>
          <div class="col-6">
            {#if !isCustomerRedeemUnlimited}
              <div class="form-floating animate__animated animate__fadeIn">
                <input type="number" class="form-control" id="customerLimitInput" bind:value={customerRedeemLimit} placeholder={$_('modals.coupon.per-customer-limit')} min="1" />
                <label for="customerLimitInput">{$_('modals.coupon.per-customer-limit')}</label>
              </div>
            {/if}
          </div>
        </div>

      </div>
      <div class="modal-footer p-3">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100 m-0 d-flex align-items-center justify-content-center gap-2" disabled={loading} onclick={saveCoupon}>
            {#if loading}<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>{/if}
            {$_('common.save')}
          </button>
        {:else}
          <button type="button" class="btn btn-secondary w-100 m-0 d-flex align-items-center justify-content-center gap-2" disabled={loading} onclick={saveCoupon}>
            {#if loading}<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>{/if}
            {$_('common.create')}
          </button>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
