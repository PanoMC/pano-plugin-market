<script>
  import { showToast } from '@panomc/sdk/toasts';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';

  let { isEdit = false, creatorCode = null, currencySymbol = '', onSaved = () => {} } = $props();

  let creator = $state('');
  let code = $state('');
  let discount = $state('');
  let discountUnit = $state('%');
  let commission = $state('');
  let startDate = $state('');
  let expiryDate = $state('');
  let isExpiryUnlimited = $state(true);
  let isLimitUnlimited = $state(true);
  let redeemLimit = $state('');
  let status = $state('active'); // 'active' or 'inactive'
  let loading = $state(false);

  function generateCode() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let result = '';
    for (let i = 0; i < 8; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    code = result;
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
    creator = '';
    code = '';
    discount = '';
    discountUnit = '%';
    commission = '';
    startDate = '';
    expiryDate = '';
    isExpiryUnlimited = true;
    isLimitUnlimited = true;
    redeemLimit = '';
    status = 'active';
  }

  function initForm() {
    if (isEdit && creatorCode) {
      creator = creatorCode.creator || '';
      code = creatorCode.code || '';
      discount = creatorCode.discount ?? '';
      discountUnit = creatorCode.unit === 'FIXED' ? '₺' : '%';
      commission = creatorCode.commissionPercent ?? '';
      status = (creatorCode.status || 'ACTIVE') === 'ACTIVE' ? 'active' : 'inactive';

      if (creatorCode.startDate || creatorCode.expiryDate) {
        isExpiryUnlimited = false;
        startDate = creatorCode.startDate ? toDateInput(creatorCode.startDate) : '';
        expiryDate = creatorCode.expiryDate ? toDateInput(creatorCode.expiryDate) : '';
      } else {
        isExpiryUnlimited = true;
        startDate = '';
        expiryDate = '';
      }

      if (creatorCode.redeemLimit != null) {
        isLimitUnlimited = false;
        redeemLimit = creatorCode.redeemLimit;
      } else {
        isLimitUnlimited = true;
        redeemLimit = '';
      }
    } else if (!isEdit) {
      resetForm();
    }
  }

  // Re-init when the target creator code prop changes.
  $effect(initForm);

  // Also re-init on every open: reopening with unchanged prop values (create -> create)
  // would not re-trigger the prop-keyed effect, leaving a stale draft in the form.
  $effect(() => {
    const el = document.getElementById('createCreatorCodeModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  function closeModal() {
    const el = document.getElementById('createCreatorCodeModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  async function saveCreatorCode() {
    if (!creator || creator.trim() === '') {
      showToast($_('modals.creator-code.toast-creator-required'));
      return;
    }
    if (!code || code.trim() === '') {
      showToast($_('modals.creator-code.toast-code-required'));
      return;
    }
    if (discount === '' || discount == null || isNaN(Number(discount))) {
      showToast($_('modals.creator-code.toast-discount-required'));
      return;
    }
    const commissionValue = commission === '' || commission == null ? 0 : Number(commission);
    if (commissionValue < 0 || commissionValue > 100) {
      showToast($_('modals.creator-code.toast-commission-range'));
      return;
    }

    loading = true;

    try {
      const body = {
        creator: creator.trim(),
        code: code.trim(),
        discount: Number(discount),
        unit: discountUnit === '₺' ? 'FIXED' : 'PERCENT',
        commissionPercent: commissionValue,
        status: status === 'active' ? 'ACTIVE' : 'INACTIVE',
      };

      if (!isExpiryUnlimited) {
        if (startDate) body.startDate = dateInputToEpoch(startDate);
        if (expiryDate) body.expiryDate = dateInputToEpoch(expiryDate);
      }
      if (!isLimitUnlimited && redeemLimit !== '' && redeemLimit != null) {
        body.redeemLimit = Number(redeemLimit);
      }

      let result;
      if (isEdit && creatorCode) {
        result = await ApiUtil.put({ path: `/api/panel/market/creator-codes/${creatorCode.id}`, body });
      } else {
        result = await ApiUtil.post({ path: '/api/panel/market/creator-codes', body });
      }

      if (result.error) {
        if (result.error === 'CODE_ALREADY_EXISTS') {
          showToast($_('modals.creator-code.toast-code-exists'));
        } else {
          showToast($_('modals.creator-code.toast-save-error'));
        }
        return;
      }

      showToast(isEdit ? $_('modals.creator-code.toast-updated') : $_('modals.creator-code.toast-created'));
      closeModal();
      onSaved();
    } catch (e) {
      console.error('[Market] Failed to save creator code', e);
      showToast($_('modals.creator-code.toast-save-error'));
    } finally {
      loading = false;
    }
  }
</script>

<div class="modal fade" id="createCreatorCodeModal" tabindex="-1" aria-labelledby="createCreatorCodeModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header p-3">
        <h5 class="modal-title" id="createCreatorCodeModalLabel">
          {#if isEdit}{$_('modals.creator-code.heading-edit')}{:else}{$_('modals.creator-code.heading-create')}{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body p-3">
        <div class="vstack gap-3">
          <!-- Status Switch -->
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="creatorStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="creatorStatusSwitch">
              {$_('common.active')}
            </label>
          </div>

          <!-- Creator / Partner Name -->
          <div class="form-floating">
            <input type="text" class="form-control" id="creatorNameInput" bind:value={creator} placeholder={$_('modals.creator-code.creator-label')} />
            <label for="creatorNameInput">{$_('modals.creator-code.creator-label')}</label>
          </div>

          <!-- Commission Rate -->
          <div class="form-floating">
            <input type="number" class="form-control" id="creatorCommissionInput" bind:value={commission} placeholder={$_('modals.creator-code.commission-label')} min="0" max="100" />
            <label for="creatorCommissionInput">{$_('modals.creator-code.commission-label')}</label>
          </div>

          <!-- Code input with Generate button -->
          <div class="input-group">
            <div class="form-floating flex-grow-1">
              <input type="text" class="form-control" id="creatorCodeInput" bind:value={code} placeholder={$_('modals.creator-code.code-label')} />
              <label for="creatorCodeInput">{$_('modals.creator-code.code-label')}</label>
            </div>
            <button class="btn btn-primary px-3 px-sm-4" type="button" onclick={generateCode} title={$_('modals.creator-code.generate-title')}>
              <i class="fas fa-wand-magic-sparkles"></i>
              <span class="d-none d-sm-inline ms-2">{$_('common.create')}</span>
            </button>
          </div>

          <!-- Discount and Unit -->
          <div class="row g-3">
            <div class="col-8">
              <div class="form-floating">
                <input type="number" class="form-control" id="creatorDiscountInput" bind:value={discount} placeholder={$_('modals.creator-code.discount-label')} min="0" />
                <label for="creatorDiscountInput">{$_('modals.creator-code.discount-label')}</label>
              </div>
            </div>
            <div class="col-4">
              <div class="form-floating">
                <select class="form-select" id="creatorDiscountUnitSelect" bind:value={discountUnit}>
                  <option value="%">{$_('modals.creator-code.unit-percent')}</option>
                  <option value="₺">{$_('modals.creator-code.unit-fixed', { values: { symbol: currencySymbol } })}</option>
                </select>
                <label for="creatorDiscountUnitSelect">{$_('modals.creator-code.unit-label')}</label>
              </div>
            </div>
          </div>

          <!-- Date / Expiry Settings -->
          <div class="vstack gap-2">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="creatorExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="creatorExpirySwitch">{$_('modals.creator-code.no-expiry')}</label>
            </div>
            {#if !isExpiryUnlimited}
              <div class="row g-3 animate__animated animate__fadeIn">
                <div class="col-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="creatorStartDateInput" bind:value={startDate} placeholder={$_('modals.creator-code.start-date')} />
                    <label for="creatorStartDateInput">{$_('modals.creator-code.start-date')}</label>
                  </div>
                </div>
                <div class="col-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="creatorExpiryInput" bind:value={expiryDate} placeholder={$_('modals.creator-code.end-date')} />
                    <label for="creatorExpiryInput">{$_('modals.creator-code.end-date')}</label>
                  </div>
                </div>
              </div>
            {/if}
          </div>

          <!-- Redeem Limit -->
          <div class="row g-3 align-items-center mb-0">
            <div class="col-6">
              <div class="form-check form-switch m-0">
                <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="creatorLimitSwitch" bind:checked={isLimitUnlimited} style="width: 2.5em; height: 1.25em;">
                <label class="form-check-label ms-1 cursor-pointer mt-1" for="creatorLimitSwitch">{$_('modals.creator-code.no-limit')}</label>
              </div>
            </div>
            <div class="col-6">
              {#if !isLimitUnlimited}
                <div class="form-floating animate__animated animate__fadeIn">
                  <input type="number" class="form-control" id="creatorLimitInput" bind:value={redeemLimit} placeholder={$_('modals.creator-code.usage-limit')} min="1" />
                  <label for="creatorLimitInput">{$_('modals.creator-code.usage-limit')}</label>
                </div>
              {/if}
            </div>
          </div>
        </div>
      </div>
      <div class="modal-footer p-3">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100 m-0 d-flex align-items-center justify-content-center gap-2" disabled={loading} onclick={saveCreatorCode}>
            {#if loading}<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>{/if}
            {$_('common.save')}
          </button>
        {:else}
          <button type="button" class="btn btn-secondary w-100 m-0 d-flex align-items-center justify-content-center gap-2" disabled={loading} onclick={saveCreatorCode}>
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
