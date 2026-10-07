<div class="vstack gap-3 animate__animated animate__fadeIn">
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.create-product.bundle.title', { values: { count: product.bundleItems.length } })}
      </div>
      <div slot="right">
        <button
          type="button"
          class="btn btn-sm btn-link"
          disabled={product.bundleItems.length >= MAX_BUNDLE_ROWS}
          onclick={openModal}>
          <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
          {$_('pages.create-product.bundle.add')}
        </button>
      </div>
    </CardHeader>

    {#if errors.bundleItems}
      <div class="card-body pb-0">
        <div class="invalid-feedback d-block" data-field="bundleItems">
          {$_(fieldErrorKey(errors.bundleItems))}
        </div>
      </div>
    {/if}

    {#if product.bundleItems.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover">
          <thead>
            <tr>
              <th class="align-middle text-nowrap" scope="col"></th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.bundle.product')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.bundle.variant')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.bundle.quantity')}
              </th>
            </tr>
          </thead>
          <tbody>
            {#each product.bundleItems as row, i (`${row.productId}:${row.variantId ?? 0}`)}
              <tr>
                <th class="align-middle" scope="row">
                  <div class="dropdown position-static">
                    <button
                      type="button"
                      class="btn btn-link"
                      data-bs-toggle="dropdown"
                      aria-expanded="false"
                      title={$_('common.actions')}
                      aria-label={$_('common.actions')}>
                      <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                    </button>
                    <div
                      class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={i === 0}
                        onclick={() => move(i, -1)}>
                        {$_('common.move-up')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={i === product.bundleItems.length - 1}
                        onclick={() => move(i, 1)}>
                        {$_('common.move-down')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item link-danger"
                        onclick={() => remove(i)}>
                        {$_('common.remove')}
                      </button>
                    </div>
                  </div>
                </th>
                <td class="align-middle">{productName(row)}</td>
                <td class="align-middle">
                  {#if row.variantId}
                    {row.variantName || `#${row.variantId}`}
                  {:else}
                    <span class="text-body-secondary">-</span>
                  {/if}
                  {#if errors[`bundleItems.${i}.variantId`]}
                    <div class="invalid-feedback d-block" data-field="bundleItems.{i}.variantId">
                      {$_(fieldErrorKey(errors[`bundleItems.${i}.variantId`]))}
                    </div>
                  {/if}
                </td>
                <td style="max-width: 9rem;">
                  <input
                    type="number"
                    min="1"
                    max="99"
                    step="1"
                    class="form-control"
                    class:is-invalid={errors[`bundleItems.${i}.quantity`]}
                    data-field="bundleItems.{i}.quantity"
                    placeholder="1"
                    aria-label={$_('pages.create-product.bundle.quantity')}
                    bind:value={row.quantity} />
                  {#if errors[`bundleItems.${i}.quantity`]}
                    <div class="invalid-feedback d-block">
                      {$_(fieldErrorKey(errors[`bundleItems.${i}.quantity`]))}
                    </div>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </div>
</div>

<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('pages.create-product.bundle.add')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          {#if candidates.length === 0}
            <div class="text-center text-body-secondary py-3">
              {$_('components.product-selector.empty')}
            </div>
          {:else}
            <ProductSelector
              products={candidates}
              bind:selected={selectedId}
              placeholder={$_('pages.create-product.bundle.search')} />
          {/if}

          {#if selectedChild?.hasVariants}
            {#if loadingVariants}
              <div class="text-center text-body-secondary">
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                {$_('common.loading')}
              </div>
            {:else}
              <select
                class="form-select"
                class:is-invalid={modalErrors.variant}
                aria-label={$_('pages.create-product.bundle.variant')}
                bind:value={selectedVariantId}>
                <option value={0}>{$_('pages.create-product.bundle.choose-variant')}</option>
                {#each childVariants as variant (variant.id)}
                  <option value={variant.id}>{variant.name}</option>
                {/each}
              </select>
              {#if modalErrors.variant}
                <div class="invalid-feedback d-block">
                  {$_(fieldErrorKey(modalErrors.variant))}
                </div>
              {/if}
            {/if}
          {/if}

          <input
            type="number"
            min="1"
            max="99"
            step="1"
            class="form-control"
            class:is-invalid={modalErrors.quantity}
            placeholder={$_('pages.create-product.bundle.quantity')}
            aria-label={$_('pages.create-product.bundle.quantity')}
            bind:value={quantity} />
          {#if modalErrors.quantity}
            <div class="invalid-feedback d-block">{$_(fieldErrorKey(modalErrors.quantity))}</div>
          {/if}
        </div>
        <div class="modal-footer">
          <button
            class="btn btn-primary w-100"
            type="submit"
            disabled={selectedId === null || loadingVariants}>
            {$_('pages.create-product.bundle.add-cta')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';
  import { marketPath } from '../../utils/api.js';
  import { moveItem } from '../../utils/variants.js';
  import ProductSelector from '../ProductSelector.svelte';
  import { MAX_BUNDLE_ROWS, addBundleRow, bundleCandidates, fieldErrorKey } from './model.js';

  // products: the /products/simple rows loaded with the page (kind, billingMode, hasVariants).
  let { product = $bindable(), errors = {}, products = [] } = $props();

  let modalElement = $state(null);
  let selectedId = $state(null);
  let selectedVariantId = $state(0);
  let quantity = $state(1);
  let childVariants = $state([]);
  let loadingVariants = $state(false);
  let modalErrors = $state({});

  const candidates = $derived(bundleCandidates(products, product.dbId));
  const selectedChild = $derived(candidates.find((p) => p.id === selectedId) ?? null);

  function productName(row) {
    return row.name || products.find((p) => p.id === row.productId)?.name || `#${row.productId}`;
  }

  function move(index, delta) {
    product.bundleItems = moveItem(product.bundleItems, index, delta);
  }

  function remove(index) {
    product.bundleItems = product.bundleItems.filter((_row, i) => i !== index);
  }

  function openModal() {
    selectedId = null;
    selectedVariantId = 0;
    quantity = 1;
    childVariants = [];
    modalErrors = {};
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
  }

  // A child with variants needs the variant list of GET /products/:id; only ACTIVE rows are offered.
  $effect(() => {
    const child = selectedChild;
    selectedVariantId = 0;
    childVariants = [];
    if (!child?.hasVariants) return;
    let cancelled = false;
    loadingVariants = true;
    (async () => {
      let res = null;
      try {
        res = await ApiUtil.get({ path: marketPath(`/products/${child.id}`) });
      } catch {
        res = null;
      }
      if (cancelled) return;
      const loaded = res && !res.error ? (res.product ?? res) : null;
      childVariants = (loaded?.variants ?? []).filter((v) => v.status !== 'INACTIVE');
      loadingVariants = false;
    })();
    return () => {
      cancelled = true;
      loadingVariants = false;
    };
  });

  function submit(event) {
    event.preventDefault();
    modalErrors = {};
    const child = selectedChild;
    if (!child) return;
    const n = Number(quantity);
    if (quantity === null || quantity === '' || !Number.isInteger(n)) {
      modalErrors = { quantity: quantity === null || quantity === '' ? 'REQUIRED' : 'NOT_INTEGER' };
      return;
    }
    if (n < 1 || n > 99) {
      modalErrors = { quantity: 'OUT_OF_RANGE' };
      return;
    }
    if (child.hasVariants && !selectedVariantId) {
      modalErrors = { variant: 'REQUIRED' };
      return;
    }
    const variant = childVariants.find((v) => v.id === selectedVariantId);
    product.bundleItems = addBundleRow(product.bundleItems, {
      productId: child.id,
      variantId: child.hasVariants ? selectedVariantId : 0,
      quantity: n,
      name: child.name,
      variantName: variant?.name ?? '',
      hasVariants: !!child.hasVariants,
    });
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
