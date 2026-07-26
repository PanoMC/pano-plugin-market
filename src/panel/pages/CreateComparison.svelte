<script module>
  import ApiUtil from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.create-comparison.title');

    const id = searchParams.get('id');

    const productsRes = await ApiUtil.get({
      path: '/api/panel/market/products/simple',
      request: event,
    });
    const products = productsRes && !productsRes.error ? productsRes.products || [] : [];

    let comparison = null;
    if (id) {
      const res = await ApiUtil.get({
        path: `/api/panel/market/comparisons/${id}`,
        request: event,
      });
      if (res && !res.error) {
        comparison = res;
      }
    }

    return { data: { products, comparison } };
  }
</script>

<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { _, showSuccessToast, showErrorToast } from '../../i18n';

  let { data } = $props();

  // An edit URL whose comparison no longer exists (deleted / stale bookmark):
  // render an explicit error state instead of silently becoming a create form
  // whose Kaydet would POST a duplicate (SSR-safe: no toast during init).
  const comparisonMissing = $derived(!!$page.url.searchParams.get('id') && !data.comparison);

  function mapComparison(res) {
    if (!res) {
      return {
        name: '',
        status: 'active',
        priority: 0,
        selectedProducts: [null, null], // Starts with two empty column slots by default for clean UX
        features: [],
        cellValues: {}
      };
    }
    return {
      name: res.name || '',
      status: res.status === 'ACTIVE' ? 'active' : 'inactive',
      priority: res.priority ?? 0,
      // selectedProducts round-trips with its null column slots preserved.
      selectedProducts: Array.isArray(res.selectedProducts) ? res.selectedProducts : [null, null],
      features: Array.isArray(res.features) ? res.features : [],
      cellValues: res.cellValues && typeof res.cellValues === 'object' ? res.cellValues : {}
    };
  }

  // Derived directly from load() data so they can never go stale if it re-runs.
  let comparisonDbId = $derived(data.comparison?.id ?? null);
  let isEdit = $derived(!!data.comparison);
  let products = $derived(data.products || []);

  let saving = $state(false);

  // Editable form model, seeded from load() data.
  // svelte-ignore state_referenced_locally -- intentional init-time seeding; the
  // effect below re-syncs if load() ever re-runs without a remount.
  let comparison = $state(mapComparison(data.comparison));

  // Defensive re-sync if load() ever re-runs without a component remount — the
  // current host remounts on every data change, but that is its private contract.
  let appliedRecord = null;
  let recordEffectRan = false;
  $effect(() => {
    const record = data.comparison;
    if (recordEffectRan && record !== appliedRecord) {
      comparison = mapComparison(record);
    }
    recordEffectRan = true;
    appliedRecord = record;
  });

  function addColumn() {
    comparison.selectedProducts = [...comparison.selectedProducts, null];
  }

  function removeColumn(index) {
    comparison.selectedProducts = comparison.selectedProducts.filter((_, i) => i !== index);
  }

  function updateColumnProduct(index, productId) {
    const updated = [...comparison.selectedProducts];
    updated[index] = productId ? Number(productId) : null;
    comparison.selectedProducts = updated;
  }

  function addFeature() {
    comparison.features = [...comparison.features, { id: Date.now(), name: '' }];
  }

  function removeFeature(id) {
    comparison.features = comparison.features.filter(f => f.id !== id);
  }

  async function handleSave() {
    if (!comparison.name || !comparison.name.trim()) {
      showErrorToast($_('pages.create-comparison.toast-title-required'));
      return;
    }

    saving = true;
    try {
      const body = {
        name: comparison.name,
        status: comparison.status === 'active' ? 'ACTIVE' : 'INACTIVE',
        priority: Number(comparison.priority) || 0,
        selectedProducts: comparison.selectedProducts,
        features: comparison.features,
        cellValues: comparison.cellValues
      };

      let result;
      if (comparisonDbId) {
        result = await ApiUtil.put({ path: `/api/panel/market/comparisons/${comparisonDbId}`, body });
      } else {
        result = await ApiUtil.post({ path: '/api/panel/market/comparisons', body });
      }

      if (result?.error) {
        showErrorToast($_('pages.create-comparison.toast-error'));
        return;
      }

      showSuccessToast(
        isEdit
          ? $_('pages.create-comparison.toast-updated')
          : $_('pages.create-comparison.toast-created'),
      );
      goto(`${base}/market/comparisons`);
    } catch (e) {
      console.error('[Market] Failed to save comparison', e);
      showErrorToast($_('pages.create-comparison.toast-error'));
    } finally {
      saving = false;
    }
  }
</script>

<MarketLayout>
  {#snippet left()}
    <div class="d-flex align-items-center gap-4">
      <a href="{base}/market/comparisons" class="btn btn-link text-decoration-none p-0">
        <i class="fas fa-arrow-left"></i>
        <span class="ms-2">{$_('pages.create-comparison.back-link')}</span>
      </a>
    </div>
  {/snippet}

  {#snippet right()}
    {#if !comparisonMissing}
    <div class="hstack gap-1">
      <button
        class="btn btn-secondary ms-2 shadow-sm"
        onclick={handleSave}
        disabled={saving || !comparison.name}>
        {#if saving}
          <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
        {:else}
          <i class="fas fa-save"></i>
        {/if}
        <span class="d-lg-inline d-none ms-2">{isEdit ? $_('common.save') : $_('common.create')}</span>
      </button>
    </div>
    {/if}
  {/snippet}

  {#if comparisonMissing}
    <div class="card animate__animated animate__fadeIn">
      <div class="card-body text-center text-body-secondary py-5">
        <i class="fas fa-circle-exclamation mb-2 fs-3"></i>
        <div>{$_('pages.create-comparison.not-found')}</div>
        <a href="{base}/market/comparisons" class="btn btn-secondary mt-3">{$_('pages.create-comparison.back-to-comparisons')}</a>
      </div>
    </div>
  {:else}
  <section class="row g-3">
    <!-- Ana Sütun (Table Matrix Builder) -->
    <div class="col-lg-8">
      <div class="vstack gap-3 animate__animated animate__fadeIn">
        <!-- Genel Bilgiler Kartı (Comparison Title) -->
        <div class="card w-100">
          <div class="card-body p-4">
            <div class="form-floating">
              <input
                type="text"
                class="form-control"
                id="comparisonName"
                placeholder={$_('pages.create-comparison.title-label')}
                bind:value={comparison.name} />
              <label for="comparisonName">{$_('pages.create-comparison.title-label')}</label>
            </div>
          </div>
        </div>

        <!-- İnteraktif Karşılaştırma Matrisi -->
        <div class="card w-100">
          <div class="card-body p-4">
            <div class="table-responsive">
              <table class="table table-bordered align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col" style="min-width: 250px;">{$_('pages.create-comparison.feature-name-header')}</th>
                    {#each comparison.selectedProducts as prodId, colIndex}
                      <th scope="col" class="text-center" style="min-width: 200px;">
                        <div class="d-flex align-items-center gap-1">
                          <select 
                            class="form-select text-truncate" 
                            value={prodId || ''} 
                            onchange={(e) => updateColumnProduct(colIndex, e.target.value)}>
                            <option value="">{$_('pages.create-comparison.select-product')}</option>
                            {#each products as prod}
                              <option value={prod.id} disabled={comparison.selectedProducts.includes(prod.id) && prod.id !== prodId}>
                                {prod.name}
                              </option>
                            {/each}
                          </select>
                          <button 
                            type="button" 
                            class="btn-close" 
                            onclick={() => removeColumn(colIndex)}
                            title={$_('pages.create-comparison.delete-column')}
                            aria-label={$_('pages.create-comparison.delete-column')}></button>
                        </div>
                      </th>
                    {/each}
                    <th scope="col" class="text-center" style="width: 100px; min-width: 100px;">
                      <button 
                        type="button" 
                        class="btn btn-primary d-flex align-items-center justify-content-center mx-auto" 
                        onclick={addColumn}
                        title={$_('pages.create-comparison.add-column')}>
                        <i class="fas fa-plus"></i>
                      </button>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {#each comparison.features as feature (feature.id)}
                    <tr>
                      <td>
                        <input 
                          type="text" 
                          class="form-control" 
                          bind:value={feature.name}
                          placeholder={$_('pages.create-comparison.feature-name-placeholder')} />
                      </td>
                      {#each comparison.selectedProducts as prodId}
                        <td class="text-center">
                          {#if prodId}
                            {@const val = comparison.cellValues[`${feature.id}-${prodId}`] || 'yes'}
                            {#if val !== 'yes' && val !== 'no'}
                              <div class="hstack gap-2 mx-auto" style="max-width: 160px;">
                                <input 
                                  type="text" 
                                  class="form-control text-center animate__animated animate__fadeIn animate__fast" 
                                  placeholder={$_('pages.create-comparison.custom-value-placeholder')}
                                  value={val === 'custom' ? '' : val} 
                                  oninput={(e) => comparison.cellValues[`${feature.id}-${prodId}`] = e.target.value} />
                                <button 
                                  type="button" 
                                  class="btn-close" 
                                  onclick={() => comparison.cellValues[`${feature.id}-${prodId}`] = 'yes'}
                                  title={$_('pages.create-comparison.back-to-option')}
                                  aria-label={$_('pages.create-comparison.back-to-option')}></button>
                              </div>
                            {:else}
                              <select 
                                class="form-select text-center mx-auto" 
                                value={val} 
                                onchange={(e) => comparison.cellValues[`${feature.id}-${prodId}`] = e.target.value}
                                style="max-width: 120px;">
                                <option value="yes">✔</option>
                                <option value="no">❌</option>
                                <option value="custom">{$_('pages.create-comparison.custom-define')}</option>
                              </select>
                            {/if}
                          {:else}
                            <span class="text-body-secondary font-monospace">{$_('pages.create-comparison.not-selected')}</span>
                          {/if}
                        </td>
                      {/each}
                      <td class="text-center">
                        <button 
                          type="button" 
                          class="btn-close" 
                          onclick={() => removeFeature(feature.id)}
                          title={$_('pages.create-comparison.delete-row')}
                          aria-label={$_('pages.create-comparison.delete-row')}></button>
                      </td>
                    </tr>
                  {/each}
                  
                  {#if comparison.features.length === 0}
                    <tr>
                      <td colspan={comparison.selectedProducts.length + 2} class="text-center py-4 text-body-secondary">
                        <span>{$_('pages.create-comparison.empty-features')}</span>
                      </td>
                    </tr>
                  {/if}

                  <!-- Özellik Ekleme Satırı -->
                  <tr>
                    <td colspan={comparison.selectedProducts.length + 2} class="p-3">
                      <button 
                        type="button" 
                        class="btn btn-primary d-inline-flex align-items-center justify-content-center" 
                        onclick={addFeature}
                        title={$_('pages.create-comparison.add-feature')}>
                        <i class="fas fa-plus"></i>
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Yan Sütun (Sidebar) -->
    <div class="col-lg-4">
      <div class="vstack gap-3">
        <!-- Ayarlar Kartı -->
        <div class="card">
          <div class="card-body">
            <ul class="list-group p-0 m-0">
              <!-- Durum Seçimi -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">{$_('common.status')}</div>
                  <div class="col-6 d-flex justify-content-end align-items-center gap-2">
                    <span>
                      {comparison.status === 'active' ? $_('common.active') : $_('common.inactive')}
                    </span>
                    <div class="form-check form-switch m-0">
                      <input 
                        class="form-check-input cursor-pointer" 
                        type="checkbox" 
                        role="switch" 
                        id="comparisonStatusSwitch" 
                        checked={comparison.status === 'active'}
                        onchange={(e) => comparison.status = e.target.checked ? 'active' : 'inactive'} />
                    </div>
                  </div>
                </div>
              </li>

              <!-- Sıralama -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">{$_('pages.create-comparison.priority')}</div>
                  <div class="col-6">
                    <input 
                      type="number" 
                      class="form-control text-end" 
                      placeholder="0" 
                      bind:value={comparison.priority} />
                  </div>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </section>
  {/if}
</MarketLayout>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
