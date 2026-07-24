<script>
  import { showToast } from '@panomc/sdk/toasts';
  import ApiUtil from '@panomc/sdk/utils/api';
  import ProductSelector from '../ProductSelector.svelte';
  import { _ } from '../../../i18n';

  let { isEdit = false, gift = null, onSaved = () => {} } = $props();

  let giftCode = $state('');
  let giftType = $state('product'); // 'product', 'credit', or 'random'

  let selectedProductId = $state('');
  let creditAmount = $state('');
  let selectedRandomProductIds = $state([]);

  let isExpiryUnlimited = $state(true);
  let startDate = $state('');
  let expiryDate = $state('');
  let status = $state('active'); // 'active' or 'inactive'

  let loading = $state(false);

  // Epoch millis -> date-input (YYYY-MM-DD) in local time, matching how the
  // value is reparsed (as local midnight) on save.
  function epochToDateInput(epoch) {
    const d = new Date(epoch);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  // date-input (YYYY-MM-DD) -> epoch millis at local midnight.
  function dateInputToEpoch(value) {
    const [year, month, day] = value.split('-').map(Number);
    return new Date(year, month - 1, day).getTime();
  }

  function initForm() {
    if (isEdit && gift) {
      giftCode = gift.code || '';
      giftType = (gift.type || 'PRODUCT').toLowerCase();
      selectedProductId = gift.productId ?? '';
      creditAmount = gift.creditAmount ?? '';
      selectedRandomProductIds = gift.productIds ? [...gift.productIds] : [];
      status = gift.status === 'INACTIVE' ? 'inactive' : 'active';
      isExpiryUnlimited = !gift.expiryDate && !gift.startDate;
      startDate = gift.startDate ? epochToDateInput(gift.startDate) : '';
      expiryDate = gift.expiryDate ? epochToDateInput(gift.expiryDate) : '';
    } else if (!isEdit) {
      giftCode = '';
      giftType = 'product';
      selectedProductId = '';
      creditAmount = '';
      selectedRandomProductIds = [];
      status = 'active';
      isExpiryUnlimited = true;
      startDate = '';
      expiryDate = '';
    }
  }

  // Re-init when the target gift prop changes.
  $effect(initForm);

  // Also re-init on every open: reopening with unchanged prop values (create -> create,
  // or the same row after an abandoned edit) would not re-trigger the prop-keyed effect.
  $effect(() => {
    const el = document.getElementById('createGiftModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  function generateGiftCode() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let result = '';
    for (let i = 0; i < 8; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    giftCode = result;
  }

  function closeModal() {
    const el = document.getElementById('createGiftModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  async function saveGift() {
    if (!giftCode || giftCode.trim() === '') {
      showToast($_('modals.gift.toast-code-required'));
      return;
    }

    const typeEnum = giftType.toUpperCase();

    if (typeEnum === 'PRODUCT' && !selectedProductId) {
      showToast($_('modals.gift.toast-select-product'));
      return;
    }
    if (typeEnum === 'CREDIT' && (creditAmount === '' || creditAmount === null || Number(creditAmount) <= 0)) {
      showToast($_('modals.gift.toast-invalid-credit'));
      return;
    }
    if (typeEnum === 'RANDOM' && (!selectedRandomProductIds || selectedRandomProductIds.length === 0)) {
      showToast($_('modals.gift.toast-select-at-least-one'));
      return;
    }

    loading = true;

    try {
      const body = {
        code: giftCode.trim(),
        type: typeEnum,
        status: status === 'active' ? 'ACTIVE' : 'INACTIVE',
      };

      if (typeEnum === 'PRODUCT') {
        body.productId = Number(selectedProductId);
      } else if (typeEnum === 'CREDIT') {
        body.creditAmount = Number(creditAmount);
      } else if (typeEnum === 'RANDOM') {
        body.productIds = selectedRandomProductIds.map(Number);
      }

      if (!isExpiryUnlimited) {
        if (startDate) body.startDate = dateInputToEpoch(startDate);
        if (expiryDate) body.expiryDate = dateInputToEpoch(expiryDate);
      }

      let result;
      if (isEdit && gift) {
        result = await ApiUtil.put({
          path: `/api/panel/market/gifts/${gift.id}`,
          body,
        });
      } else {
        result = await ApiUtil.post({
          path: '/api/panel/market/gifts',
          body,
        });
      }

      if (result.error) {
        if (result.error === 'CODE_ALREADY_EXISTS') {
          showToast($_('modals.gift.toast-code-exists'));
        } else {
          showToast($_('modals.gift.toast-save-error'));
        }
        return;
      }

      showToast(isEdit ? $_('modals.gift.toast-updated') : $_('modals.gift.toast-created'));
      closeModal();
      onSaved();
    } catch (e) {
      console.error('[Market] Failed to save gift', e);
      showToast($_('modals.gift.toast-save-error'));
    } finally {
      loading = false;
    }
  }
</script>

<div class="modal fade" id="createGiftModal" tabindex="-1" aria-labelledby="createGiftModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createGiftModalLabel">
          {#if isEdit}{$_('modals.gift.edit-title')}{:else}{$_('modals.gift.create-title')}{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body pb-0">

        <!-- Status Switch -->
        <div class="mb-3">
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="giftStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="giftStatusSwitch">
              {$_('common.active')}
            </label>
          </div>
        </div>

        <!-- Gift Code -->
        <div class="input-group mb-3">
          <div class="form-floating">
            <input type="text" class="form-control" id="giftCodeInput" bind:value={giftCode} placeholder={$_('modals.gift.code-placeholder')} />
            <label for="giftCodeInput">{$_('modals.gift.code-label')}</label>
          </div>
          <button class="btn btn-primary px-3 px-sm-4" type="button" onclick={generateGiftCode} title={$_('modals.gift.generate-code')}>
            <i class="fas fa-wand-magic-sparkles"></i>
            <span class="d-none d-sm-inline ms-2">{$_('common.create')}</span>
          </button>
        </div>

        <!-- Gift Type -->
        <div class="mb-3">
          <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3 mb-3 mt-1">
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeProduct" value="product" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeProduct">{$_('modals.gift.type-product')}</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeCredit" value="credit" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeCredit">{$_('modals.gift.type-credit')}</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeRandom" value="random" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeRandom">{$_('modals.gift.type-random')}</label>
            </div>
          </div>

          {#if giftType === 'product'}
            <ProductSelector bind:selected={selectedProductId} multiple={false} maxHeight="200px" />
          {:else if giftType === 'credit'}
            <div class="input-group">
              <div class="form-floating">
                <input type="number" class="form-control" id="creditAmountInput" bind:value={creditAmount} placeholder={$_('modals.gift.credit-amount')} min="1" />
                <label for="creditAmountInput">{$_('modals.gift.credit-amount')}</label>
              </div>
              <span class="input-group-text">
                <i class="fas fa-coins text-warning"></i>
              </span>
            </div>
          {:else if giftType === 'random'}
            <div class="vstack gap-2 animate__animated animate__fadeIn">
              <label class="form-label mb-0">{$_('modals.gift.random-products-label')}</label>
              <ProductSelector bind:selected={selectedRandomProductIds} multiple={true} maxHeight="180px" />
            </div>
          {/if}
        </div>

        <!-- Date / Expiry Settings -->
        <div class="vstack gap-2 mb-3">
          <div class="form-check form-switch m-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="giftExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-1 cursor-pointer mt-1" for="giftExpirySwitch">{$_('modals.gift.unlimited-duration')}</label>
          </div>
          {#if !isExpiryUnlimited}
            <div class="row g-3 animate__animated animate__fadeIn">
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="giftStartDateInput" bind:value={startDate} placeholder={$_('modals.gift.start-date')} />
                  <label for="giftStartDateInput">{$_('modals.gift.start-date')}</label>
                </div>
              </div>
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="giftExpiryInput" bind:value={expiryDate} placeholder={$_('modals.gift.end-date')} />
                  <label for="giftExpiryInput">{$_('modals.gift.end-date')}</label>
                </div>
              </div>
            </div>
          {/if}
        </div>

      </div>
      <div class="modal-footer p-3 pt-3">
        {#if isEdit}
          <button
            type="button"
            class="btn btn-primary w-100 m-0 d-flex align-items-center justify-content-center gap-2"
            disabled={loading || !giftCode}
            onclick={saveGift}>
            {#if loading}
              <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
            {/if}
            {$_('common.save')}
          </button>
        {:else}
          <button
            type="button"
            class="btn btn-secondary w-100 m-0 d-flex align-items-center justify-content-center gap-2"
            disabled={loading || !giftCode}
            onclick={saveGift}>
            {#if loading}
              <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
            {/if}
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
