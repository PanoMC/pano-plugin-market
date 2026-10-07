<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="orders">
  {#snippet right()}
    {#if can(user, 'PAY')}
      <a class="btn btn-primary me-2" href="{base}/market/orders/create-order">
        {$_('pages.orders.create-order')}
      </a>
    {/if}
    <div class="dropdown d-inline-block">
      <button
        type="button"
        class="btn btn-link"
        data-bs-toggle="dropdown"
        data-bs-popper-config={POPPER_FIXED}
        title={$_('common.actions')}
        aria-label={$_('common.actions')}>
        <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
      </button>
      <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
        <button type="button" class="dropdown-item" onclick={() => exportModal?.open()}>
          <i class="fa-solid fa-file-csv me-2" aria-hidden="true"></i>
          {$_('pages.orders.export-csv')}
        </button>
        <button type="button" class="dropdown-item" onclick={() => filtersModal?.open()}>
          <i class="fa-solid fa-filter me-2" aria-hidden="true"></i>
          {$_('pages.orders.filters')}
        </button>
      </div>
    </div>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.orders.count', { values: { count: orderCount } })}
        </div>
        <div slot="middle" style="width: 250px;">
          <SearchInput
            autofocus
            initialValue={filters.search}
            searching={$navigating !== null}
            debounceMs={300}
            onchange={(value) => go({ search: value })} />
        </div>
        <CardFilters slot="right">
          {#each STATUS_TABS as tab (tab.key)}
            <CardFiltersItem
              button
              active={currentTab === tab.key}
              onclick={() => go({ status: tab.value })}>
              {$_(`pages.orders.tab.${tab.key}`)}
            </CardFiltersItem>
          {/each}
          {#if modalFilterCount > 0}
            <CardFiltersItem button onclick={clearModalFilters}>
              {$_('common.clear-filters')}
            </CardFiltersItem>
          {/if}
        </CardFilters>
      </CardHeader>

      {#if orders.length === 0}
        <NoContent icon="" />
      {:else}
        <OrdersTable {orders} {user} shippingEnabled={ctx?.shippingEnabled === true} />

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

<OrderFiltersModal bind:this={filtersModal} {filters} {ctx} onApply={(values) => go(values)} />
<ExportOrdersModal bind:this={exportModal} {filters} />

<script module>
  import { loadList } from '../utils/list.js';
  import { ORDER_PARAMS } from '../components/orders/filters.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/orders',
      params: ORDER_PARAMS,
      nodes: ['OV'],
      emptyKey: 'orders',
      title: 'pages.orders.title',
    });
  }
</script>

<script>
  // The page header's right column scrolls sideways, which clips a menu hanging below it; a fixed menu is not clipped.
  const POPPER_FIXED = '{"strategy":"fixed"}';

  import {
    CardHeader,
    CardFilters,
    CardFiltersItem,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import { base, navigating, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import LoadError from '../components/LoadError.svelte';
  import ExportOrdersModal from '../components/modals/ExportOrdersModal.svelte';
  import OrderFiltersModal from '../components/modals/OrderFiltersModal.svelte';
  import OrdersTable from '../components/orders/OrdersTable.svelte';
  import {
    MODAL_FILTERS,
    STATUS_TABS,
    activeModalFilters,
    activeTab,
    listParams,
    normalizeFilters,
  } from '../components/orders/filters.js';
  import { sectionsFor } from '../navigation.js';
  import { gotoList } from '../utils/list.js';
  import { can } from '../utils/permissions.js';

  let { data } = $props();

  let filtersModal = $state(null);
  let exportModal = $state(null);

  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const filters = $derived(normalizeFilters(data.filters));
  const orders = $derived(data.orders ?? []);
  const orderCount = $derived(data.orderCount ?? data.count ?? 0);
  const totalPage = $derived(data.totalPage ?? 1);
  const currentPage = $derived(data.page ?? 1);
  const currentTab = $derived(activeTab(filters.status));
  const modalFilterCount = $derived(
    activeModalFilters(filters, { shippingEnabled: ctx?.shippingEnabled === true }).length,
  );

  // The URL is the source of truth; load() re-runs on every navigation. A filter or search change
  // always drops `page`.
  function go(overrides) {
    return gotoList('/market/orders', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/orders', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }

  function clearModalFilters() {
    return go(Object.fromEntries(MODAL_FILTERS.map((name) => [name, null])));
  }
</script>
