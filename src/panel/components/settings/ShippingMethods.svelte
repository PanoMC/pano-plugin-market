<ConfirmModal bind:this={confirm} />

{#if loadError}
  <LoadError error={loadError} onRetry={load} />
{:else if loading}
  <div class="text-center text-body-secondary py-5">
    <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
    <span class="visually-hidden">{$_('common.loading')}</span>
  </div>
{:else}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('settings.shipping-methods.count', { values: { count: methods.length } })}
      </div>
      <div slot="right">
        <a class="btn btn-sm btn-link" href="{base}/market/settings/shipping-method">
          <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
          {$_('settings.shipping-methods.create')}
        </a>
      </div>
    </CardHeader>

    {#if methods.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th class="align-middle text-nowrap" scope="col"></th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.name')}</th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-methods.table.provider')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-methods.table.rate-source')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-methods.table.zones')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.shipping-methods.table.free-threshold')}
              </th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            </tr>
          </thead>
          <tbody>
            {#each methods as method, index (method.id)}
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
                      <a
                        class="dropdown-item"
                        href="{base}/market/settings/shipping-method?id={method.id}">
                        <i class="fa-solid fa-pen me-2" aria-hidden="true"></i>
                        {$_('common.edit')}
                      </a>
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={index === 0 || busy}
                        onclick={() => move(method, -1)}>
                        <i class="fa-solid fa-arrow-up me-2" aria-hidden="true"></i>
                        {$_('common.move-up')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={index === methods.length - 1 || busy}
                        onclick={() => move(method, 1)}>
                        <i class="fa-solid fa-arrow-down me-2" aria-hidden="true"></i>
                        {$_('common.move-down')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item text-danger"
                        onclick={() => askDelete(method)}>
                        <i class="fa-solid fa-trash me-2" aria-hidden="true"></i>
                        {$_('common.delete')}
                      </button>
                    </div>
                  </div>
                </th>
                <td class="text-nowrap">
                  {method.name}
                  {#if (method.rates ?? []).length === 0}
                    <span
                      class="badge text-bg-warning ms-1"
                      use:tooltip={[$_('settings.shipping-methods.no-rates-hint')]}>
                      {$_('settings.shipping-methods.no-rates')}
                    </span>
                  {/if}
                </td>
                <td class="text-nowrap">{providerLabel(method.providerId)}</td>
                <td class="text-nowrap">
                  {$_(`enums.shipping-rate-source.${rateSourceOf(method.rateSource)}`)}
                </td>
                <td class="text-nowrap">{zoneCount(method.rates)}</td>
                <td class="text-nowrap">
                  {method.freeShippingThreshold === null ||
                  method.freeShippingThreshold === undefined
                    ? '—'
                    : fmt.money(method.freeShippingThreshold, ctx?.currency)}
                </td>
                <td class="text-nowrap">
                  <span
                    class="badge {method.status === 'INACTIVE'
                      ? 'text-bg-secondary'
                      : 'text-bg-success'}">
                    {method.status === 'INACTIVE' ? $_('common.inactive') : $_('common.active')}
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
  import { base } from '@panomc/sdk/svelte';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import LoadError from '../LoadError.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { loadContext } from '../../utils/context.js';
  import { currentLocale, fmt } from '../../utils/locale.js';
  import { movedIds, providerName } from '../../utils/payment-methods.js';
  import { RATE_SOURCES, zoneCount } from '../../utils/shipping-rates.js';
  import { toastError } from '../../utils/toast.js';

  // Section `shipping-methods` of the settings page (13 §19.2). The settings page may pass the shared
  // `ctx`; without it the component reads GET /context itself.
  let { ctx: ctxProp = null } = $props();

  let confirm = $state(null);
  let methods = $state.raw([]);
  let carriers = $state.raw([]);
  let loadedCtx = $state.raw(null);
  let loading = $state(true);
  let loadError = $state(null);
  let busy = $state(false);

  const ctx = $derived(ctxProp ?? loadedCtx);
  const rawTranslate = (key) => $rawI18n(key, { default: key });
  const rateSourceOf = (value) => (RATE_SOURCES.includes(value) ? value : 'RULES');

  function providerLabel(id) {
    const carrier = carriers.find((c) => c.id === id);
    return carrier ? providerName(carrier, currentLocale(), rawTranslate) : (id ?? '');
  }

  async function fetchAll() {
    const [methodsResult, carriersResult] = await Promise.all([
      call(ApiUtil.get({ path: marketPath('/shipping/methods') })),
      call(ApiUtil.get({ path: marketPath('/shipping/carriers') })),
    ]);
    if (!methodsResult.ok) return methodsResult.error;
    methods = Array.isArray(methodsResult.body.methods) ? methodsResult.body.methods : [];
    // names of the providers are a nicety: a failed carrier list falls back to the raw ids
    if (carriersResult.ok)
      carriers = carriersResult.body.carriers ?? carriersResult.body.providers ?? [];
    return null;
  }

  async function load() {
    loading = true;
    const [error, context] = await Promise.all([
      fetchAll(),
      ctxProp ? Promise.resolve(null) : loadContext(undefined),
    ]);
    loadError = error;
    if (context) loadedCtx = context;
    loading = false;
  }

  // A refresh after a change keeps the table on screen; a failure only toasts.
  async function reload() {
    const error = await fetchAll();
    if (error) showErrorToast($_('common.error-generic'));
  }

  onMount(load);

  async function move(method, delta) {
    const ids = movedIds(methods, method.id, delta);
    if (!ids || busy) return;
    const before = methods;
    methods = ids.map((id) => before.find((m) => m.id === id));
    busy = true;
    let result;
    try {
      result = await call(
        ApiUtil.post({ path: marketPath('/shipping/methods/sort'), body: { ids } }),
      );
    } finally {
      busy = false;
    }
    if (!result.ok) {
      methods = before;
      toastError($_, result);
    }
    await reload();
  }

  function askDelete(method) {
    confirm?.open({
      icon: 'fa-solid fa-trash',
      variant: 'danger',
      title: $_('modals.confirm-delete.method.title'),
      description: $_('modals.confirm-delete.method.description', {
        values: { name: method.name },
      }),
      confirmLabel: $_('common.delete'),
      onConfirm: async () => {
        const result = await call(
          ApiUtil.delete({ path: marketPath(`/shipping/methods/${method.id}`) }),
        );
        if (!result.ok && result.error !== 'NOT_FOUND') {
          toastError($_, result);
          return false;
        }
        showSuccessToast($_('settings.shipping-methods.toast-deleted'));
        await reload();
      },
    });
  }
</script>
