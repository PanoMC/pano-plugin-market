<ConfirmModal bind:this={confirm} />
<ShippingZoneModal bind:this={zoneModal} onSaved={reload} />

{#if loadError}
  <LoadError error={loadError} onRetry={load} />
{:else if loading}
  <div class="text-center text-body-secondary py-5">
    <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
    <span class="visually-hidden">{$_('common.loading')}</span>
  </div>
{:else}
  <div class="alert alert-info d-flex align-items-start" role="alert">
    <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
    <div>{$_('settings.shipping-zones.first-match')}</div>
  </div>

  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('settings.shipping-zones.count', { values: { count: zones.length } })}
      </div>
      <div slot="right">
        <button
          type="button"
          class="btn btn-sm btn-link"
          disabled={zones.length >= MAX_ZONES}
          onclick={() => zoneModal?.open(null, zones)}>
          <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
          {$_('settings.shipping-zones.create')}
        </button>
      </div>
    </CardHeader>

    {#if zones.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th class="align-middle text-nowrap" scope="col"></th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.name')}</th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-zones.table.countries')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-zones.table.regions')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-zones.table.postal')}
              </th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            </tr>
          </thead>
          <tbody>
            {#each zones as zone, index (zone.id)}
              {@const summary = countrySummary(zone)}
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
                      <button
                        type="button"
                        class="dropdown-item"
                        onclick={() => zoneModal?.open(zone, zones)}>
                        <i class="fa-solid fa-pen me-2" aria-hidden="true"></i>
                        {$_('common.edit')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={index === 0 || busy}
                        onclick={() => move(zone, -1)}>
                        <i class="fa-solid fa-arrow-up me-2" aria-hidden="true"></i>
                        {$_('common.move-up')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={index === zones.length - 1 || busy}
                        onclick={() => move(zone, 1)}>
                        <i class="fa-solid fa-arrow-down me-2" aria-hidden="true"></i>
                        {$_('common.move-down')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item text-danger"
                        onclick={() => askDelete(zone)}>
                        <i class="fa-solid fa-trash me-2" aria-hidden="true"></i>
                        {$_('common.delete')}
                      </button>
                    </div>
                  </div>
                </th>
                <td class="text-nowrap">
                  {zone.name}
                  {#if zone.shadowedBy}
                    <i
                      class="fa-solid fa-triangle-exclamation text-warning ms-1"
                      role="img"
                      aria-label={$_('settings.shipping-zones.shadowed')}
                      use:tooltip={[$_('settings.shipping-zones.shadowed')]}></i>
                  {/if}
                </td>
                <td class="text-nowrap">
                  {#if summary.everywhere}
                    {$_('settings.shipping-zones.everywhere')}
                  {:else}
                    {summary.shown.join(', ')}{#if summary.more > 0}
                      <span class="text-body-secondary">
                        {$_('settings.shipping-zones.more', { values: { count: summary.more } })}
                      </span>
                    {/if}
                  {/if}
                </td>
                <td class="text-nowrap">{regionCount(zone)}</td>
                <td class="text-nowrap">{postalCount(zone)}</td>
                <td class="text-nowrap">
                  <span
                    class="badge {zone.status === 'INACTIVE'
                      ? 'text-bg-secondary'
                      : 'text-bg-success'}">
                    {zone.status === 'INACTIVE' ? $_('common.inactive') : $_('common.active')}
                  </span>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </div>
{/if}

<script>
  import { onMount } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import LoadError from '../LoadError.svelte';
  import ShippingZoneModal from '../modals/ShippingZoneModal.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { movedIds } from '../../utils/payment-methods.js';
  import {
    MAX_ZONES,
    countrySummary,
    postalCount,
    regionCount,
  } from '../../utils/shipping-rates.js';
  import { toastError } from '../../utils/toast.js';

  // Section `shipping-zones` of the settings page (13 §19.1). Loads GET /shipping/zones itself.
  let confirm = $state(null);
  let zoneModal = $state(null);
  // extra / extraError: GET /shipping/zones loaded with the page (utils/settings.js extraPathFor).
  let { extra = null, extraError = null } = $props();

  let zones = $state.raw(Array.isArray(extra?.zones) ? extra.zones : []);
  let loading = $state(!extra && !extraError);
  let loadError = $state(extraError);
  let busy = $state(false);

  async function fetchZones() {
    const result = await call(ApiUtil.get({ path: marketPath('/shipping/zones') }));
    if (!result.ok) return result.error;
    zones = Array.isArray(result.body.zones) ? result.body.zones : [];
    return null;
  }

  async function load() {
    loading = true;
    loadError = await fetchZones();
    loading = false;
  }

  // A refresh after a change keeps the table on screen; a failure only toasts.
  async function reload() {
    const error = await fetchZones();
    if (error) showErrorToast($_('common.error-generic'));
  }

  onMount(() => {
    if (!extra && !extraError) load();
  });

  async function move(zone, delta) {
    const ids = movedIds(zones, zone.id, delta);
    if (!ids || busy) return;
    const before = zones;
    zones = ids.map((id) => before.find((z) => z.id === id));
    busy = true;
    let result;
    try {
      result = await call(
        ApiUtil.post({ path: marketPath('/shipping/zones/sort'), body: { ids } }),
      );
    } finally {
      busy = false;
    }
    if (!result.ok) {
      zones = before;
      toastError($_, result);
    }
    await reload();
  }

  function askDelete(zone) {
    confirm?.open({
      icon: 'fa-solid fa-trash',
      variant: 'danger',
      title: $_('modals.confirm-delete.zone.title'),
      description: $_('modals.confirm-delete.zone.description', {
        values: { name: zone.name, count: Number(zone.rateCount ?? 0) },
      }),
      confirmLabel: $_('common.delete'),
      onConfirm: async () => {
        const result = await call(
          ApiUtil.delete({ path: marketPath(`/shipping/zones/${zone.id}`) }),
        );
        if (!result.ok && result.error !== 'NOT_FOUND') {
          toastError($_, result);
          return false;
        }
        showSuccessToast($_('settings.shipping-zones.toast-deleted'));
        await reload();
      },
    });
  }
</script>
