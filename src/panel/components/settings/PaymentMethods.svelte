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
        <b>{$_('settings.payments.test-mode-title')}</b>
        <div>{$_('settings.payments.test-mode-hint')}</div>
      </div>
    </div>
  {/if}

  {#if noPlugins}
    <div class="alert alert-info d-flex align-items-start" role="alert">
      <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.payments.no-plugins-title')}</b>
        <div>{$_('settings.payments.no-plugins-body')}</div>
        {#if isHttpUrl(ctx?.storeUrl)}
          <a class="alert-link" href={ctx.storeUrl} target="_blank" rel="noopener noreferrer">
            {$_('settings.payments.open-store')}
          </a>
        {/if}
      </div>
    </div>
  {/if}

  <div class="row g-2 align-items-center mb-3">
    <div class="col-12 col-md">
      {$_('settings.payments.method-count', { values: { count: visible.length } })}
    </div>
    <div class="col-12 col-md-4">
      <SearchInput
        initialValue={searchValue}
        placeholderKey="plugins.pano-plugin-market.search.payment-methods"
        onchange={(val) => applyFilters({ search: val })} />
    </div>
    <div class="col-12 col-md-auto">
      <div class="btn-group" role="group" aria-label={$_('settings.payments.region-label')}>
        {#each REGIONS as item (item)}
          <button
            type="button"
            class="btn btn-outline-secondary"
            class:active={regionFilter === item}
            aria-pressed={regionFilter === item}
            onclick={() => applyFilters({ region: item })}>
            {item === 'all'
              ? $_('common.all')
              : item === 'tr'
                ? $_('settings.payments.filters.turkey')
                : $_('settings.payments.filters.global')}
          </button>
        {/each}
      </div>
    </div>
  </div>

  {#if visible.length === 0}
    <NoContent />
  {:else}
    <div class="row g-3">
      {#each visible as provider (provider.id)}
        {@const behavior = rowBehavior(provider)}
        {@const label = providerName(provider, locale(), rawTranslate)}
        {@const orderIndex = ordered.findIndex((p) => p.id === provider.id)}
        {@const checked = pending[provider.id] ?? behavior.switchOn}
        <div class="col-md-6 col-xl-4">
          <div class="card h-100" class:border-danger={behavior.danger}>
            <div class="card-body d-flex flex-column gap-3">
              <div class="d-flex align-items-start gap-2">
                <div
                  class="d-flex align-items-center justify-content-center rounded flex-shrink-0 overflow-hidden bg-body-secondary"
                  style="width: 40px; height: 40px;">
                  {#if !logoFailed[provider.id]}
                    <img
                      src={logoPath(base, provider.id)}
                      alt={$_('settings.payments.logo-alt', { values: { name: label } })}
                      loading="lazy"
                      style="max-width: 70%; max-height: 70%; object-fit: contain;"
                      onerror={() => (logoFailed[provider.id] = true)} />
                  {:else}
                    <i class={provider.descriptor?.icon || 'fa-solid fa-credit-card'} aria-hidden="true"
                    ></i>
                  {/if}
                </div>
                <div class="flex-grow-1 min-w-0">
                  <h6 class="mb-1 text-break">{label}</h6>
                  <p class="mb-0 text-body-secondary">
                    {provider.config?.customDescription || txt(provider.descriptor?.description)}
                  </p>
                </div>
              </div>

              <div class="d-flex flex-wrap gap-1">
                <StatusBadge kind="provider" value={provider.state} />
                {#if effectiveTestMode(provider, ctx)}
                  <span class="badge text-bg-warning">{$_('common.test')}</span>
                {/if}
                {#if provider.descriptor?.verification}
                  <span class="badge text-bg-secondary">
                    {$_(`enums.verification.${provider.descriptor.verification}`)}
                  </span>
                {/if}
                {#if provider.descriptor?.region}
                  <span class="badge text-bg-light border">
                    {provider.descriptor.region === 'global'
                      ? $_('settings.payments.filters.global')
                      : provider.descriptor.region.toUpperCase()}
                  </span>
                {/if}
              </div>

              {#if behavior.reason === 'incompatible'}
                <div class="small text-danger">
                  {$_('settings.payments.incompatible', {
                    values: { spiVersion: provider.spiVersion ?? '' },
                  })}
                </div>
              {:else if behavior.reason === 'unavailable'}
                <div class="small text-danger">
                  {#if provider.lastError}
                    <div class="text-break">{provider.lastError}</div>
                  {/if}
                  <div>{$_('settings.payments.unavailable')}</div>
                  <div>
                    {$_('settings.payments.unavailable-hint')}
                    <a href="{base}/addons">{$_('settings.payments.open-addons')}</a>
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
                      id="pm-toggle-{provider.id}"
                      aria-label={$_('settings.payments.toggle-aria', { values: { name: label } })}
                      {checked}
                      disabled={behavior.switchDisabled || busy}
                      onchange={(e) => toggleEnabled(provider, e)} />
                  </div>
                {/snippet}
                {#if behavior.switchHint}
                  <span use:tooltip={[$_('settings.payments.configure-first')]}>{@render toggle()}</span>
                {:else}
                  {@render toggle()}
                {/if}

                <button
                  type="button"
                  class="btn btn-outline-secondary btn-sm ms-auto"
                  onclick={() => modal?.open(provider)}>
                  {behavior.readOnly ? $_('settings.payments.view') : $_('settings.payments.configure')}
                </button>

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
                    <button
                      type="button"
                      class="dropdown-item"
                      disabled={orderIndex <= 0 || busy}
                      onclick={() => move(provider, -1)}>
                      <i class="fa-solid fa-arrow-up me-2" aria-hidden="true"></i>
                      {$_('common.move-up')}
                    </button>
                    <button
                      type="button"
                      class="dropdown-item"
                      disabled={orderIndex === -1 || orderIndex >= ordered.length - 1 || busy}
                      onclick={() => move(provider, 1)}>
                      <i class="fa-solid fa-arrow-down me-2" aria-hidden="true"></i>
                      {$_('common.move-down')}
                    </button>
                    {#if isHttpUrl(provider.descriptor?.docsUrl)}
                      <a
                        class="dropdown-item"
                        href={provider.descriptor.docsUrl}
                        target="_blank"
                        rel="noopener">
                        <i class="fa-solid fa-book me-2" aria-hidden="true"></i>
                        {$_('settings.payments.docs')}
                      </a>
                    {/if}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      {/each}
    </div>
  {/if}
{/if}

<PaymentMethodModal bind:this={modal} {ctx} onRefresh={refresh} getProvider={findProvider} />

<script>
  import { onMount } from 'svelte';
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { NoContent, SearchInput } from '@panomc/sdk/components/panel';
  import { base, page, goto } from '@panomc/sdk/svelte';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _, showSuccessToast } from '../../../i18n';
  import LoadError from '../LoadError.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import PaymentMethodModal from '../modals/PaymentMethodModal.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { loadContext } from '../../utils/context.js';
  import { currentLocale } from '../../utils/locale.js';
  import {
    REGIONS,
    effectiveTestMode,
    filterProviders,
    isHttpUrl,
    logoPath,
    movedIds,
    onlyBuiltIns,
    providerName,
    regionOf,
    rowBehavior,
    sortProviders,
  } from '../../utils/payment-methods.js';
  import { resolveText } from '../../utils/schema-form.js';
  import { toastError } from '../../utils/toast.js';

  // The list is driven by the provider registry (GET /payment-providers), not by a catalogue. The
  // settings page may pass the shared `ctx`; without it the component reads GET /context itself.
  let { ctx: ctxProp = null } = $props();

  let providers = $state.raw([]);
  let loadedCtx = $state.raw(null);
  let loading = $state(true);
  let loadError = $state(null);
  let busy = $state(false);
  // optimistic switch positions: provider id -> boolean, dropped when the request settles
  let pending = $state({});
  let logoFailed = $state({});
  let modal = $state(null);

  const ctx = $derived(ctxProp ?? loadedCtx);
  const locale = () => currentLocale();
  const rawTranslate = (key) => $rawI18n(key, { default: key });
  const txt = (text) => resolveText(text, currentLocale(), rawTranslate);

  // Region / search live in the URL (?region=tr&search=...). They filter the loaded list client
  // side, so changing them navigates WITHOUT invalidateAll: the $page store updates and nothing is
  // refetched. `section=payments` is kept so the Settings page keeps rendering this section.
  const regionFilter = $derived(regionOf($page.url.searchParams.get('region')));
  const searchValue = $derived($page.url.searchParams.get('search') || '');

  function applyFilters({ region = regionFilter, search = searchValue } = {}) {
    const queryParams = buildQueryParams({
      section: 'payments',
      region: region === 'all' ? null : region,
      search: search || null,
    });
    goto(`${base}/market/settings${queryParams}`, { keepFocus: true, noscroll: true });
  }

  const ordered = $derived(sortProviders(providers, currentLocale(), rawTranslate));
  const visible = $derived(
    filterProviders(
      ordered,
      { region: regionFilter, search: searchValue },
      currentLocale(),
      rawTranslate,
    ),
  );
  const noPlugins = $derived(onlyBuiltIns(providers));
  const showTestHint = $derived(
    Boolean(ctx?.testMode) || providers.some((p) => effectiveTestMode(p, ctx)),
  );

  const findProvider = (id) => providers.find((p) => p.id === id) ?? null;

  async function fetchProviders() {
    const result = await call(ApiUtil.get({ path: marketPath('/payment-providers') }));
    if (!result.ok) return { error: result.error };
    return { providers: Array.isArray(result.body.providers) ? result.body.providers : [] };
  }

  async function load() {
    loading = true;
    loadError = null;
    const [fetched, context] = await Promise.all([
      fetchProviders(),
      ctxProp ? Promise.resolve(null) : loadContext(undefined),
    ]);
    if (fetched.error) loadError = fetched.error;
    else providers = fetched.providers;
    if (context) loadedCtx = context;
    loading = false;
  }

  // Reloads in place (no navigation: that would remount the settings page and drop its section).
  async function refresh() {
    const fetched = await fetchProviders();
    if (!fetched.error) providers = fetched.providers;
  }

  onMount(() => {
    load();
  });

  async function toggleEnabled(provider, event) {
    // Captured synchronously; currentTarget is nulled once the handler yields.
    const input = event.currentTarget;
    const next = input.checked;
    const label = providerName(provider, currentLocale(), rawTranslate);

    pending[provider.id] = next;
    busy = true;
    let result;
    try {
      result = await call(
        ApiUtil.post({
          path: marketPath(`/payment-methods/${provider.id}/toggle`),
          body: { enabled: next },
        }),
      );
    } finally {
      busy = false;
    }

    if (!result.ok) {
      delete pending[provider.id];
      input.checked = rowBehavior(provider).switchOn;
      toastError($_, result);
      if (result.error === 'PROVIDER_UNAVAILABLE' || result.error === 'NOT_FOUND') await refresh();
      return;
    }

    showSuccessToast(
      next
        ? $_('settings.payments.toast-activated', { values: { name: label } })
        : $_('settings.payments.toast-deactivated', { values: { name: label } }),
    );
    await refresh();
    delete pending[provider.id];
  }

  async function move(provider, delta) {
    const ids = movedIds(ordered, provider.id, delta);
    if (!ids) return;
    const before = providers;
    // optimistic: positions follow the new id order
    providers = providers.map((p) => ({
      ...p,
      config: { ...p.config, position: ids.indexOf(p.id) },
    }));
    busy = true;
    let result;
    try {
      result = await call(ApiUtil.post({ path: marketPath('/payment-methods/sort'), body: { ids } }));
    } finally {
      busy = false;
    }
    if (!result.ok) {
      providers = before;
      toastError($_, result);
      return;
    }
    await refresh();
  }
</script>
