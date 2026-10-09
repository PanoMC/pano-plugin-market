<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="deliveries">
  {#snippet right()}
    <button type="button" class="btn btn-link" onclick={() => filtersModal?.open()}>
      <i class="fa-solid fa-filter me-2" aria-hidden="true"></i>
      {$_('pages.deliveries.filters')}
    </button>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.deliveries.count', { values: { count: deliveryCount } })}
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
          <FilterSelect
            label={$_('common.status')}
            current={currentTab}
            options={STATUS_TABS.map((tab) => ({ ...tab, label: $_(`pages.deliveries.tab.${tab.key}`) }))}
            onSelect={(tab) => go({ status: tab.value })} />
          <select
            class="form-select form-select-sm ms-2 w-auto"
            aria-label={$_('pages.deliveries.phase')}
            value={filters.phase}
            onchange={(event) => go({ phase: event.currentTarget.value })}>
            <option value="">{$_('pages.deliveries.phase-all')}</option>
            {#each PHASES as value (value)}
              <option {value}>{$_(`enums.phase.${value}`)}</option>
            {/each}
          </select>
          {#if modalFilterCount > 0}
            <CardFiltersItem button onclick={clearModalFilters}>
              {$_('common.clear-filters')}
            </CardFiltersItem>
          {/if}
        </CardFilters>
      </CardHeader>

      {#if deliveries.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="text-nowrap" scope="col"></th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.order')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.player')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.product')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.action')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.server')}</th>
                <th class="text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.attempts')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.last-error')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.deliveries.table.when')}</th>
              </tr>
            </thead>
            <tbody>
              {#each deliveries as delivery (delivery.id)}
                {@const actions = rowActions(delivery, user)}
                {@const when = whenCell(delivery)}
                <tr>
                  <th scope="row">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
                      </button>
                      <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                        {#if actions.includes('retry')}
                          <button type="button" class="dropdown-item" onclick={() => askRetry(delivery)}>
                            <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>
                            {$_('pages.deliveries.actions.retry')}
                          </button>
                        {/if}
                        {#if actions.includes('offer-again')}
                          <button type="button" class="dropdown-item" onclick={() => askRetry(delivery)}>
                            <i class="fa-solid fa-paper-plane me-2" aria-hidden="true"></i>
                            {$_('pages.deliveries.actions.offer-again')}
                          </button>
                        {/if}
                        {#if actions.includes('cancel')}
                          <button
                            type="button"
                            class="dropdown-item text-danger"
                            onclick={() => askCancel(delivery)}>
                            <i class="fa-solid fa-ban me-2" aria-hidden="true"></i>
                            {$_('pages.deliveries.actions.cancel')}
                          </button>
                        {/if}
                        <a class="dropdown-item" href="{base}/market/orders/detail/{delivery.orderId}">
                          <i class="fa-solid fa-eye me-2" aria-hidden="true"></i>
                          {$_('pages.deliveries.actions.view-order')}
                        </a>
                      </div>
                    </div>
                  </th>
                  <td class="text-nowrap">
                    <a href="{base}/market/orders/detail/{delivery.orderId}">#{delivery.orderId}</a>
                  </td>
                  <td class="text-nowrap">
                    {#if delivery.playerUsername}
                      <PlayerCell username={delivery.playerUsername} />
                    {:else}
                      —
                    {/if}
                  </td>
                  <td>{delivery.productName ?? '—'}</td>
                  <td class="text-nowrap">
                    {$_(`enums.action-type.${delivery.actionType}`)}
                    {#if delivery.phase && delivery.phase !== 'GRANT'}
                      <span class="badge text-bg-secondary ms-1">
                        {$_(`enums.phase.${delivery.phase}`)}
                      </span>
                    {/if}
                  </td>
                  <td class="text-nowrap">{delivery.serverName ?? '—'}</td>
                  <td class="text-nowrap">
                    <StatusBadge kind="delivery" value={delivery.status} />
                    {#if showCancelRequested(delivery)}
                      <span class="badge text-bg-warning ms-1">
                        {$_('pages.deliveries.cancel-requested')}
                      </span>
                    {/if}
                    {#if delivery.requiresOnline}
                      <i
                        class="fa-solid fa-user-clock ms-1"
                        title={$_('pages.deliveries.requires-online')}
                        aria-label={$_('pages.deliveries.requires-online')}></i>
                    {/if}
                  </td>
                  <td>{delivery.attempts ?? 0}</td>
                  <td>
                    {#if delivery.lastErrorCode}
                      <span
                        class:text-warning-emphasis={mayHaveRun(delivery)}
                        title={delivery.lastError ?? ''}>
                        {#if mayHaveRun(delivery)}
                          <i class="fa-solid fa-triangle-exclamation me-1" aria-hidden="true"></i>
                        {/if}
                        {errorLabel(delivery.lastErrorCode)}
                      </span>
                    {:else}
                      —
                    {/if}
                  </td>
                  <td class="text-nowrap">
                    {#if when.at}
                      {#if when.scheduled}
                        <span class="text-body-secondary">
                          {$_('pages.deliveries.scheduled-for')}
                        </span>
                      {/if}
                      {formatWhen(when.at)}
                    {:else}
                      —
                    {/if}
                  </td>
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

<DeliveryFiltersModal bind:this={filtersModal} {filters} onApply={(values) => go(values)} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import { loadList } from '../utils/list.js';
  import { DELIVERY_PARAMS } from '../utils/deliveries.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/deliveries',
      params: DELIVERY_PARAMS,
      nodes: ['OV'],
      title: 'pages.deliveries.title',
    });
  }
</script>

<script>
  import FilterSelect from '../components/FilterSelect.svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import {
    CardHeader,
    CardFilters,
    CardFiltersItem,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import { base, navigating, page, invalidateAll } from '@panomc/sdk/svelte';
  import { _, showErrorToast, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PlayerCell from '../components/PlayerCell.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import DeliveryFiltersModal from '../components/modals/DeliveryFiltersModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call, errorKey } from '../utils/api.js';
  import {
    MODAL_FILTERS,
    PHASES,
    STATUS_TABS,
    activeModalFilters,
    activeTab,
    listParams,
    mayHaveRun,
    normalizeFilters,
    rowActions,
    showCancelRequested,
    whenCell,
  } from '../utils/deliveries.js';
  import { gotoList } from '../utils/list.js';
  import { pageOf } from '../utils/page.js';
  import { currentLocale } from '../utils/locale.js';

  let { data } = $props();

  let filtersModal = $state(null);
  let confirmModal = $state(null);

  const user = $derived($page.data?.user);
  const filters = $derived(normalizeFilters(data.filters));
  const list = $derived(pageOf(data));
  const deliveries = $derived(list.items);
  const deliveryCount = $derived(list.totalItems);
  const totalPage = $derived(list.totalPages);
  const currentPage = $derived(list.number);
  const currentTab = $derived(activeTab(filters.status));
  const modalFilterCount = $derived(activeModalFilters(filters).length);

  // The URL is the source of truth; load() re-runs on every navigation. A filter or search change
  // always drops `page`.
  function go(overrides) {
    return gotoList('/market/deliveries', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/deliveries', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }

  function clearModalFilters() {
    return go(Object.fromEntries(MODAL_FILTERS.map((name) => [name, null])));
  }

  function formatWhen(epoch) {
    return new Date(Number(epoch)).toLocaleString(currentLocale());
  }

  // Unknown code => the raw code (13 §9.1); the raw lastError is the cell tooltip (text only).
  function errorLabel(code) {
    const key = `enums.delivery-error.${code}`;
    const text = $_(key);
    return text === `plugins.pano-plugin-market.${key}` || text === key ? code : text;
  }

  // Stale rows (DELIVERY_NOT_RETRYABLE / DELIVERY_NOT_CANCELLABLE) toast the code and refresh.
  async function run(path, successKey) {
    const result = await call(api.panel.post({ path, body: {} }));
    if (!result.ok) {
      showErrorToast($_(errorKey(result.error)));
      if (result.error !== 'NETWORK_ERROR') await invalidateAll();
      return false;
    }
    showSuccessToast($_(successKey));
    await invalidateAll();
  }

  function askRetry(delivery) {
    const again = delivery.status === 'SENT';
    confirmModal?.open({
      icon: 'fa-solid fa-rotate-right',
      title: $_(again ? 'pages.deliveries.offer-again-title' : 'pages.deliveries.retry-title'),
      description: $_(
        again ? 'pages.deliveries.offer-again-description' : 'pages.deliveries.retry-description',
      ),
      confirmLabel: $_(again ? 'pages.deliveries.actions.offer-again' : 'pages.deliveries.actions.retry'),
      onConfirm: () => run(`/deliveries/${delivery.id}/retry`, 'pages.deliveries.toast-retry'),
    });
  }

  function askCancel(delivery) {
    confirmModal?.open({
      icon: 'fa-solid fa-ban',
      title: $_('pages.deliveries.cancel-title'),
      description: $_('pages.deliveries.cancel-description'),
      confirmLabel: $_('pages.deliveries.actions.cancel'),
      variant: 'danger',
      onConfirm: () => run(`/deliveries/${delivery.id}/cancel`, 'pages.deliveries.toast-cancel'),
    });
  }
</script>
