{#if loadError}
  <LoadError error={loadError} onRetry={load} />
{:else if loading}
  <div class="text-center text-body-secondary py-5">
    <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
    <span class="visually-hidden">{$_('common.loading')}</span>
  </div>
{:else}
  {#if showTestHint}
    <div class="alert alert-info d-flex align-items-start" role="alert">
      <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.shipping-carriers.test-mode-title')}</b>
        <div>{$_('settings.shipping-carriers.test-mode-hint')}</div>
      </div>
    </div>
  {/if}

  {#if noPlugins}
    <div class="alert alert-info d-flex align-items-start" role="alert">
      <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.shipping-carriers.no-plugins-title')}</b>
        <div>{$_('settings.shipping-carriers.no-plugins-body')}</div>
        {#if isHttpUrl(ctx?.storeUrl)}
          <a class="alert-link" href={ctx.storeUrl} target="_blank" rel="noopener noreferrer">
            {$_('settings.shipping-carriers.open-store')}
          </a>
        {/if}
      </div>
    </div>
  {/if}

  <div class="mb-3">
    {$_('settings.shipping-carriers.count', { values: { count: ordered.length } })}
  </div>

  {#if ordered.length === 0}
    <NoContent icon="" />
  {:else}
    <div class="row g-3">
      {#each ordered as carrier (carrier.id)}
        {@const behavior = carrierBehavior(carrier)}
        {@const label = providerName(carrier, locale(), rawTranslate)}
        {@const checked = pending[carrier.id] ?? behavior.switchOn}
        <div class="col-md-6 col-xl-4">
          <div class="card h-100" class:border-danger={behavior.danger}>
            <div class="card-body d-flex flex-column gap-3">
              <div class="d-flex align-items-start gap-2">
                <div
                  class="d-flex align-items-center justify-content-center rounded flex-shrink-0 overflow-hidden bg-body-secondary"
                  style="width: 40px; height: 40px;">
                  <i class={iconOf(carrier)} aria-hidden="true"></i>
                </div>
                <div class="flex-grow-1 min-w-0">
                  <h6 class="mb-1 text-break">{label}</h6>
                  <p class="mb-0 text-body-secondary">{txt(carrier.descriptor?.description)}</p>
                </div>
              </div>

              <div class="d-flex flex-wrap gap-1">
                <StatusBadge kind="provider" value={carrier.state} />
                {#if effectiveTestMode(carrier, ctx)}
                  <span class="badge text-bg-warning">{$_('common.test')}</span>
                {/if}
                {#if carrier.descriptor?.verification}
                  <span class="badge text-bg-secondary">
                    {$_(`enums.verification.${carrier.descriptor.verification}`)}
                  </span>
                {/if}
                {#if Number(carrier.methodCount) > 0}
                  <span class="badge text-bg-light border">
                    {$_('settings.shipping-carriers.methods', {
                      values: { count: Number(carrier.methodCount) },
                    })}
                  </span>
                {/if}
              </div>

              {#if behavior.reason === 'incompatible'}
                <div class="small text-danger">
                  {$_('settings.shipping-carriers.incompatible', {
                    values: { spiVersion: carrier.spiVersion ?? '' },
                  })}
                </div>
              {:else if behavior.reason === 'unavailable'}
                <div class="small text-danger">
                  {#if carrier.lastError}
                    <div class="text-break">{carrier.lastError}</div>
                  {/if}
                  <div>{$_('settings.shipping-carriers.unavailable')}</div>
                  <div>
                    {$_('settings.shipping-carriers.unavailable-hint')}
                    <a href="{base}/addons">{$_('settings.shipping-carriers.open-addons')}</a>
                  </div>
                </div>
              {/if}

              <div class="d-flex align-items-center gap-2 mt-auto">
                {#snippet toggle()}
                  <div class="form-check form-switch m-0">
                    <input
                      class="form-check-input"
                      type="checkbox"
                      role="switch"
                      id="carrier-toggle-{carrier.id}"
                      aria-label={$_('settings.shipping-carriers.toggle-aria', {
                        values: { name: label },
                      })}
                      {checked}
                      disabled={behavior.switchDisabled || busy}
                      onchange={(e) => toggleEnabled(carrier, e)} />
                  </div>
                {/snippet}
                {#if behavior.manualLocked}
                  <span use:tooltip={[$_('settings.shipping-carriers.manual-locked')]}
                    >{@render toggle()}</span>
                {:else if behavior.switchHint}
                  <span use:tooltip={[$_('settings.shipping-carriers.configure-first')]}>
                    {@render toggle()}
                  </span>
                {:else}
                  {@render toggle()}
                {/if}

                <button
                  type="button"
                  class="btn btn-outline-secondary btn-sm ms-auto"
                  onclick={() => modal?.open(carrier)}>
                  {behavior.readOnly
                    ? $_('settings.shipping-carriers.view')
                    : $_('settings.shipping-carriers.configure')}
                </button>

                {#if isHttpUrl(carrier.descriptor?.docsUrl)}
                  <div class="dropdown">
                    <button
                      type="button"
                      class="btn btn-link btn-sm"
                      data-bs-toggle="dropdown"
                      title={$_('common.actions')}
                      aria-label={$_('common.actions')}>
                      <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
                    </button>
                    <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
                      <a
                        class="dropdown-item"
                        href={carrier.descriptor.docsUrl}
                        target="_blank"
                        rel="noopener noreferrer">
                        <i class="fa-solid fa-book me-2" aria-hidden="true"></i>
                        {$_('settings.shipping-carriers.docs')}
                      </a>
                    </div>
                  </div>
                {/if}
              </div>
            </div>
          </div>
        </div>
      {/each}
    </div>
  {/if}
{/if}

<CarrierModal bind:this={modal} onRefresh={refresh} getCarrier={findCarrier} />

<script>
  import { onMount } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { NoContent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _, showSuccessToast } from '../../../i18n';
  import LoadError from '../LoadError.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import CarrierModal from '../modals/CarrierModal.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { loadContext } from '../../utils/context.js';
  import { currentLocale } from '../../utils/locale.js';
  import { effectiveTestMode, isHttpUrl, providerName } from '../../utils/payment-methods.js';
  import { resolveText } from '../../utils/schema-form.js';
  import { carrierBehavior, onlyManualCarrier, sortCarriers } from '../../utils/shipping-rates.js';
  import { toastError } from '../../utils/toast.js';

  // Section `shipping-carriers` of the settings page (13 §19.3): the twin of the payment method grid
  // for GET /shipping/carriers, without a rules tab and without reordering. The settings page may pass
  // the shared `ctx`; without it the component reads GET /context itself.
  let { ctx: ctxProp = null } = $props();

  let carriers = $state.raw([]);
  let loadedCtx = $state.raw(null);
  let loading = $state(true);
  let loadError = $state(null);
  let busy = $state(false);
  // optimistic switch positions: carrier id -> boolean, dropped when the request settles
  let pending = $state({});
  let modal = $state(null);

  const ctx = $derived(ctxProp ?? loadedCtx);
  const locale = () => currentLocale();
  const rawTranslate = (key) => $rawI18n(key, { default: key });
  const txt = (text) => resolveText(text, currentLocale(), rawTranslate);
  const nameOf = (c) => providerName(c, currentLocale(), rawTranslate);

  const ordered = $derived(sortCarriers(carriers, nameOf));
  const noPlugins = $derived(onlyManualCarrier(carriers));
  const showTestHint = $derived(
    Boolean(ctx?.testMode) || carriers.some((c) => effectiveTestMode(c, ctx)),
  );

  const findCarrier = (id) => carriers.find((c) => c.id === id) ?? null;
  // The descriptor icon comes from API data and is only used as a Font Awesome class when it looks like one.
  const iconOf = (carrier) =>
    /^[a-z0-9 -]{1,64}$/i.test(carrier.descriptor?.icon ?? '')
      ? carrier.descriptor.icon
      : 'fa-solid fa-truck';

  async function fetchCarriers() {
    const result = await call(ApiUtil.get({ path: marketPath('/shipping/carriers') }));
    if (!result.ok) return { error: result.error };
    const list = result.body.carriers ?? result.body.providers;
    return { carriers: Array.isArray(list) ? list : [] };
  }

  async function load() {
    loading = true;
    loadError = null;
    const [fetched, context] = await Promise.all([
      fetchCarriers(),
      ctxProp ? Promise.resolve(null) : loadContext(undefined),
    ]);
    if (fetched.error) loadError = fetched.error;
    else carriers = fetched.carriers;
    if (context) loadedCtx = context;
    loading = false;
  }

  // Reloads in place (no navigation: that would remount the settings page and drop its section).
  async function refresh() {
    const fetched = await fetchCarriers();
    if (!fetched.error) carriers = fetched.carriers;
  }

  onMount(() => {
    load();
  });

  async function toggleEnabled(carrier, event) {
    // Captured synchronously; currentTarget is nulled once the handler yields.
    const input = event.currentTarget;
    const next = input.checked;
    const label = nameOf(carrier);

    pending[carrier.id] = next;
    busy = true;
    let result;
    try {
      result = await call(
        ApiUtil.post({
          path: marketPath(`/shipping/carriers/${encodeURIComponent(carrier.id)}/toggle`),
          body: { enabled: next },
        }),
      );
    } finally {
      busy = false;
    }

    if (!result.ok) {
      delete pending[carrier.id];
      input.checked = carrierBehavior(carrier).switchOn;
      toastError($_, result);
      if (result.error === 'PROVIDER_UNAVAILABLE' || result.error === 'NOT_FOUND') await refresh();
      return;
    }

    showSuccessToast(
      next
        ? $_('settings.shipping-carriers.toast-activated', { values: { name: label } })
        : $_('settings.shipping-carriers.toast-deactivated', { values: { name: label } }),
    );
    await refresh();
    delete pending[carrier.id];
  }
</script>
