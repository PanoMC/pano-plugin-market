<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="subscriptions">
  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.subscriptions.count', { values: { count: subscriptionCount } })}
        </div>
        <div slot="middle" style="width: 250px;">
          <SearchInput
            autofocus
            initialValue={filters.search}
            searching={$navigating !== null}
            placeholderKey="plugins.pano-plugin-market.search.subscriptions"
            debounceMs={300}
            onchange={(value) => go({ search: value })} />
        </div>
        <CardFilters slot="right">
          <FilterSelect
            label={$_('common.status')}
            current={currentTab}
            options={STATUS_TABS.map((tab) => ({ ...tab, label: $_(`pages.subscriptions.tab.${tab.key}`) }))}
            onSelect={(tab) => go({ status: tab.value })} />
        </CardFilters>
      </CardHeader>

      {#if subscriptions.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.id')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.player')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.product')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.price')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.method')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.period-end')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscriptions.table.mode')}</th>
              </tr>
            </thead>
            <tbody>
              {#each subscriptions as subscription (subscription.id)}
                {@const actions = actionsFor(subscription, user)}
                <tr>
                  <th class="align-middle" scope="row">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                      </button>
                      <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                        <a class="dropdown-item" href="{base}/market/subscriptions/detail/{subscription.id}">
                          <i class="fa-solid fa-eye me-2" aria-hidden="true"></i>
                          {$_('pages.subscriptions.actions.view')}
                        </a>
                        {#if actions.includes('retry')}
                          <button
                            type="button"
                            class="dropdown-item"
                            onclick={() => confirmRetry(confirmModal, $_, subscription, () => go({}))}>
                            <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>
                            {$_('pages.subscriptions.actions.retry')}
                          </button>
                        {/if}
                        {#if actions.includes('cancel')}
                          <button
                            type="button"
                            class="dropdown-item link-danger"
                            onclick={() => cancelModal?.open(subscription)}>
                            <i class="fa-solid fa-ban me-2" aria-hidden="true"></i>
                            {$_('pages.subscriptions.actions.cancel')}
                          </button>
                        {/if}
                      </div>
                    </div>
                  </th>
                  <td class="text-nowrap">
                    <a href="{base}/market/subscriptions/detail/{subscription.id}">#{subscription.id}</a>
                  </td>
                  <td class="text-nowrap">
                    {#if subscription.playerUsername}
                      <PlayerCell username={subscription.playerUsername} />
                    {:else}
                      —
                    {/if}
                  </td>
                  <td>{subscription.productName ?? '—'}</td>
                  <td class="text-nowrap">{priceText(subscription, fmt.money, duration)}</td>
                  <td class="text-nowrap">
                    <StatusBadge kind="subscription" value={subscription.status} />
                    {#if subscription.cancelAtPeriodEnd}
                      <span class="badge text-bg-warning ms-1">
                        {$_('pages.subscriptions.cancels-at-period-end')}
                      </span>
                    {/if}
                    {#if subscription.testMode}
                      <span class="badge text-bg-secondary ms-1">{$_('common.test')}</span>
                    {/if}
                  </td>
                  <td class="text-nowrap">{methodText(subscription) || '—'}</td>
                  <td class="text-nowrap">{dateText(subscription.currentPeriodEnd)}</td>
                  <td class="text-nowrap">
                    {subscription.mode ? $_(`enums.subscription-mode.${subscription.mode}`) : '—'}
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

<CancelSubscriptionModal bind:this={cancelModal} onSaved={() => go({})} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import { loadList } from '../utils/list.js';
  import { SUBSCRIPTION_PARAMS } from '../utils/subscriptions.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/subscriptions',
      params: SUBSCRIPTION_PARAMS,
      nodes: ['OV'],
      title: 'pages.subscriptions.title',
    });
  }
</script>

<script>
  import FilterSelect from '../components/FilterSelect.svelte';
  import {
    CardHeader,
    CardFilters,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import { base, navigating, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PlayerCell from '../components/PlayerCell.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import CancelSubscriptionModal from '../components/modals/CancelSubscriptionModal.svelte';
  import { confirmRetry } from '../components/subscriptions/retry.js';
  import { sectionsFor } from '../navigation.js';
  import { formatDuration } from '../utils/format.js';
  import { gotoList } from '../utils/list.js';
  import { pageOf } from '../utils/page.js';
  import { currentLocale, fmt } from '../utils/locale.js';
  import {
    STATUS_TABS,
    actionsFor,
    activeTab,
    listParams,
    methodText,
    normalizeFilters,
    priceText,
  } from '../utils/subscriptions.js';

  let { data } = $props();

  let cancelModal = $state(null);
  let confirmModal = $state(null);

  const user = $derived($page.data?.user);
  const filters = $derived(normalizeFilters(data.filters));
  const list = $derived(pageOf(data));
  const subscriptions = $derived(list.items);
  const subscriptionCount = $derived(list.totalItems);
  const totalPage = $derived(list.totalPages);
  const currentPage = $derived(list.number);
  const currentTab = $derived(activeTab(filters.status));

  const duration = (unit, count) => formatDuration(unit, count, $_);
  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  // The URL is the source of truth; a filter or search change always drops `page`.
  function go(overrides) {
    return gotoList('/market/subscriptions', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/subscriptions', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }
</script>
