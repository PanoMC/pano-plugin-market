<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="payment-events">
  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.payment-events.count', { values: { count: eventCount } })}
        </div>
        <CardFilters slot="right">
          {#each STATUS_TABS as tab (tab.key)}
            <CardFiltersItem button active={currentTab === tab.key} onclick={() => go({ status: tab.value })}>
              {$_(`pages.payment-events.tab.${tab.key}`)}
            </CardFiltersItem>
          {/each}
        </CardFilters>
      </CardHeader>

      {#if events.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.provider')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.direction')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.channel')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.event-types')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.http')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.error')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.ip')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.payment-events.table.date')}</th>
              </tr>
            </thead>
            <tbody>
              {#each events as event (event.id)}
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
                        <button type="button" class="dropdown-item" onclick={() => detailModal?.open(event)}>
                          <i class="fa-solid fa-eye me-2" aria-hidden="true"></i>
                          {$_('pages.payment-events.actions.view')}
                        </button>
                        {#if canReplay(event, user)}
                          <button type="button" class="dropdown-item" onclick={() => askReplay(event)}>
                            <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>
                            {$_('pages.payment-events.actions.replay')}
                          </button>
                        {/if}
                      </div>
                    </div>
                  </th>
                  <td class="text-nowrap">{event.providerId ?? '—'}</td>
                  <td class="text-nowrap">{event.direction ?? '—'}</td>
                  <td class="text-nowrap">{event.channel ?? '—'}</td>
                  <td class="text-nowrap">
                    <StatusBadge kind="event" value={event.status} />
                    {#if isUnverified(event)}
                      <span class="badge text-bg-danger ms-1">{$_('pages.payment-events.unverified')}</span>
                    {/if}
                  </td>
                  <td>{eventTypesText(event) || '—'}</td>
                  <td>{event.responseStatus ?? '—'}</td>
                  <td>{event.error || '—'}</td>
                  <td class="text-nowrap">{event.remoteIp || '—'}</td>
                  <td class="text-nowrap">{dateText(event.createdAt)}</td>
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

<PaymentEventModal bind:this={detailModal} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import { loadList } from '../utils/list.js';
  import { EVENT_PARAMS } from '../utils/payment-events.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/payment-events',
      params: EVENT_PARAMS,
      nodes: ['OV'],
      emptyKey: 'events',
      title: 'pages.payment-events.title',
    });
  }
</script>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import {
    CardHeader,
    CardFilters,
    CardFiltersItem,
    NoContent,
    Pagination,
  } from '@panomc/sdk/components/panel';
  import { page } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import PaymentEventModal from '../components/modals/PaymentEventModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call, marketPath } from '../utils/api.js';
  import { gotoList } from '../utils/list.js';
  import { currentLocale } from '../utils/locale.js';
  import {
    STATUS_TABS,
    activeTab,
    canReplay,
    eventTypesText,
    listParams,
    normalizeFilters,
  } from '../utils/payment-events.js';
  import { isKnownStatus } from '../utils/status.js';
  import { toastError } from '../utils/toast.js';

  let { data } = $props();

  let detailModal = $state(null);
  let confirmModal = $state(null);

  const user = $derived($page.data?.user);
  const filters = $derived(normalizeFilters(data.filters));
  const events = $derived(data.events ?? []);
  const eventCount = $derived(data.eventCount ?? data.count ?? 0);
  const totalPage = $derived(data.totalPage ?? 1);
  const currentPage = $derived(data.page ?? 1);
  const currentTab = $derived(activeTab(filters.status));

  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  // The URL is the source of truth; a filter change always drops `page`.
  function go(overrides) {
    return gotoList('/market/payment-events', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/payment-events', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }

  // Stale rows (INVALID_STATE, NOT_FOUND) toast and refresh (13 section 23).
  function askReplay(event) {
    confirmModal?.open({
      icon: 'fa-solid fa-rotate-right',
      title: $_('pages.payment-events.replay-title'),
      description: $_('pages.payment-events.replay-description'),
      confirmLabel: $_('pages.payment-events.actions.replay'),
      onConfirm: async () => {
        const result = await call(
          ApiUtil.post({ path: marketPath(`/payment-events/${event.id}/replay`), body: {} }),
        );
        if (!result.ok) {
          toastError($_, result);
          if (result.error !== 'INVALID_STATE' && result.error !== 'NOT_FOUND') return false;
          setTimeout(() => go({}), 350);
          return;
        }
        // Toast text never carries API strings: an unknown status falls back to the generic text.
        const status = result.body?.status;
        showSuccessToast(
          isKnownStatus('event', status)
            ? $_('pages.payment-events.toast-replayed', {
                values: { status: $_(`enums.event.${status}`) },
              })
            : $_('pages.payment-events.toast-replayed-generic'),
        );
        setTimeout(() => go({}), 350);
      },
    });
  }
</script>
