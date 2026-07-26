<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.products.title');

    const pageNum = parseInt(searchParams.get('page')) || 1;
    const search = searchParams.get('search');
    const statusParam = searchParams.get('status');
    const status = statusParam === 'ACTIVE' || statusParam === 'INACTIVE' ? statusParam : null;

    const fetchPage = (p) =>
      ApiUtil.get({
        path:
          '/api/panel/market/products' +
          buildQueryParams({
            page: p === 1 ? null : p,
            search: search || null,
            status,
          }),
        request: event,
      });

    let effectivePage = pageNum;
    // Fetch the first products page alongside market settings (for the SALES
    // currency symbol shown in the price column).
    let [body, settingsRes] = await Promise.all([
      fetchPage(pageNum),
      ApiUtil.get({ path: '/api/panel/market/settings', request: event }),
    ]);

    const currencySymbol =
      settingsRes && !settingsRes.error ? settingsRes.currencySymbol || '' : '';

    // A stale ?page= (bookmark / back-button after deletes) points past the last
    // page; fall back to page 1 with the same filters instead of faking an empty store.
    if (body?.error === 'PAGE_NOT_FOUND' && pageNum > 1) {
      effectivePage = 1;
      body = await fetchPage(1);
    }

    if (!body || body.error) {
      return {
        data: {
          products: [],
          productCount: 0,
          totalPage: 1,
          page: 1,
          currencySymbol,
          error: body?.error || 'NETWORK_ERROR',
        },
      };
    }

    body.page = effectivePage;
    body.currencySymbol = currencySymbol;
    return { data: body };
  }
</script>

<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput, NoContent } from '@panomc/sdk/components/panel';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { _, showSuccessToast, showErrorToast } from '../../i18n';

  let { data } = $props();

  // Data comes straight from load(); the panel host remounts this view
  // ({#key data}) whenever load() re-runs, so we render load()'s result directly.
  let products = $derived(data.products || []);
  let productCount = $derived(data.productCount || 0);
  let totalPage = $derived(data.totalPage || 1);
  let currentPage = $derived(data.page || 1);
  let loadError = $derived(data.error || null);

  // SALES-currency symbol from GET /settings (fetched in load()); threaded into
  // the price formatter. Never hardcode a currency symbol.
  let currencySymbol = $derived(data.currencySymbol || '');

  // All list state (page / search / status) lives in the URL query params.
  let currentSearch = $derived($page.url.searchParams.get('search') || '');
  let currentStatus = $derived($page.url.searchParams.get('status') || 'ALL');

  let isSearching = $state(false);
  let buttonsLoading = $state(false);

  // Merge the current URL state with overrides into a products query string.
  // page 1 / status ALL / empty search are omitted (buildQueryParams drops falsy).
  function buildQuery({ page: pageNum = currentPage, search = currentSearch, status = currentStatus } = {}) {
    return buildQueryParams({
      page: pageNum && Number(pageNum) > 1 ? pageNum : null,
      search: search || null,
      status: status && status !== 'ALL' ? status : null,
    });
  }

  // Navigate to the products route with the merged query; invalidateAll re-runs load().
  function navigate(overrides = {}) {
    return goto(`${base}/market/products${buildQuery(overrides)}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  // href for a status pill: preserves the current search and resets to page 1.
  function statusHref(status) {
    return `/market/products${buildQueryParams({
      search: currentSearch || null,
      status: status === 'ALL' ? null : status,
    })}`;
  }

  async function onSearchChange(val) {
    isSearching = true;
    try {
      await navigate({ page: 1, search: val });
    } finally {
      isSearching = false;
    }
  }

  function onPageClick(pageNum) {
    return navigate({ page: pageNum });
  }

  function editProduct(id) {
    goto(`${base}/market/products/create-product?id=${id}`);
  }

  async function cloneProduct(id) {
    if (buttonsLoading) return;
    buttonsLoading = true;
    try {
      const res = await ApiUtil.post({ path: `/api/panel/market/products/${id}/clone` });
      if (res?.error) {
        showErrorToast($_('pages.products.toast-clone-error'));
      } else {
        showSuccessToast($_('pages.products.toast-clone-success'));
        await navigate();
      }
    } catch (e) {
      console.error('[Market] Failed to clone product', e);
      showErrorToast($_('pages.products.toast-clone-error'));
    } finally {
      buttonsLoading = false;
    }
  }

  async function deleteProduct(product) {
    if (buttonsLoading) return;
    if (!window.confirm($_('pages.products.confirm-delete', { values: { name: product.name } }))) return;
    buttonsLoading = true;
    try {
      const res = await ApiUtil.delete({ path: `/api/panel/market/products/${product.id}` });
      if (res?.error) {
        showErrorToast($_('pages.products.toast-delete-error'));
      } else {
        showSuccessToast($_('pages.products.toast-delete-success'));
        // If we removed the last row on a non-first page, step back a page.
        const targetPage = products.length === 1 && currentPage > 1 ? currentPage - 1 : currentPage;
        await navigate({ page: targetPage });
      }
    } catch (e) {
      console.error('[Market] Failed to delete product', e);
      showErrorToast($_('pages.products.toast-delete-error'));
    } finally {
      buttonsLoading = false;
    }
  }

  function formatPrice(value, symbol) {
    const formatted = Number(value || 0).toFixed(2);
    return symbol ? `${formatted} ${symbol}` : formatted;
  }

  function imageUrl(fileName) {
    return `${base}/api/panel/market/products/image/${fileName}?thumbnail=true`;
  }
</script>
<MarketLayout>
  {#snippet right()}
    <a href="{base}/market/products/create-product" class="btn btn-secondary">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">{$_('pages.products.add-product')}</span>
    </a>
  {/snippet}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.products.count', { values: { count: productCount } })}
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={currentSearch}
          searching={isSearching}
          placeholderKey="plugins.pano-plugin-market.search.products"
          onchange={onSearchChange} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem href={statusHref('ALL')} active={currentStatus === 'ALL'}>{$_('common.all')}</CardFiltersItem>
        <CardFiltersItem href={statusHref('ACTIVE')} active={currentStatus === 'ACTIVE'}>{$_('common.active')}</CardFiltersItem>
        <CardFiltersItem href={statusHref('INACTIVE')} active={currentStatus === 'INACTIVE'}>{$_('common.inactive')}</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if loadError}
      <div class="text-center text-body-secondary py-5">
        <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
        <div>{$_('pages.products.load-error')}</div>
      </div>
    {:else if products.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col" style="width: 60px;"></th>
              <th scope="col" class="text-nowrap" style="width: 60px;">{$_('pages.products.table.icon')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.products.table.thumbnail')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.products.table.name')}</th>
              <th scope="col" class="text-nowrap">{$_('common.status')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.products.table.category')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.products.table.price')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.products.table.stock')}</th>
            </tr>
          </thead>
          <tbody>
            {#each products as product (product.id)}
              <tr>
                <th scope="row">
                  <div class="dropdown position-static">
                    <button
                      type="button"
                      class="btn btn-link"
                      data-bs-toggle="dropdown"
                      title={$_('common.actions')}
                      aria-label={$_('common.actions')}>
                      <span class="fas fa-ellipsis-v"></span>
                    </button>
                    <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                      <button type="button" class="dropdown-item" onclick={() => editProduct(product.id)}>
                        <i class="fas fa-pen me-2"></i>
                        {$_('common.edit')}
                      </button>
                      <button type="button" class="dropdown-item" onclick={() => cloneProduct(product.id)}>
                        <i class="fas fa-clone me-2"></i>
                        {$_('common.clone')}
                      </button>
                      <button type="button" class="dropdown-item text-danger" onclick={() => deleteProduct(product)}>
                        <i class="fas fa-trash me-2"></i>
                        {$_('common.delete')}
                      </button>
                    </div>
                  </div>
                </th>
                <td>
                  <div class="d-flex align-items-center justify-content-center bg-body-secondary rounded-circle" style="width: 36px; height: 36px;">
                    <i class="fas {product.icon || 'fa-box'} text-body-secondary"></i>
                  </div>
                </td>
                <td>
                  <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden" style="width: 40px; height: 40px;">
                    {#if product.imageFileName}
                      <img src={imageUrl(product.imageFileName)} alt={product.name} class="w-100 h-100 object-fit-cover" />
                    {:else}
                      <!-- Premium vector box icon representing default product package -->
                      <svg class="w-100 h-100 p-2 text-primary opacity-75" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                        <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="opacity-50"/>
                        <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="opacity-75"/>
                      </svg>
                    {/if}
                  </div>
                </td>
                <td>
                  <div class="vstack gap-0">
                    <button
                      type="button"
                      title={$_('common.edit')}
                      class="btn btn-link p-0 border-0 text-decoration-none text-start fw-medium focus-ring"
                      onclick={() => editProduct(product.id)}>
                      {product.name}
                    </button>
                    <span class="text-body-secondary small">ID: #{product.id}</span>
                  </div>
                </td>
                <td>
                  {#if product.status === 'ACTIVE'}
                    <span class="badge text-bg-success">{$_('common.active')}</span>
                  {:else if product.status === 'HIDDEN'}
                    <span class="badge text-bg-secondary">{$_('common.hidden')}</span>
                  {:else}
                    <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                  {/if}
                </td>
                <td>
                  <span class="badge text-bg-primary fw-medium border-0">{product.categoryName || $_('pages.products.uncategorized')}</span>
                </td>
                <td class="">
                  {formatPrice(product.price, currencySymbol)}
                </td>
                <td>
                  <span class="badge text-bg-primary fw-medium border-0">
                    {product.stock === null || product.stock === undefined ? $_('common.unlimited') : $_('pages.products.stock-count', { values: { count: product.stock } })}
                  </span>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>

      <div class="card-footer">
        <Pagination
          page={currentPage}
          {totalPage}
          on:firstPageClick={() => onPageClick(1)}
          on:lastPageClick={() => onPageClick(totalPage)}
          on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
    {/if}
  </div>
</MarketLayout>
