<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams }
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.orders.title');

    const pageNum = parseInt(searchParams.get('page')) || 1;
    const search = searchParams.get('search');
    const status = searchParams.get('status');

    const fetchPage = (p) =>
      ApiUtil.get({
        path:
          '/api/panel/market/orders' +
          buildQueryParams({
            page: p === 1 ? null : p,
            search,
            status
          }),
        request: event
      });

    let effectivePage = pageNum;

    // SALES SYMBOL DELIVERY RULE: fetch the market settings alongside the main
    // data so the sales-currency symbol is available without a hardcoded ₺.
    let [body, settings] = await Promise.all([
      fetchPage(pageNum),
      ApiUtil.get({ path: '/api/panel/market/settings', request: event })
    ]);

    // A stale ?page= (bookmark / back-button) points past the last page; fall
    // back to page 1 with the same filters instead of faking an empty store.
    if (body?.error === 'PAGE_NOT_FOUND' && pageNum > 1) {
      effectivePage = 1;
      body = await fetchPage(1);
    }

    const currencySymbol = settings?.currencySymbol || '';

    if (!body || body.error) {
      return {
        data: {
          orders: [],
          orderCount: 0,
          totalPage: 1,
          page: 1,
          currencySymbol,
          error: body?.error || 'NETWORK_ERROR'
        }
      };
    }

    body.page = effectivePage;
    body.currencySymbol = currencySymbol;
    return { data: body };
  }
</script>

<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput, NoContent, Date as DateComponent } from '@panomc/sdk/components/panel';
  import { base, page, goto } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import OrderDetailModal from '../components/modals/OrderDetailModal.svelte';

  let { data } = $props();

  // Currency code -> symbol. Historical orders may carry a currency that differs
  // from the current sales currency, so each row is shown in its own symbol
  // rather than the global one.
  const CURRENCY_SYMBOLS = { TRY: '₺', USD: '$', EUR: '€', GBP: '£' };

  // SALES SYMBOL DELIVERY RULE: seeded from load()'s /settings fetch, used as the
  // fallback symbol for orders that don't carry an explicit currency code.
  let currencySymbol = $derived(data.currencySymbol || '');

  // The order whose detail modal is currently targeted.
  let selectedOrderId = $state(null);

  // All list data comes from load(); re-derives whenever load() re-runs.
  let orders = $derived(data.orders || []);
  let orderCount = $derived(data.orderCount || 0);
  let totalPage = $derived(data.totalPage || 1);
  let currentPage = $derived(data.page || 1);
  let loadError = $derived(data.error || null);

  // Status filter and search are read straight from the URL that produced
  // load()'s data, so the address bar is the single source of truth and stays
  // deep-linkable / back-button correct.
  let currentStatus = $derived($page.url.searchParams.get('status') || 'all');
  let searchQuery = $derived($page.url.searchParams.get('search') || '');

  let isSearching = $state(false);

  // backend OrderStatus name -> badge display
  const ORDER_STATUS = {
    COMPLETED: { key: 'pages.orders.status-completed', cls: 'text-bg-success' },
    REFUNDED: { key: 'pages.orders.status-refunded', cls: 'text-bg-danger' },
    PENDING: { key: 'pages.orders.status-pending', cls: 'text-bg-warning' },
    FAILED: { key: 'pages.orders.status-failed', cls: 'text-bg-danger' }
  };

  function formatMoney(value, symbol) {
    const formatted = (Number(value) || 0).toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
    return symbol ? `${formatted} ${symbol}` : formatted;
  }

  function openDetail(id) {
    selectedOrderId = id;
  }

  // Navigate to the same route with the list state encoded in the URL so the
  // address bar stays deep-linkable and the back button works; load() re-runs
  // with the new params. The panel host remounts the plugin page on every
  // load() re-run ({#key data}); that remount is the accepted cost here.
  async function navigate({
    page: pageNum = currentPage,
    search: searchVal = searchQuery,
    status: statusVal = currentStatus
  } = {}) {
    isSearching = true;

    const queryParams = buildQueryParams({
      page: pageNum && pageNum !== 1 ? pageNum : null,
      search: searchVal ? searchVal.trim() || null : null,
      status: statusVal && statusVal !== 'all' ? statusVal : null
    });

    await goto(`${base}/market/orders${queryParams}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true
    });
  }

  function onPageClick(pageNum) {
    navigate({ page: pageNum });
  }

  function setStatusFilter(value) {
    navigate({ status: value, page: 1 });
  }

  function copyToClipboard(text) {
    navigator.clipboard.writeText(text);
  }

  // Re-initialize Bootstrap popovers whenever the rows change (load() re-run).
  $effect(() => {
    const unused = orders;
    if (typeof window !== 'undefined' && window.bootstrap) {
      let popovers = [];
      const timer = setTimeout(() => {
        const popoverTriggerList = document.querySelectorAll('[data-bs-toggle="popover"]');
        popovers = [...popoverTriggerList].map((el) => new window.bootstrap.Popover(el));
      }, 50);
      return () => {
        clearTimeout(timer);
        popovers.forEach((p) => p.dispose());
      };
    }
  });
</script>

<MarketLayout>
  <div class="card animate__animated animate__fadeIn">
    <CardHeader>
      <div slot="left">
        {$_('pages.orders.count', { values: { count: orderCount } })}
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={searchQuery}
          searching={isSearching}
          debounceMs={300}
          onchange={(e) => navigate({ search: e, page: 1 })} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button onclick={() => setStatusFilter('all')} active={currentStatus === 'all'}>
          {$_('common.all')}
        </CardFiltersItem>
        <CardFiltersItem button onclick={() => setStatusFilter('COMPLETED')} active={currentStatus === 'COMPLETED'}>
          {$_('pages.orders.status-completed')}
        </CardFiltersItem>
        <CardFiltersItem button onclick={() => setStatusFilter('REFUNDED')} active={currentStatus === 'REFUNDED'}>
          {$_('pages.orders.status-refunded')}
        </CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if loadError}
      <div class="text-center text-body-secondary py-5">
        <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
        <div>{$_('pages.orders.load-error')}</div>
      </div>
    {:else if orders.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle mb-0">
          <thead>
            <tr>
              <th class="ps-3" style="width: 120px;">{$_('pages.orders.table.order-id')}</th>
              <th>{$_('pages.orders.table.player')}</th>
              <th>{$_('pages.orders.table.product')}</th>
              <th>{$_('pages.orders.table.price')}</th>
              <th>{$_('pages.orders.table.payment-method')}</th>
              <th>{$_('common.status')}</th>
              <th>{$_('pages.orders.table.date')}</th>
              <th class="pe-3 text-end" style="width: 90px;"></th>
            </tr>
          </thead>
          <tbody>
            {#each orders as order (order.id)}
              {@const products = (order.items || []).map((i) => i.productName)}
              <tr>
                <td class="ps-3">
                  <button
                    type="button"
                    class="btn btn-link p-0 text-decoration-none font-monospace user-select-all cursor-pointer focus-ring rounded border-0"
                    title={$_('pages.orders.title-copy')}
                    onclick={() => copyToClipboard(order.id)}>
                    #{order.id}
                  </button>
                </td>
                <td>
                  {#if order.playerUsername}
                    <a href="{base}/players/detail/{order.playerUsername}" class="text-decoration-none d-flex align-items-center focus-ring rounded" title={$_('pages.orders.title-view')}>
                      <img src="https://minotar.net/avatar/{order.playerUsername}/24" class="rounded-circle me-2" style="width: 24px; height: 24px;" alt={order.playerUsername} />
                      <span>{order.playerUsername}</span>
                    </a>
                  {:else}
                    <span class="text-body-secondary">-</span>
                  {/if}
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1 align-items-center">
                    {#if products.length > 0}
                      <span class="badge text-bg-primary focus-ring rounded" title={$_('pages.orders.title-view')}>
                        {products[0]}
                      </span>
                      {#if products.length > 1}
                        <span
                          class="badge text-bg-secondary cursor-help rounded-pill"
                          data-bs-toggle="popover"
                          data-bs-trigger="hover focus"
                          data-bs-placement="top"
                          data-bs-html="true"
                          data-bs-content={products.slice(1).map(p => `<span class='badge text-bg-primary me-1'>${p}</span>`).join('')}>
                          +{products.length - 1}
                        </span>
                      {/if}
                    {/if}
                  </div>
                </td>
                <td>
                  <span class="badge text-bg-secondary focus-ring rounded" title={$_('common.edit')}>
                    {formatMoney(order.totalPrice, CURRENCY_SYMBOLS[order.currency] || currencySymbol)}
                  </span>
                </td>
                <td>
                  {order.paymentLabel || '-'}
                </td>
                <td>
                  {#if ORDER_STATUS[order.status]}
                    <span class="badge {ORDER_STATUS[order.status].cls}">{$_(ORDER_STATUS[order.status].key)}</span>
                  {:else}
                    <span class="badge text-bg-secondary">{order.status}</span>
                  {/if}
                </td>
                <td><DateComponent time={order.createdAt} relativeFormat={true} /></td>
                <td class="pe-3 text-end">
                  <button
                    type="button"
                    class="btn btn-sm btn-light"
                    data-bs-toggle="modal"
                    data-bs-target="#orderDetailModal"
                    title={$_('pages.orders.detail')}
                    onclick={() => openDetail(order.id)}>
                    <i class="fas fa-eye me-1"></i>{$_('pages.orders.detail')}
                  </button>
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

<OrderDetailModal orderId={selectedOrderId} {currencySymbol} onUpdated={() => navigate()} />
