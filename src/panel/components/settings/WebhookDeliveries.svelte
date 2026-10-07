<ConfirmModal bind:this={confirm} />
<WebhookDeliveryModal bind:this={detailModal} />

<div class="card">
  <CardHeader>
    <div slot="left">
      {#if endpointId}
        <a class="text-decoration-none me-2" href="{base}/market/settings?section=webhooks">
          <i class="fa-solid fa-arrow-left" aria-hidden="true"></i>
          <span class="visually-hidden">{$_('common.back')}</span>
        </a>
      {/if}
      {$_('settings.webhook-deliveries.count', { values: { count } })}
    </div>
    <CardFilters slot="right">
      <FilterSelect
        label={$_('common.status')}
        current={currentTab}
        options={DELIVERY_TABS.map((tab) => ({ ...tab, label: $_(`settings.webhook-deliveries.tab.${tab.key}`) }))}
        onSelect={(tab) => setStatus(tab.value)} />
    </CardFilters>
  </CardHeader>

  {#if loadError}
    <div class="card-body">
      <LoadError error={loadError} onRetry={load} />
    </div>
  {:else if loading && deliveries.length === 0}
    <div class="text-center text-body-secondary py-5">
      <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
      <span class="visually-hidden">{$_('common.loading')}</span>
    </div>
  {:else if deliveries.length === 0}
    <NoContent icon="" />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col"></th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.event')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.order')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.attempts')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.http')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.duration')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.created')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('settings.webhook-deliveries.table.delivered')}</th>
          </tr>
        </thead>
        <tbody>
          {#each deliveries as row (row.id)}
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
                  <div class="dropdown-menu dropdown-menu-start">
                    <button type="button" class="dropdown-item" onclick={() => detailModal?.open(row)}>
                      <i class="fa-solid fa-eye me-2" aria-hidden="true"></i>
                      {$_('common.view')}
                    </button>
                    {#if canRedeliver(row)}
                      <button type="button" class="dropdown-item" onclick={() => askRedeliver(row)}>
                        <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>
                        {$_('settings.webhook-deliveries.redeliver')}
                      </button>
                    {/if}
                  </div>
                </div>
              </th>
              <td class="text-nowrap">{row.event}</td>
              <td class="text-nowrap">
                {#if orderLabel(row)}
                  <a class="text-decoration-none" href="{base}/market/orders/detail/{row.orderId}">
                    {orderLabel(row)}
                  </a>
                {:else}
                  —
                {/if}
              </td>
              <td class="text-nowrap"><StatusBadge kind="webhook" value={row.status} /></td>
              <td>{row.attempts ?? 0}</td>
              <td>{row.lastStatusCode ?? '—'}</td>
              <td class="text-nowrap">
                {row.durationMs === null || row.durationMs === undefined
                  ? '—'
                  : $_('settings.webhook-deliveries.ms', { values: { ms: row.durationMs } })}
              </td>
              <td class="text-nowrap">{dateText(row.createdAt)}</td>
              <td class="text-nowrap">{dateText(row.deliveredAt)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>

    {#if totalPage > 1}
      <div class="card-footer">
        <Pagination
          {page}
          {totalPage}
          on:firstPageClick={() => gotoPage(1)}
          on:lastPageClick={() => gotoPage(totalPage)}
          on:pageLinkClick={(event) => gotoPage(event.detail.page)} />
      </div>
    {/if}
  {/if}
</div>

<script>
  import FilterSelect from '../FilterSelect.svelte';
  import { onMount } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import {
    CardFilters,
    CardHeader,
    NoContent,
    Pagination,
  } from '@panomc/sdk/components/panel';
  import { base, page as pageStore } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import LoadError from '../LoadError.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import WebhookDeliveryModal from '../modals/WebhookDeliveryModal.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import {
    DELIVERY_TABS,
    canRedeliver,
    deliveriesPath,
    deliveryTab,
    orderLabel,
    parseEndpointId,
  } from '../../utils/webhooks.js';

  // Section `webhook-deliveries` (13 §18.3). `?endpointId=` limits the log to one endpoint.
  let confirm = $state(null);
  let detailModal = $state(null);
  // extra / extraError: the first page (no status filter) loaded with the page (extraPathFor).
  let { extra = null, extraError = null } = $props();

  let deliveries = $state.raw(extra?.deliveries ?? []);
  let count = $state(extra?.count ?? extra?.deliveries?.length ?? 0);
  let totalPage = $state(extra?.totalPage ?? 1);
  let page = $state(1);
  let status = $state(null);
  let loading = $state(!extra && !extraError);
  let loadError = $state(extraError);

  const endpointId = $derived(parseEndpointId($pageStore?.url?.searchParams?.get('endpointId')));
  const currentTab = $derived(deliveryTab(status));
  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  let requestTag = 0;

  async function load() {
    const tag = ++requestTag;
    loading = true;
    const result = await call(
      ApiUtil.get({ path: marketPath(deliveriesPath({ endpointId, status, page })) }),
    );
    if (tag !== requestTag) return;
    loading = false;
    if (!result.ok) {
      // A stale page number (rows were deleted): fall back to the first page once.
      if (result.error === 'PAGE_NOT_FOUND' && page > 1) {
        page = 1;
        await load();
        return;
      }
      loadError = result.error;
      return;
    }
    loadError = null;
    deliveries = result.body.deliveries ?? [];
    count = result.body.count ?? deliveries.length;
    totalPage = result.body.totalPage ?? 1;
  }

  onMount(() => {
    if (!extra && !extraError) load();
  });

  function setStatus(value) {
    status = value;
    page = 1;
    load();
  }

  function gotoPage(next) {
    page = next;
    load();
  }

  // Stale rows (a status that moved on, a deleted row) toast and refresh (13 §23).
  function askRedeliver(row) {
    confirm?.open({
      icon: 'fa-solid fa-rotate-right',
      title: $_('settings.webhook-deliveries.confirm-redeliver.title'),
      description: $_('settings.webhook-deliveries.confirm-redeliver.description'),
      confirmLabel: $_('settings.webhook-deliveries.redeliver'),
      onConfirm: async () => {
        const result = await call(
          ApiUtil.post({ path: marketPath(`/webhook-deliveries/${row.id}/redeliver`), body: {} }),
        );
        if (!result.ok) {
          toastError($_, result);
          if (result.error !== 'BAD_REQUEST' && result.error !== 'NOT_FOUND') return false;
          await load();
          return;
        }
        showSuccessToast($_('settings.webhook-deliveries.toast-redelivered'));
        await load();
      },
    });
  }
</script>
