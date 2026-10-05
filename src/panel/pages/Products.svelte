<MarketLayout area="catalog" sections={sectionsFor('catalog', user)} active="products">
  {#snippet right()}
    <a href="{base}/market/products/create-product" class="btn btn-primary">
      <i class="fa-solid fa-plus" aria-hidden="true"></i>
      <span class="d-lg-inline d-none ms-2">{$_('pages.products.add-product')}</span>
    </a>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.products.count', { values: { count: productCount } })}
        </div>
        <div slot="middle" style="width: 250px;">
          <SearchInput
            autofocus
            initialValue={filters.search}
            searching={$navigating !== null}
            debounceMs={300}
            placeholderKey="plugins.pano-plugin-market.search.products"
            onchange={(value) => go({ search: value })} />
        </div>
        <div slot="right" class="d-flex align-items-center gap-3">
          <CardFilters>
            <CardFiltersItem
              button
              active={filters.status === ''}
              onclick={() => go({ status: null })}>
              {$_('common.all')}
            </CardFiltersItem>
            {#each STATUS_FILTERS as status (status)}
              <CardFiltersItem
                button
                active={filters.status === status}
                onclick={() => go({ status })}>
                {$_(`pages.create-product.statuses.${status}`)}
              </CardFiltersItem>
            {/each}
          </CardFilters>
          <div class="vr d-none d-md-block"></div>
          <CardFilters>
            {#each KIND_FILTERS as kind (kind)}
              <CardFiltersItem
                button
                active={filters.kind === kind}
                onclick={() => go({ kind: filters.kind === kind ? null : kind })}>
                {$_(`enums.product-kind.${kind}`)}
              </CardFiltersItem>
            {/each}
            {#if hasExtraFilters(filters)}
              <CardFiltersItem button onclick={() => go({ kind: null, categoryId: null })}>
                {$_('common.clear-filters')}
              </CardFiltersItem>
            {/if}
          </CardFilters>
        </div>
      </CardHeader>

      {#if products.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.products.table.name')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.products.table.type')}
                </th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.products.table.category')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.products.table.price')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.products.table.stock')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.products.table.sold')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each products as product (product.id)}
                {@const stock = stockCell(product)}
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
                          onclick={() => editProduct(product.id)}>
                          <i class="fas fa-pen me-2" aria-hidden="true"></i>
                          {$_('common.edit')}
                        </button>
                        <button
                          type="button"
                          class="dropdown-item"
                          disabled={busy}
                          onclick={() => cloneProduct(product.id)}>
                          <i class="fas fa-clone me-2" aria-hidden="true"></i>
                          {$_('common.clone')}
                        </button>
                        <button
                          type="button"
                          class="dropdown-item"
                          disabled={busy}
                          onclick={() => adjustStock(product)}>
                          <i class="fas fa-boxes-stacked me-2" aria-hidden="true"></i>
                          {$_('pages.create-product.adjust-stock')}
                        </button>
                        <button
                          type="button"
                          class="dropdown-item link-danger"
                          disabled={busy}
                          onclick={() => deleteProduct(product)}>
                          <i class="fas fa-trash me-2" aria-hidden="true"></i>
                          {$_('common.delete')}
                        </button>
                      </div>
                    </div>
                  </th>
                  <td class="align-middle">
                    <div class="d-flex align-items-center gap-2">
                      {#if product.imageFileName}
                        <img
                          src={imageUrl(product.imageFileName)}
                          alt=""
                          width="40"
                          height="40"
                          loading="lazy"
                          class="rounded object-fit-cover flex-shrink-0" />
                      {:else}
                        <div
                          class="d-flex align-items-center justify-content-center bg-body-secondary rounded flex-shrink-0"
                          style="width: 40px; height: 40px;">
                          <i
                            class="fas {product.icon || 'fa-box'} text-body-secondary"
                            aria-hidden="true"></i>
                        </div>
                      {/if}
                      <div class="vstack">
                        <a
                          class="text-decoration-none fw-medium"
                          href="{base}/market/products/create-product?id={product.id}">
                          {product.name}
                        </a>
                        <span class="text-body-secondary small">ID: #{product.id}</span>
                      </div>
                    </div>
                  </td>
                  <td class="align-middle">
                    <div class="d-flex flex-wrap gap-1">
                      {#each typeBadges(product) as badge (badge.key)}
                        <span class="badge text-bg-{badge.tone}">{$_(badge.key)}</span>
                      {/each}
                    </div>
                  </td>
                  <td class="align-middle">
                    <span class="badge text-bg-{statusTone(product.status)}">
                      {$_(`pages.create-product.statuses.${product.status}`)}
                    </span>
                  </td>
                  <td class="align-middle">
                    {product.categoryName || $_('pages.products.uncategorized')}
                  </td>
                  <td class="align-middle text-nowrap">
                    {fmt.money(product.price, currency)}
                    {#if product.compareAtPrice !== null && product.compareAtPrice !== undefined}
                      <div class="text-body-secondary text-decoration-line-through small">
                        {fmt.money(product.compareAtPrice, currency)}
                      </div>
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">
                    {#if stock.unlimited}
                      {$_('common.unlimited')}
                    {:else}
                      {$_('pages.products.stock-count', { values: { count: stock.count } })}
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">{product.soldCount ?? 0}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>

        {#if totalPage > 1}
          <div class="card-footer">
            <Pagination
              page={currentPage}
              {totalPage}
              on:firstPageClick={() => gotoPage(1)}
              on:lastPageClick={() => gotoPage(totalPage)}
              on:pageLinkClick={(event) => gotoPage(event.detail.page)} />
          </div>
        {/if}
      {/if}
    </div>
  {/if}
</MarketLayout>

<ConfirmModal bind:this={confirmModal} />
<StockModal bind:this={stockModal} onUpdated={onStockUpdated} />

<script module>
  import { loadList } from '../utils/list.js';
  import { PRODUCT_PARAMS } from '../components/products/filters.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/products',
      params: PRODUCT_PARAMS,
      nodes: ['CAT'],
      emptyKey: 'products',
      title: 'pages.products.title',
    });
  }
</script>

<script>
  import {
    CardHeader,
    CardFilters,
    CardFiltersItem,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { base, goto, navigating, page } from '@panomc/sdk/svelte';
  import { _, showErrorToast, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import StockModal from '../components/modals/StockModal.svelte';
  import {
    KIND_FILTERS,
    STATUS_FILTERS,
    hasExtraFilters,
    listParams,
    normalizeFilters,
    pageAfterDelete,
    statusTone,
    stockCell,
    typeBadges,
  } from '../components/products/filters.js';
  import { sectionsFor } from '../navigation.js';
  import { call, errorKey, errorParams, marketPath } from '../utils/api.js';
  import { gotoList } from '../utils/list.js';
  import { fmt } from '../utils/locale.js';

  let { data } = $props();

  let confirmModal = $state(null);
  let stockModal = $state(null);
  let busy = $state(false);

  const user = $derived($page.data?.user);
  const filters = $derived(normalizeFilters(data.filters));
  const products = $derived(data.products ?? []);
  const productCount = $derived(data.productCount ?? data.count ?? 0);
  const totalPage = $derived(data.totalPage ?? 1);
  const currentPage = $derived(data.page ?? 1);
  const currency = $derived(data.ctx?.currency ?? null);

  // The URL is the source of truth; load() re-runs on every navigation. A filter or search change
  // always drops `page`.
  function go(overrides) {
    return gotoList('/market/products', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/products', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }

  function reload(pageNum = currentPage) {
    return pageNum > 1 ? gotoPage(pageNum) : go({});
  }

  // A modal is hidden before the page is re-loaded (13 §1.4); Bootstrap's fade takes 300 ms.
  function afterModalHidden(run) {
    setTimeout(run, 350);
  }

  function imageUrl(fileName) {
    return `${base}/api/panel/market/products/image/${encodeURIComponent(fileName)}?thumbnail=true`;
  }

  function editProduct(id) {
    goto(`${base}/market/products/create-product?id=${id}`);
  }

  async function cloneProduct(id) {
    if (busy) return;
    busy = true;
    const result = await call(ApiUtil.post({ path: marketPath(`/products/${id}/clone`) }));
    busy = false;
    if (!result.ok) {
      showErrorToast($_(errorKey(result.error)), errorParams(result.error, result.body));
      return;
    }
    showSuccessToast($_('pages.products.toast-clone-success'));
    await reload();
  }

  // Adjust Stock needs the variants of the product, which only GET /products/:id returns.
  async function adjustStock(product) {
    if (busy) return;
    busy = true;
    const result = await call(ApiUtil.get({ path: marketPath(`/products/${product.id}`) }));
    busy = false;
    if (!result.ok) {
      showErrorToast($_(errorKey(result.error)), errorParams(result.error, result.body));
      return;
    }
    const record = result.body.product ?? result.body;
    stockModal?.open({ productId: product.id, variants: record.variants ?? [], variantId: 0 });
  }

  function onStockUpdated() {
    afterModalHidden(() => reload());
  }

  function deleteProduct(product) {
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      title: $_('pages.products.delete-title'),
      description: $_('pages.products.confirm-delete', { values: { name: product.name } }),
      confirmLabel: $_('common.delete'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(ApiUtil.delete({ path: marketPath(`/products/${product.id}`) }));
        if (!result.ok) {
          showErrorToast($_(errorKey(result.error)), errorParams(result.error, result.body));
          return false;
        }
        showSuccessToast($_('pages.products.toast-delete-success'));
        const target = pageAfterDelete(products.length, currentPage);
        afterModalHidden(() => reload(target));
      },
    });
  }
</script>
