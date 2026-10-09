<MarketLayout area="catalog">
  {#snippet left()}
    <a href="{base}/market/products" class="btn btn-link text-decoration-none p-0">
      <i class="fas fa-arrow-left" aria-hidden="true"></i>
      <span class="ms-2">{$_('pages.create-product.products')}</span>
    </a>
  {/snippet}

  {#snippet right()}
    {#if !productMissing && !data.error}
      <div class="hstack gap-1">
        <button
          type="button"
          class="btn btn-link link-danger"
          title={$_('pages.create-product.remove-title')}
          aria-label={$_('pages.create-product.remove-title')}
          onclick={deleteProduct}
          disabled={!isEdit || saving}>
          <i class="fas fa-trash" aria-hidden="true"></i>
        </button>
        <button
          type="button"
          class="btn btn-secondary ms-2"
          onclick={handleSave}
          disabled={!isDirty || saving}>
          {#if saving}
            <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
          {:else}
            <i class="fas fa-save" aria-hidden="true"></i>
          {/if}
          <span class="d-lg-inline d-none ms-2">{$_('common.save')}</span>
        </button>
      </div>
    {/if}
  {/snippet}

  {#if productMissing}
    <div class="card animate__animated animate__fadeIn">
      <div class="card-body text-center text-body-secondary py-5">
        <i class="fas fa-circle-exclamation mb-2 fs-3" aria-hidden="true"></i>
        <div>{$_('pages.create-product.not-found')}</div>
        <a href="{base}/market/products" class="btn btn-secondary mt-3">
          {$_('pages.create-product.back-to-products')}
        </a>
      </div>
    </div>
  {:else if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="overflow-x-auto">
      <ul
        class="nav nav-pills flex-nowrap"
        role="tablist"
        aria-label={$_('pages.create-product.title')}>
        {#each tabs as id, index (id)}
          <li class="nav-item" role="presentation">
            <button
              type="button"
              role="tab"
              id="product-tab-{id}"
              class="nav-link text-nowrap"
              class:active={currentTab === id}
              aria-selected={currentTab === id}
              aria-controls="product-panel-{id}"
              tabindex={currentTab === id ? 0 : -1}
              onclick={() => selectTab(id)}
              onkeydown={(e) => onTabKeydown(e, index)}>
              {$_(`pages.create-product.tabs.${id}`)}
              {#if errorTabs.includes(id)}
                <span class="text-danger ms-1" aria-hidden="true">&bull;</span>
                <span class="visually-hidden">{$_('pages.create-product.tab-has-errors')}</span>
              {/if}
            </button>
          </li>
        {/each}
      </ul>
    </div>

    <section class="row g-3">
      <div class="col-lg-8">
        <div class="vstack gap-3">
          {#if currentTab === 'pricing' && product.billingMode === 'SUBSCRIPTION'}
            <div class="alert alert-info d-flex align-items-start" role="alert">
              <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
              <div>
                <b>{$_('pages.create-product.subscription-notice.title')}</b>
                <div>{$_('pages.create-product.subscription-notice.body')}</div>
              </div>
            </div>
          {/if}

          {#if currentTab === 'bundle' && sideErrors.products}
            {@render sideWarning('products')}
          {:else if currentTab === 'actions' && sideErrors.servers}
            {@render sideWarning('servers')}
          {/if}

          <!-- The general tab stays mounted so the editor keeps its content and never re-normalises. -->
          <div
            role="tabpanel"
            id="product-panel-general"
            aria-labelledby="product-tab-general"
            hidden={currentTab !== 'general'}>
            <GeneralTab bind:product {errors} />
          </div>

          {#if currentTab !== 'general'}
            <div
              role="tabpanel"
              id="product-panel-{currentTab}"
              aria-labelledby="product-tab-{currentTab}">
              {#if currentTab === 'pricing'}
                <PricingTab bind:product {errors} {ctx} {isEdit} onAdjustStock={openStock} />
              {:else if currentTab === 'variants'}
                <VariantsTab bind:product {errors} {ctx} {isEdit} onAdjustStock={openStock} />
              {:else if currentTab === 'fields'}
                <FieldsTab bind:product {errors} {ctx} />
              {:else if currentTab === 'bundle'}
                <BundleTab bind:product {errors} products={productsList} />
              {:else if currentTab === 'shipping'}
                <ShippingTab bind:product {errors} {ctx} />
              {:else if currentTab === 'limits'}
                <LimitsTab bind:product {errors} {ctx} products={productsList} />
              {:else if currentTab === 'providers'}
                <ProvidersTab bind:product {errors} {ctx} />
              {:else if currentTab === 'seo'}
                <SeoTab bind:product {errors} {ctx} />
              {:else if currentTab === 'actions'}
                <ActionsTab bind:product {errors} {ctx} servers={serversList} />
              {/if}
            </div>
          {/if}
        </div>
      </div>

      <div class="col-lg-4">
        <div class="card">
          <div class="card-body">
            <ul class="list-group p-0 m-0">
              <li class="list-group-item p-2">
                {#if previewUrl}
                  <div class="position-relative w-100">
                    <div
                      class="rounded border d-flex align-items-center justify-content-center bg-body-tertiary position-relative overflow-hidden ratio ratio-1x1"
                      role="button"
                      tabindex="0"
                      onclick={() => fileInput.click()}
                      onkeydown={(e) => e.key === 'Enter' && fileInput.click()}>
                      <img
                        src={previewUrl}
                        alt={$_('pages.create-product.image-alt')}
                        class="w-100 h-100 object-fit-cover" />
                    </div>
                    <button
                      type="button"
                      class="btn btn-sm btn-danger position-absolute top-0 start-100 translate-middle rounded-circle shadow-sm"
                      title={$_('common.remove')}
                      aria-label={$_('common.remove')}
                      onclick={onRemoveImage}>
                      <i class="fas fa-minus small" aria-hidden="true"></i>
                    </button>
                  </div>
                {:else}
                  <DragAndDropZone
                    style="aspect-ratio: 1/1;"
                    icon="fas fa-image fa-3x"
                    title={$_('pages.create-product.image-title')}
                    accept={['image/png', 'image/jpeg', 'image/gif', 'image/webp']}
                    maxFileSize={5 * 1024 * 1024}
                    on:drop={(e) => processFile(e.detail)}
                    on:error={() => showErrorToast($_('pages.create-product.image-error'))} />
                {/if}
                <input
                  type="file"
                  class="d-none"
                  accept="image/png,image/jpeg,image/gif,image/webp"
                  onchange={onFileChange}
                  bind:this={fileInput} />
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-status">{$_('common.status')}</label>
                  <div class="col-6">
                    <select
                      class="form-select form-select-sm"
                      id="product-status"
                      data-field="status"
                      bind:value={product.status}>
                      {#each STATUSES as status (status)}
                        <option value={status}
                          >{$_(`pages.create-product.statuses.${status}`)}</option>
                      {/each}
                    </select>
                  </div>
                </div>
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-kind">{$_('pages.create-product.kind')}</label>
                  <div class="col-6">
                    <select
                      class="form-select form-select-sm"
                      id="product-kind"
                      data-field="kind"
                      disabled={isEdit}
                      bind:value={product.kind}>
                      {#each KINDS as kind (kind)}
                        <option value={kind}>{$_(`pages.create-product.kinds.${kind}`)}</option>
                      {/each}
                    </select>
                  </div>
                </div>
                {#if isEdit}
                  <div class="form-text">{$_('pages.create-product.kind-locked')}</div>
                {/if}
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-featured">
                    {$_('pages.create-product.featured')}
                  </label>
                  <div class="col-6 d-flex justify-content-end">
                    <div class="form-check form-switch m-0">
                      <input
                        class="form-check-input"
                        type="checkbox"
                        role="switch"
                        id="product-featured"
                        bind:checked={product.featured} />
                    </div>
                  </div>
                </div>
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-physical">
                    {$_('pages.create-product.physical')}
                  </label>
                  <div class="col-6 d-flex justify-content-end">
                    <div class="form-check form-switch m-0">
                      <input
                        class="form-check-input"
                        type="checkbox"
                        role="switch"
                        id="product-physical"
                        data-field="physical"
                        disabled={product.kind !== 'STANDARD' || product.billingMode !== 'ONE_TIME'}
                        bind:checked={product.physical} />
                    </div>
                  </div>
                </div>
                {#if errors.physical}
                  <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.physical))}</div>
                {/if}
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-allow-gift">
                    {$_('pages.create-product.allow-gift')}
                  </label>
                  <div class="col-6 d-flex justify-content-end">
                    <div class="form-check form-switch m-0">
                      <input
                        class="form-check-input"
                        type="checkbox"
                        role="switch"
                        id="product-allow-gift"
                        bind:checked={product.allowGift} />
                    </div>
                  </div>
                </div>
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-category">
                    {$_('pages.create-product.category')}
                  </label>
                  <div class="col-6">
                    <select
                      class="form-select form-select-sm"
                      class:is-invalid={sideErrors.categories}
                      id="product-category"
                      data-field="categoryId"
                      bind:value={product.categoryId}>
                      <option value={null}>{$_('pages.create-product.uncategorized')}</option>
                      {#each categories as cat (cat.id)}
                        <option value={cat.id}>{cat.name}</option>
                      {/each}
                    </select>
                  </div>
                </div>
                {#if sideErrors.categories}
                  <div class="invalid-feedback d-block">
                    {$_('pages.create-product.side-load-failed')}
                    <button
                      type="button"
                      class="btn btn-link btn-sm p-0 align-baseline"
                      onclick={() => reloadSide('categories')}>
                      {$_('pages.create-product.reload')}
                    </button>
                  </div>
                {/if}
              </li>

              {#if category?.tiered}
                <li class="list-group-item">
                  <div class="row g-0 align-items-center">
                    <label class="col-6" for="product-tier-rank">
                      {$_('pages.create-product.tier-rank')}
                    </label>
                    <div class="col-6">
                      <input
                        type="number"
                        min="0"
                        step="1"
                        id="product-tier-rank"
                        data-field="tierRank"
                        class="form-control form-control-sm"
                        class:is-invalid={errors.tierRank}
                        placeholder="0"
                        bind:value={product.tierRank} />
                    </div>
                  </div>
                  {#if errors.tierRank}
                    <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.tierRank))}</div>
                  {/if}
                </li>
              {/if}

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">{$_('pages.create-product.icon')}</div>
                  <div class="col-6">
                    <IconPicker bind:value={product.icon} color="#0d6efd" placement="top-end" />
                  </div>
                </div>
              </li>

              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <label class="col-6" for="product-priority">
                    {$_('pages.create-product.priority')}
                  </label>
                  <div class="col-6">
                    <input
                      type="number"
                      id="product-priority"
                      class="form-control form-control-sm"
                      placeholder="0"
                      bind:value={product.priority} />
                  </div>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  {/if}
</MarketLayout>

<ConfirmModal bind:this={confirmModal} />
<StockModal bind:this={stockModal} onUpdated={onStockUpdated} />

{#snippet sideWarning(kind)}
  <div class="alert alert-warning d-flex align-items-start" role="alert">
    <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('pages.create-product.side-load-failed')}</b>
      <div>
        <button type="button" class="btn alert-btn mt-2" onclick={() => reloadSide(kind)}>
          {$_('pages.create-product.reload')}
        </button>
      </div>
    </div>
  </div>
{/snippet}

<script module>
  import { api } from '@panomc/sdk/plugin-api';
  import { failureOf, PANEL_URL } from '../utils/api.js';
  import { guard } from '../utils/guard.js';
  import { loadContextWith } from '../utils/list-core.js';
  import { pageOf } from '../utils/page.js';
  import { PLUGIN_ID } from '../utils/plugin.js';

  /**
   * GET /products/:id (edit), /categories, /servers, /products/simple and /context in parallel
   * (13 §8). A failed product GET is an error state (never a silent empty form); a failed side list
   * leaves the dependent control with an inline error and a Reload link.
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const allowed = await guard(event, ['CAT']);
    if (allowed.denied) return allowed.denied;
    allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.pages.create-product.title`);

    const id = event.url.searchParams.get('id');
    const get = (options) => api.panel.get(options);
    const ok = (res) => failureOf(res) === null;

    const [productRes, categoriesRes, serversRes, productsRes, ctx] = await Promise.all([
      id ? get({ path: `/products/${id}`, request: event }) : Promise.resolve(null),
      get({ path: '/categories', request: event }),
      get({ path: '/servers', request: event }),
      get({ path: '/products/simple', request: event }),
      loadContextWith({ get }, event),
    ]);

    let error = null;
    let product = null;
    if (id) {
      if (ok(productRes)) product = productRes.product ?? productRes;
      else error = failureOf(productRes);
    }

    return {
      data: {
        error,
        productId: id,
        product,
        ctx,
        categories: ok(categoriesRes) ? pageOf(categoriesRes).items : [],
        servers: ok(serversRes) ? (serversRes.items ?? []) : [],
        products: ok(productsRes) ? (productsRes.items ?? []) : [],
        sideErrors: {
          categories: !ok(categoriesRes),
          servers: !ok(serversRes),
          products: !ok(productsRes),
        },
      },
    };
  }
</script>

<script>
  import { tick, untrack } from 'svelte';
  import { DragAndDropZone } from '@panomc/sdk/components/panel';
  import { base, goto } from '@panomc/sdk/svelte';
  import { _, showErrorToast, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import IconPicker from '../components/IconPicker.svelte';
  import LoadError from '../components/LoadError.svelte';
  import StockModal from '../components/modals/StockModal.svelte';
  import ActionsTab from '../components/product/ActionsTab.svelte';
  import BundleTab from '../components/product/BundleTab.svelte';
  import FieldsTab from '../components/product/FieldsTab.svelte';
  import GeneralTab from '../components/product/GeneralTab.svelte';
  import LimitsTab from '../components/product/LimitsTab.svelte';
  import PricingTab from '../components/product/PricingTab.svelte';
  import ProvidersTab from '../components/product/ProvidersTab.svelte';
  import SeoTab from '../components/product/SeoTab.svelte';
  import ShippingTab from '../components/product/ShippingTab.svelte';
  import VariantsTab from '../components/product/VariantsTab.svelte';
  import {
    KINDS,
    STATUSES,
    TAB_IDS,
    applyRules,
    buildPayload,
    defaultProduct,
    fieldErrorKey,
    firstErrorPath,
    revealCandidates,
    fromApi,
    mapServerErrors,
    snapshot,
    tabOfPath,
    tabsWithErrors,
    validateProduct,
    visibleTabs,
  } from '../components/product/model.js';
  import { call, errorKey } from '../utils/api.js';


  let { data } = $props();

  // The host remounts this page on every load() run, so the data is read once.
  const ctx = untrack(() => data.ctx);
  const initial = untrack(() => (data.product ? fromApi(data.product, ctx) : defaultProduct(ctx)));

  const productMissing = $derived(data.error === 'NOT_FOUND');

  let product = $state(initial);
  let errors = $state({});
  let saving = $state(false);
  let activeTab = $state('general');
  let baseline = $state(null);

  let categoriesList = $state(untrack(() => data.categories ?? []));
  let serversList = $state(untrack(() => data.servers ?? []));
  let productsList = $state(untrack(() => data.products ?? []));
  let sideErrors = $state(untrack(() => ({ ...(data.sideErrors ?? {}) })));

  let existingImageFileName = $state(untrack(() => data.product?.imageFileName ?? null));
  let imageRemoved = $state(false);
  let selectedFile = $state(null);
  let previewUrl = $state(
    untrack(() =>
      data.product?.imageFileName
        ? `${PANEL_URL}/products/image/${data.product.imageFileName}`
        : null,
    ),
  );
  let fileInput = $state(null);

  let confirmModal = $state(null);
  let stockModal = $state(null);

  const isEdit = $derived(product.dbId !== null);
  const categories = $derived(flattenCategories(categoriesList));
  const category = $derived(categories.find((c) => c.id === product.categoryId) ?? null);
  const tabs = $derived(visibleTabs(product, ctx));
  const currentTab = $derived(tabs.includes(activeTab) ? activeTab : 'general');
  const errorTabs = $derived(tabsWithErrors(errors));
  const isDirty = $derived(
    baseline !== null && (snapshot(product) !== baseline || !!selectedFile || imageRemoved),
  );

  function flattenCategories(tree, out = []) {
    for (const cat of tree) {
      out.push({ id: cat.id, name: cat.name, tiered: cat.tiered === true });
      if (cat.children?.length) flattenCategories(cat.children, out);
    }
    return out;
  }

  // Fields that other fields force (kind, billing mode, category) are applied as the admin edits.
  $effect(() => {
    applyRules(product, ctx, sideErrors.categories ? undefined : category);
  });

  // The dirty baseline is captured after the editor has mounted: TipTap writes normalised HTML
  // back into `description` ('' -> '<p></p>') on creation.
  async function captureBaseline() {
    baseline = null;
    await tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    baseline = snapshot(product);
  }

  $effect(() => {
    untrack(() => {
      const hash = location.hash.slice(1);
      if (TAB_IDS.includes(hash)) activeTab = hash;
      captureBaseline();
    });
  });

  // beforeunload guard while there are unsaved changes (cleanup returned from the effect).
  $effect(() => {
    if (!isDirty) return;
    const handler = (event) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  });

  // ---- tabs ----

  function selectTab(id) {
    activeTab = id;
    try {
      history.replaceState(history.state, '', `#${id}`);
    } catch {
      /* the hash is only a convenience */
    }
  }

  function onTabKeydown(event, index) {
    let target = null;
    if (event.key === 'ArrowRight') target = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') target = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') target = 0;
    else if (event.key === 'End') target = tabs.length - 1;
    if (target === null) return;
    event.preventDefault();
    document.getElementById(`product-tab-${tabs[target]}`)?.focus();
  }

  /** Switches to the tab of an error path, then focuses its input. */
  async function reveal(path) {
    if (!path) return;
    const tab = tabOfPath(path);
    if (tab && tabs.includes(tab) && tab !== currentTab) selectTab(tab);
    await tick();
    let holder = null;
    for (const name of revealCandidates(path)) {
      holder = document.querySelector(`[data-field="${name}"]`);
      if (holder) break;
    }
    if (!holder) return;
    const control = holder.matches('input,select,textarea,button')
      ? holder
      : holder.querySelector('input,select,textarea,button');
    (control ?? holder).scrollIntoView?.({ block: 'center' });
    control?.focus?.();
  }

  // ---- side lists ----

  async function reloadSide(kind) {
    const path = { categories: '/categories', servers: '/servers', products: '/products/simple' }[
      kind
    ];
    const result = await call(api.panel.get({ path }));
    if (!result.ok) {
      showErrorToast($_(errorKey(result.error)));
      return;
    }
    if (kind === 'categories') categoriesList = pageOf(result.body).items;
    else if (kind === 'servers') serversList = result.body.items ?? [];
    else productsList = result.body.items ?? [];
    sideErrors[kind] = false;
  }

  // ---- image ----

  function processFile(file) {
    selectedFile = file;
    imageRemoved = false;
    const reader = new FileReader();
    reader.onload = (e) => {
      previewUrl = e.target.result;
    };
    reader.readAsDataURL(file);
  }

  function onRemoveImage() {
    selectedFile = null;
    previewUrl = null;
    if (existingImageFileName) imageRemoved = true;
    if (fileInput) fileInput.value = '';
  }

  function onFileChange(event) {
    const file = event.target.files[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      showErrorToast($_('pages.create-product.image-error'));
      return;
    }
    processFile(file);
  }

  // ---- stock ----

  function openStock(variant) {
    stockModal?.open({
      productId: product.dbId,
      variants: product.variants,
      variantId: variant?.id ?? 0,
    });
  }

  function onStockUpdated({ variantId, stock }) {
    const wasDirty = isDirty;
    if (variantId) {
      const row = product.variants.find((v) => v.id === variantId);
      if (row) row.stock = stock;
    } else {
      product.hasStockLimit = stock !== null;
      product.stock = stock ?? 0;
    }
    // The server already holds the new value: it is not an unsaved change.
    if (!wasDirty) baseline = snapshot(product);
  }

  // ---- save ----

  async function refresh() {
    const result = await call(api.panel.get({ path: `/products/${product.dbId}` }));
    if (!result.ok) {
      showErrorToast($_(errorKey(result.error)));
      return;
    }
    const record = result.body.product ?? result.body;
    product = fromApi(record, ctx);
    existingImageFileName = record.imageFileName ?? null;
    previewUrl = existingImageFileName
      ? `${PANEL_URL}/products/image/${existingImageFileName}`
      : null;
    selectedFile = null;
    imageRemoved = false;
    if (fileInput) fileInput.value = '';
    await captureBaseline();
  }

  function failed(result, pathMap) {
    if (result.error === 'INVALID_PRODUCT') {
      errors = mapServerErrors(result.body?.fieldErrors, pathMap);
      showErrorToast($_('pages.create-product.validation-failed'));
      reveal(firstErrorPath(errors));
    } else if (result.error === 'SLUG_ALREADY_EXISTS' || result.error === 'RESERVED_SLUG') {
      errors = { slug: result.error };
      reveal('slug');
    } else {
      showErrorToast($_(errorKey(result.error)));
    }
  }

  async function handleSave() {
    if (saving) return;
    const checked = validateProduct(product, ctx, {
      category: sideErrors.categories ? null : category,
      isEdit,
    });
    errors = checked.errors;
    if (Object.keys(checked.errors).length > 0) {
      showErrorToast($_('pages.create-product.validation-failed'));
      await reveal(firstErrorPath(checked.errors));
      return;
    }

    saving = true;
    try {
      const payload = buildPayload($state.snapshot(product), ctx, {
        isEdit,
        imageFile: selectedFile,
        removeImage: imageRemoved,
      });
      const formData = new FormData();
      for (const [name, value] of payload.scalars) formData.append(name, value);
      for (const [part, value] of Object.entries(payload.json))
        formData.append(part, JSON.stringify(value));
      for (const { part, file } of payload.files) formData.append(part, file);

      const result = await call(
        isEdit
          ? api.panel.put({
              path: `/products/${product.dbId}`,
              body: formData,
              headers: {},
            })
          : api.panel.post({ path: '/products', body: formData, headers: {} }),
      );

      if (!result.ok) {
        failed(result, payload.pathMap);
        return;
      }

      showSuccessToast($_(`pages.create-product.${isEdit ? 'update' : 'create'}-success`));
      if (isEdit) {
        await refresh();
      } else {
        baseline = snapshot(product);
        selectedFile = null;
        imageRemoved = false;
        const id = result.body.id;
        goto(id ? `${base}/market/products/create-product?id=${id}` : `${base}/market/products`);
      }
    } catch (e) {
      console.error('[Market] Failed to save product', e);
      showErrorToast($_('pages.create-product.save-error'));
    } finally {
      saving = false;
    }
  }

  function deleteProduct() {
    if (!isEdit) return;
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      title: $_('pages.create-product.delete-title'),
      description: $_('pages.create-product.delete-confirm', { values: { name: product.name } }),
      confirmLabel: $_('common.delete'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(
          api.panel.delete({ path: `/products/${product.dbId}` }),
        );
        if (!result.ok) {
          showErrorToast($_('pages.create-product.delete-error'));
          return false;
        }
        showSuccessToast($_('pages.create-product.delete-success'));
        baseline = snapshot(product);
        selectedFile = null;
        imageRemoved = false;
        goto(`${base}/market/products`);
      },
    });
  }
</script>
