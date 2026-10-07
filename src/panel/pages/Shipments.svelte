<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="shipments">
  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.shipments.count', { values: { count: shipmentCount } })}
        </div>
        <div slot="middle" style="width: 250px;">
          <SearchInput
            autofocus
            placeholderKey="plugins.pano-plugin-market.search.shipments"
            initialValue={filters.search}
            searching={$navigating !== null}
            debounceMs={300}
            onchange={(value) => go({ search: value })} />
        </div>
        <CardFilters slot="right">
          <FilterSelect
            label={$_('common.status')}
            current={currentTab}
            options={STATUS_TABS.map((tab) => ({ ...tab, label: $_(`pages.shipments.tab.${tab.key}`) }))}
            onSelect={(tab) => (tab.href ? goto(base + tab.href) : go({ status: tab.value }))} />
        </CardFilters>
      </CardHeader>

      {#if shipments.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"></th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.shipments.table.order')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.shipments.table.recipient')}
                </th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.shipments.table.carrier')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.shipments.table.tracking')}
                </th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.shipments.table.shipped')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.shipments.table.delivered')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each shipments as shipment (shipment.id)}
                {@const actions = rowActions(shipment, user)}
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
                        <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
                      </button>
                      <div
                        class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                        {#each actions as action (action)}
                          {#if action === 'label' || action === 'generic-label'}
                            <a
                              class="dropdown-item"
                              href={labelPath(base, shipment.id, action === 'generic-label')}
                              target="_blank"
                              rel="noopener"
                              download>
                              <i class="fa-solid fa-file-arrow-down me-2" aria-hidden="true"></i>
                              {$_(`pages.shipments.actions.${action}`)}
                            </a>
                          {:else}
                            <button
                              type="button"
                              class="dropdown-item"
                              class:link-danger={action === 'cancel'}
                              onclick={() => runAction(action, shipment)}>
                              <i class="fa-solid {ACTION_ICONS[action]} me-2" aria-hidden="true"
                              ></i>
                              {$_(`pages.shipments.actions.${action}`)}
                            </button>
                          {/if}
                        {/each}
                      </div>
                    </div>
                  </th>
                  <td class="align-middle text-nowrap">
                    <a href="{base}/market/orders/detail/{shipment.orderId}">#{shipment.orderId}</a>
                  </td>
                  <td class="align-middle">
                    {#if shipment.toAddress}
                      <div>{recipientName(shipment.toAddress) || '—'}</div>
                      <div class="text-body-secondary">{shipment.toAddress.city ?? ''}</div>
                    {:else}
                      —
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap"
                    >{shipment.carrierName ?? shipment.providerId ?? '—'}</td>
                  <td class="align-middle text-nowrap">
                    {#if shipment.trackingNumber}
                      {#if isHttpUrl(shipment.trackingUrl)}
                        <a href={shipment.trackingUrl} target="_blank" rel="noopener">
                          {shipment.trackingNumber}
                        </a>
                      {:else}
                        {shipment.trackingNumber}
                      {/if}
                      <CopyButton text={shipment.trackingNumber} />
                    {:else}
                      —
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">
                    <StatusBadge kind="shipment" value={shipment.status} />
                    {#if shipment.stale}
                      <span class="badge text-bg-warning ms-1">{$_('pages.shipments.stale')}</span>
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">{formatWhen(shipment.shippedAt)}</td>
                  <td class="align-middle text-nowrap">{formatWhen(shipment.deliveredAt)}</td>
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

<ShipmentModal
  bind:this={viewModal}
  {user}
  onDone={refreshAfter}
  onStale={refreshAfter}
  onRelease={askRelease} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import { loadList } from '../utils/list.js';
  import { SHIPMENT_PARAMS } from '../utils/shipments.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/shipments',
      params: SHIPMENT_PARAMS,
      nodes: ['OV'],
      emptyKey: 'shipments',
      title: 'pages.shipments.title',
    });
  }
</script>

<script>
  import FilterSelect from '../components/FilterSelect.svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import {
    CardHeader,
    CardFilters,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import { base, goto, navigating, page, invalidateAll } from '@panomc/sdk/svelte';
  import { _, showErrorToast, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import CopyButton from '../components/CopyButton.svelte';
  import LoadError from '../components/LoadError.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import ShipmentModal from '../components/modals/ShipmentModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call } from '../utils/api.js';
  import { gotoList } from '../utils/list.js';
  import { currentLocale } from '../utils/locale.js';
  import {
    STATUS_TABS,
    activeTab,
    cancelRequest,
    failureText,
    isHttpUrl,
    labelPath,
    listParams,
    normalizeFilters,
    offersForceCancel,
    recipientName,
    releaseRequest,
    retryRequest,
    rowActions,
    trackRequest,
  } from '../utils/shipments.js';
  import { isStaleError } from '../components/order-detail/actions.js';

  let { data } = $props();

  let viewModal = $state(null);
  let confirmModal = $state(null);

  const ACTION_ICONS = {
    view: 'fa-eye',
    track: 'fa-location-crosshairs',
    retry: 'fa-rotate-right',
    release: 'fa-box-open',
    cancel: 'fa-ban',
  };

  const user = $derived($page.data?.user);
  const filters = $derived(normalizeFilters(data.filters));
  const shipments = $derived(data.shipments ?? []);
  const shipmentCount = $derived(data.shipmentCount ?? data.count ?? 0);
  const totalPage = $derived(data.totalPage ?? 1);
  const currentPage = $derived(data.page ?? 1);
  const currentTab = $derived(activeTab(filters.status));

  // The URL is the source of truth; load() re-runs on every navigation and drops `page` on a filter change.
  function go(overrides) {
    return gotoList('/market/shipments', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/shipments', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }

  function formatWhen(epoch) {
    return epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—';
  }

  async function refreshAfter(toastKey) {
    if (typeof toastKey === 'string') showSuccessToast($_(toastKey));
    await invalidateAll();
  }

  // Runs one request. A stale-state failure (cancelled elsewhere, wrong transition ...) toasts the
  // code and refreshes the list. Returns the call() result.
  async function run(request, successKey) {
    const result = await call(
      ApiUtil[request.method.toLowerCase()]({ path: request.path, body: request.body }),
    );
    if (!result.ok) {
      showErrorToast(failureText($_, result.error, result.body));
      if (isStaleError(result.error) || result.error === 'SHIPPING_PROVIDER_ERROR')
        await invalidateAll();
      return result;
    }
    showSuccessToast($_(successKey));
    await invalidateAll();
    return result;
  }

  function runAction(action, shipment) {
    if (action === 'view') return viewModal?.open(shipment.id);
    if (action === 'track') return run(trackRequest(shipment.id), 'pages.shipments.toast.tracked');
    if (action === 'retry') return run(retryRequest(shipment.id), 'pages.shipments.toast.retried');
    if (action === 'release') return askRelease(shipment);
    if (action === 'cancel') return askCancel(shipment);
  }

  function askRelease(shipment) {
    confirmModal?.open({
      icon: 'fa-solid fa-box-open',
      title: $_('pages.shipments.release.title'),
      description: $_('pages.shipments.release.description'),
      confirmLabel: $_('pages.shipments.actions.release'),
      onConfirm: async () => {
        const result = await run(releaseRequest(shipment.id), 'pages.shipments.toast.released');
        return result.ok ? undefined : false;
      },
    });
  }

  function askCancel(shipment) {
    confirmModal?.open({
      icon: 'fa-solid fa-ban',
      title: $_('pages.shipments.cancel.title'),
      description: $_('pages.shipments.cancel.description'),
      confirmLabel: $_('pages.shipments.actions.cancel'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(
          ApiUtil.post({
            path: cancelRequest(shipment.id).path,
            body: cancelRequest(shipment.id).body,
          }),
        );
        if (result.ok) {
          showSuccessToast($_('pages.shipments.toast.cancelled'));
          await invalidateAll();
          return undefined;
        }
        showErrorToast(failureText($_, result.error, result.body));
        if (offersForceCancel(result.error, result.body)) {
          // The same modal switches to the "Cancel Anyway" confirmation and stays open.
          askForce(shipment);
          return false;
        }
        await invalidateAll();
      },
    });
  }

  function askForce(shipment) {
    confirmModal?.open({
      icon: 'fa-solid fa-triangle-exclamation',
      title: $_('pages.shipments.force.title'),
      description: $_('pages.shipments.force.description'),
      confirmLabel: $_('pages.shipments.force.cta'),
      variant: 'danger',
      onConfirm: async () => {
        const request = cancelRequest(shipment.id, true);
        const result = await call(ApiUtil.post({ path: request.path, body: request.body }));
        if (result.ok) {
          showSuccessToast($_('pages.shipments.toast.cancelled'));
          await invalidateAll();
          return undefined;
        }
        showErrorToast(failureText($_, result.error, result.body));
        await invalidateAll();
      },
    });
  }
</script>
