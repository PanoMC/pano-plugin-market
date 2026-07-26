<script>
  import { onMount } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import ProductSelector from '../ProductSelector.svelte';
  import { _, showSuccessToast, showErrorToast } from '../../../i18n';

  let { isEdit = false, discount = null, currencySymbol = '', onSaved = () => {} } = $props();

  let name = $state('');
  let value = $state('');
  let unit = $state('%');
  let startDate = $state('');
  let expiryDate = $state('');
  let isExpiryUnlimited = $state(true);
  let usageLimit = $state('');
  let isLimitUnlimited = $state(true);
  let status = $state('active');
  let minPaymentAmount = $state('');
  let discountScope = $state('all'); // 'all', 'products', 'categories'
  let loading = $state(false);

  let selectedProducts = $state([]);
  let selectedCategories = $state([]);
  let categorySearch = $state('');

  // Category checkbox list is fetched from the panel category tree and flattened.
  let categories = $state([]);

  let filteredCategories = $derived(
    categories.filter((c) => c.name.toLowerCase().includes(categorySearch.toLowerCase()))
  );

  function flattenCategories(nodes, acc = []) {
    for (const node of nodes) {
      acc.push({ id: node.id, name: node.name });
      if (node.children?.length) flattenCategories(node.children, acc);
    }
    return acc;
  }

  async function loadCategories() {
    try {
      const res = await ApiUtil.get({ path: '/api/panel/market/categories' });
      if (res.error) throw res.error;
      categories = flattenCategories(res.categories || []);
    } catch (e) {
      console.error('[Market] Failed to load categories', e);
      categories = [];
    }
  }

  onMount(loadCategories);

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
    name = '';
    value = '';
    unit = '%';
    startDate = '';
    expiryDate = '';
    isExpiryUnlimited = true;
    usageLimit = '';
    isLimitUnlimited = true;
    status = 'active';
    minPaymentAmount = '';
    discountScope = 'all';
    selectedProducts = [];
    selectedCategories = [];
    categorySearch = '';
  }

  function initForm() {
    if (isEdit && discount) {
      name = discount.name || '';
      value = discount.value ?? '';
      unit = discount.unit === 'FIXED' ? '₺' : '%';
      minPaymentAmount = discount.minPaymentAmount ?? '';
      status = (discount.status || 'ACTIVE') === 'ACTIVE' ? 'active' : 'inactive';

      const scope = discount.scope || 'ALL';
      discountScope = scope === 'PRODUCTS' ? 'products' : scope === 'CATEGORIES' ? 'categories' : 'all';
      selectedProducts = Array.isArray(discount.productIds) ? [...discount.productIds] : [];
      selectedCategories = Array.isArray(discount.categoryIds) ? [...discount.categoryIds] : [];
      categorySearch = '';

      if (discount.startDate || discount.expiryDate) {
        isExpiryUnlimited = false;
        startDate = discount.startDate ? toDateInput(discount.startDate) : '';
        expiryDate = discount.expiryDate ? toDateInput(discount.expiryDate) : '';
      } else {
        isExpiryUnlimited = true;
        startDate = '';
        expiryDate = '';
      }

      if (discount.usageLimit != null) {
        isLimitUnlimited = false;
        usageLimit = discount.usageLimit;
      } else {
        isLimitUnlimited = true;
        usageLimit = '';
      }
    } else if (!isEdit) {
      resetForm();
    }
  }

  // Re-init when the target discount prop changes.
  $effect(initForm);

  // Also re-init on every open: reopening with unchanged prop values (create -> create)
  // would not re-trigger the prop-keyed effect, leaving a stale draft in the form.
  $effect(() => {
    const el = document.getElementById('createDiscountModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  function toggleCategory(id) {
    if (selectedCategories.includes(id)) {
      selectedCategories = selectedCategories.filter((cId) => cId !== id);
    } else {
      selectedCategories = [...selectedCategories, id];
    }
  }

  function closeModal() {
    const el = document.getElementById('createDiscountModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  async function saveDiscount() {
    if (!name || name.trim() === '') {
      showErrorToast($_('modals.discount.toast-name-required'));
      return;
    }
    if (value === '' || value == null || isNaN(Number(value))) {
      showErrorToast($_('modals.discount.toast-value-required'));
      return;
    }

    loading = true;

    try {
      const body = {
        name: name.trim(),
        value: Number(value),
        unit: unit === '₺' ? 'FIXED' : 'PERCENT',
        scope: discountScope === 'products' ? 'PRODUCTS' : discountScope === 'categories' ? 'CATEGORIES' : 'ALL',
        status: status === 'active' ? 'ACTIVE' : 'INACTIVE',
      };

      if (minPaymentAmount !== '' && minPaymentAmount != null) {
        body.minPaymentAmount = Number(minPaymentAmount);
      }
      if (discountScope === 'products') {
        body.productIds = selectedProducts;
      } else if (discountScope === 'categories') {
        body.categoryIds = selectedCategories;
      }
      if (!isExpiryUnlimited) {
        if (startDate) body.startDate = dateInputToEpoch(startDate);
        if (expiryDate) body.expiryDate = dateInputToEpoch(expiryDate);
      }
      if (!isLimitUnlimited && usageLimit !== '' && usageLimit != null) {
        body.usageLimit = Number(usageLimit);
      }

      let result;
      if (isEdit && discount) {
        result = await ApiUtil.put({ path: `/api/panel/market/discounts/${discount.id}`, body });
      } else {
        result = await ApiUtil.post({ path: '/api/panel/market/discounts', body });
      }

      if (result.error) throw result.error;

      showSuccessToast(
        isEdit ? $_('modals.discount.toast-updated') : $_('modals.discount.toast-created'),
      );
      closeModal();
      onSaved();
    } catch (e) {
      console.error('[Market] Failed to save discount', e);
      showErrorToast($_('modals.discount.toast-save-error'));
    } finally {
      loading = false;
    }
  }
</script>

<div class="modal fade" id="createDiscountModal" tabindex="-1" aria-labelledby="createDiscountModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header p-3">
        <h5 class="modal-title" id="createDiscountModalLabel">
          {#if isEdit}{$_('modals.discount.title-edit')}{:else}{$_('modals.discount.title-create')}{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body p-3">
        <div class="vstack gap-3">
          <!-- Status Switch -->
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="discountStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="discountStatusSwitch">
              {$_('common.active')}
            </label>
          </div>

          <!-- Discount Name -->
          <div class="form-floating">
            <input type="text" class="form-control" id="discountNameInput" bind:value={name} placeholder={$_('modals.discount.name-label')} />
            <label for="discountNameInput">{$_('modals.discount.name-label')}</label>
          </div>

          <!-- Value and Unit -->
          <div class="row g-3">
            <div class="col-8">
              <div class="form-floating">
                <input type="number" class="form-control" id="discountValueInput" bind:value={value} placeholder={$_('modals.discount.value-placeholder')} />
                <label for="discountValueInput">{$_('modals.discount.value-label')}</label>
              </div>
            </div>
            <div class="col-4">
              <div class="form-floating">
                <select class="form-select" id="discountUnitSelect" bind:value={unit}>
                  <option value="%">{$_('modals.discount.unit-percent')}</option>
                  <option value="₺">{$_('modals.discount.unit-fixed', { values: { symbol: currencySymbol } })}</option>
                </select>
                <label for="discountUnitSelect">{$_('modals.discount.unit-label')}</label>
              </div>
            </div>
          </div>

          <!-- Minimum Sepet Tutarı -->
          <div class="input-group">
            <div class="form-floating">
              <input type="number" class="form-control" id="discountMinPaymentInput" bind:value={minPaymentAmount} placeholder={$_('modals.discount.min-payment')} min="0" />
              <label for="discountMinPaymentInput">{$_('modals.discount.min-payment')}</label>
            </div>
            <span class="input-group-text">{currencySymbol}</span>
          </div>

          <!-- İndirim Kapsamı Seçimi -->
          <div class="mb-2">
            <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3 mb-2 mt-1">
              <div class="form-check m-0">
                <input class="form-check-input cursor-pointer" type="radio" name="discountScope" id="scopeAll" value="all" bind:group={discountScope}>
                <label class="form-check-label cursor-pointer" for="scopeAll">{$_('modals.discount.scope-all')}</label>
              </div>
              <div class="form-check m-0">
                <input class="form-check-input cursor-pointer" type="radio" name="discountScope" id="scopeProducts" value="products" bind:group={discountScope}>
                <label class="form-check-label cursor-pointer" for="scopeProducts">{$_('modals.discount.scope-products')}</label>
              </div>
              <div class="form-check m-0">
                <input class="form-check-input cursor-pointer" type="radio" name="discountScope" id="scopeCategories" value="categories" bind:group={discountScope}>
                <label class="form-check-label cursor-pointer" for="scopeCategories">{$_('modals.discount.scope-categories')}</label>
              </div>
            </div>

            {#if discountScope === 'products'}
              <ProductSelector bind:selected={selectedProducts} multiple={true} maxHeight="150px" />
            {:else if discountScope === 'categories'}
              <input type="text" class="form-control form-control-sm mb-2" placeholder={$_('modals.discount.category-search-placeholder')} bind:value={categorySearch} />
              <div class="list-group border rounded overflow-y-auto mb-0" style="max-height: 150px;">
                {#each filteredCategories as category}
                  <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action">
                    <input class="form-check-input flex-shrink-0 mt-0 cursor-pointer" type="checkbox" value={category.id}
                           checked={selectedCategories.includes(category.id)}
                           onchange={() => toggleCategory(category.id)}>
                    <span>{category.name}</span>
                  </label>
                {:else}
                  <div class="text-center text-body-secondary py-3">
                    <i class="fas fa-circle-info me-1"></i> {$_('modals.discount.no-results')}
                  </div>
                {/each}
              </div>
            {/if}
          </div>

          <!-- Tarih Alanları (Süresiz switch aktifse tamamen gizlenir) -->
          <div class="vstack gap-2">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="discountExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="discountExpirySwitch">{$_('modals.discount.expiry-unlimited')}</label>
            </div>

            {#if !isExpiryUnlimited}
              <div class="row g-3 animate__animated animate__fadeIn">
                <!-- Başlangıç Tarihi -->
                <div class="col-md-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="discountStartInput" bind:value={startDate} placeholder={$_('modals.discount.start-date')} />
                    <label for="discountStartInput">{$_('modals.discount.start-date')}</label>
                  </div>
                </div>

                <!-- Bitiş Tarihi -->
                <div class="col-md-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="discountExpiryInput" bind:value={expiryDate} placeholder={$_('modals.discount.expiry-date')} />
                    <label for="discountExpiryInput">{$_('modals.discount.expiry-date')}</label>
                  </div>
                </div>
              </div>
            {/if}
          </div>

          <!-- Usage Limit -->
          <div class="row g-3 align-items-center">
            <div class="col-6">
              <div class="form-check form-switch m-0">
                <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="discountLimitSwitch" bind:checked={isLimitUnlimited} style="width: 2.5em; height: 1.25em;">
                <label class="form-check-label ms-1 cursor-pointer mt-1" for="discountLimitSwitch">{$_('modals.discount.limit-unlimited')}</label>
              </div>
            </div>
            <div class="col-6">
              {#if !isLimitUnlimited}
                <div class="form-floating animate__animated animate__fadeIn">
                  <input type="number" class="form-control" id="discountLimitInput" bind:value={usageLimit} placeholder={$_('modals.discount.usage-limit')} min="1" />
                  <label for="discountLimitInput">{$_('modals.discount.usage-limit')}</label>
                </div>
              {/if}
            </div>
          </div>
        </div>
      </div>
      <div class="modal-footer p-3">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100 m-0 d-flex align-items-center justify-content-center gap-2" disabled={loading} onclick={saveDiscount}>
            {#if loading}<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>{/if}
            {$_('common.save')}
          </button>
        {:else}
          <button type="button" class="btn btn-secondary w-100 m-0 d-flex align-items-center justify-content-center gap-2" disabled={loading} onclick={saveDiscount}>
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
