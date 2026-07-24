<script>
  import { tick } from 'svelte';
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, NoContent } from '@panomc/sdk/components/panel';
  import { base, page, goto } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { PAYMENT_METHODS, createDefaultMethodState } from '../../data/payment-methods.js';
  import PaymentMethodSettingsModal from '../modals/PaymentMethodSettingsModal.svelte';
  import { _ } from '../../../i18n';

  let { settings: initialSettings = {} } = $props();

  // Local writable copy of the loaded settings: re-derived if the page load()
  // re-runs, reassigned by the client-side refresh() after a save (navigating
  // would remount the whole plugin page and drop the active settings tab).
  let settings = $derived(initialSettings);

  let activeMethod = $state(null);

  // Region/search filters live in the URL (?region=tr&search=...) so they are
  // bookmarkable and back-button correct. They filter the static 13-method
  // catalog client-side, so changing them navigates WITHOUT invalidateAll: the
  // $page store updates, the filtered list recomputes, and no settings refetch
  // (or plugin-page remount) happens. `section=payments` is preserved so the
  // Settings page keeps rendering this tab; `all`/empty are omitted.
  const regionFilter = $derived.by(() => {
    const value = $page.url.searchParams.get('region');
    return value === 'tr' || value === 'global' ? value : 'all';
  });
  const searchValue = $derived($page.url.searchParams.get('search') || '');

  function applyFilters({ region = regionFilter, search = searchValue } = {}) {
    const queryParams = buildQueryParams({
      section: 'payments',
      region: region === 'all' ? null : region,
      search: search || null,
    });
    goto(`${base}/market/settings${queryParams}`, { keepFocus: true, noscroll: true });
  }

  // Overlay the server's { methodId: { enabled, settings } } onto the default catalog state.
  // Secret fields arrive masked as '********' (still truthy, so the configured check passes).
  function buildMethodState(paymentMethods) {
    const fresh = createDefaultMethodState();

    if (paymentMethods) {
      for (const method of PAYMENT_METHODS) {
        const stored = paymentMethods[method.id];
        if (!stored) continue;

        fresh[method.id].enabled = Boolean(stored.enabled);

        const stateSettings = stored.settings ?? {};
        for (const field of method.fields) {
          if (stateSettings[field.key] !== undefined) {
            fresh[method.id].settings[field.key] = stateSettings[field.key];
          }
        }
      }
    }

    return fresh;
  }

  // Writable derived: rebuilds from server state whenever the loaded settings
  // change (e.g. after a refresh()). NOTE: the derived value is a plain object,
  // NOT a $state proxy — mutating it in place (st.enabled = ...) does not
  // re-render. All updates must go through setMethodEnabled/reassignment.
  let methodState = $derived(buildMethodState(settings.paymentMethods));

  // Reactively flip a method's enabled flag by reassigning the derived, and keep
  // the clicked checkbox's DOM state in sync (a no-op state write, e.g. reverting
  // false -> false after a rejected enable, would otherwise leave the user-clicked
  // checkbox out of sync with the state).
  function setMethodEnabled(methodId, enabled, input) {
    methodState = {
      ...methodState,
      [methodId]: { ...methodState[methodId], enabled },
    };
    if (input) input.checked = enabled;
  }

  async function refresh() {
    const body = await ApiUtil.get({ path: '/api/panel/market/settings' });
    if (body && !body.error) {
      settings = body;
    }
  }

  const filteredMethods = $derived.by(() => {
    const term = searchValue.trim().toLowerCase();
    return PAYMENT_METHODS.filter((m) => {
      if (regionFilter !== 'all' && m.region !== regionFilter) return false;
      if (!term) return true;
      return (
        $_(m.name).toLowerCase().includes(term) ||
        $_(m.description).toLowerCase().includes(term) ||
        m.id.toLowerCase().includes(term)
      );
    });
  });

  function isConfigured(method) {
    const settings = methodState[method.id]?.settings ?? {};
    return method.fields
      .filter((f) => f.required)
      .every((f) => Boolean(settings[f.key]));
  }

  async function toggleEnabled(method, event) {
    // Captured synchronously; currentTarget is nulled once the handler yields.
    const input = event?.currentTarget ?? null;

    const st = methodState[method.id];
    if (!st) return;

    const next = !st.enabled;

    if (next && !isConfigured(method)) {
      showToast($_('settings.payments.toast-required-settings', { values: { name: $_(method.name) } }));
      setMethodEnabled(method.id, false, input);
      activeMethod = method;
      return;
    }

    // Optimistically reflect the checkbox the user just clicked; revert on failure.
    setMethodEnabled(method.id, next, input);

    try {
      const body = await ApiUtil.post({
        path: `/api/panel/market/payment-methods/${method.id}/toggle`,
        body: { enabled: next }
      });

      // No body: demo mode (ApiUtil already toasted) or a swallowed error — revert.
      if (!body) {
        setMethodEnabled(method.id, !next, input);
        return;
      }

      if (body.error) {
        setMethodEnabled(method.id, !next, input);
        if (body.error === 'PAYMENT_METHOD_NOT_CONFIGURED') {
          showToast($_('settings.payments.toast-enable-failed-not-configured', { values: { name: $_(method.name) } }));
        } else {
          showToast($_('settings.payments.toast-update-error', { values: { name: $_(method.name) } }));
        }
        return;
      }

      showToast(next
        ? $_('settings.payments.toast-activated', { values: { name: $_(method.name) } })
        : $_('settings.payments.toast-deactivated', { values: { name: $_(method.name) } }));
      await refresh();
    } catch (e) {
      setMethodEnabled(method.id, !next, input);
      showToast($_('settings.payments.toast-update-error', { values: { name: $_(method.name) } }));
    }
  }

  // Open the shared settings modal for a specific method. We do NOT use Bootstrap's
  // data-bs-toggle: it opens the modal synchronously on click, before Svelte flushes
  // the activeMethod change into the modal's `method`/`settingsState` props, so the
  // modal would init from the previously-clicked (stale) method. Instead we set
  // activeMethod, await tick() so the props (and the modal's init $effect) settle,
  // then show the modal programmatically — its show.bs.modal handler re-inits the
  // form from the now-current method.
  async function openSettings(method) {
    activeMethod = method;
    await tick();
    const el = document.getElementById('paymentMethodSettingsModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).show();
    }
  }

  // Close the settings modal programmatically; the Kaydet button intentionally has no
  // data-bs-dismiss so validation/API failures keep the modal (and typed values) open.
  function closeSettingsModal() {
    const el = document.getElementById('paymentMethodSettingsModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  async function onSettingsSaved(payload) {
    if (!payload) return;

    const method = PAYMENT_METHODS.find((m) => m.id === payload.id);

    try {
      const body = await ApiUtil.post({
        path: `/api/panel/market/payment-methods/${payload.id}`,
        body: { settings: payload.settings }
      });

      if (body.error) {
        showToast($_('settings.payments.toast-save-error', { values: { name: method ? $_(method.name) : '' } }));
        return;
      }

      // Close first, then refresh so local state reflects the server's
      // masked-secret truth + enabled flags via the reloaded settings prop.
      closeSettingsModal();
      showToast($_('settings.payments.toast-save-success', { values: { name: method ? $_(method.name) : '' } }));
      await refresh();
    } catch (e) {
      showToast($_('settings.payments.toast-save-error', { values: { name: method ? $_(method.name) : '' } }));
    }
  }
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('settings.payments.method-count', { values: { count: filteredMethods.length } })}
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={searchValue}
        placeholderKey="plugins.pano-plugin-market.search.payment-methods"
        onchange={(val) => applyFilters({ search: val })} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={regionFilter === 'all'} onclick={() => applyFilters({ region: 'all' })}>
        {$_('common.all')}
      </CardFiltersItem>
      <CardFiltersItem button active={regionFilter === 'tr'} onclick={() => applyFilters({ region: 'tr' })}>
        {$_('settings.payments.filters.turkey')}
      </CardFiltersItem>
      <CardFiltersItem button active={regionFilter === 'global'} onclick={() => applyFilters({ region: 'global' })}>
        {$_('settings.payments.filters.global')}
      </CardFiltersItem>
    </CardFilters>
  </CardHeader>

  <div class="card-body">
    {#if filteredMethods.length === 0}
      <NoContent />
    {:else}
      <div class="row g-3">
        {#each filteredMethods as method (method.id)}
          {@const isEnabled = methodState[method.id]?.enabled}
          {@const configured = isConfigured(method)}
          <div class="col-md-6 col-xl-4">
            <div
              class="card h-100 position-relative focus-ring"
              role="button"
              tabindex="0"
              style="cursor: pointer;"
              title={$_('common.edit')}
              aria-label={$_('settings.payments.open-settings-aria', { values: { name: $_(method.name) } })}
              onclick={() => openSettings(method)}
              onkeydown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  openSettings(method);
                }
              }}>
              <div
                class="position-absolute top-0 end-0 m-2 d-flex align-items-center gap-2"
                role="presentation"
                onclick={(e) => e.stopPropagation()}
                onkeydown={(e) => e.stopPropagation()}>
                <div
                  class="form-check form-switch m-0"
                  title={!configured ? $_('settings.payments.fill-required-first') : null}>
                  <input
                    class="form-check-input"
                    type="checkbox"
                    role="switch"
                    id="pm-toggle-{method.id}"
                    checked={isEnabled}
                    disabled={!configured}
                    onchange={(e) => toggleEnabled(method, e)} />
                </div>
              </div>

              <div class="card-body">
                <div class="d-flex align-items-start gap-2 pe-5">
                  <div
                    class="d-flex align-items-center justify-content-center rounded flex-shrink-0 overflow-hidden"
                    style="width: 40px; height: 40px; background: {method.color}20;">
                    {#if method.logo}
                      <img
                        src={method.logo}
                        alt={$_('settings.payments.logo-alt', { values: { name: $_(method.name) } })}
                        loading="lazy"
                        style="max-width: 70%; max-height: 70%; object-fit: contain;" />
                    {:else}
                      <i class="fas {method.icon} fs-5" style="color: {method.color};"></i>
                    {/if}
                  </div>
                  <div class="flex-grow-1 min-w-0">
                    <div class="d-flex align-items-baseline gap-2 flex-wrap">
                      <h6 class="mb-0 text-break">{$_(method.name)}</h6>
                      {#if method.region === 'tr'}
                        <span class="badge text-bg-primary">TR</span>
                      {/if}
                    </div>
                    <p class="small text-body-secondary mb-0">{$_(method.description)}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        {/each}
      </div>
    {/if}
  </div>
</div>

<PaymentMethodSettingsModal
  method={activeMethod}
  settingsState={activeMethod ? methodState[activeMethod.id] : null}
  onsave={onSettingsSaved} />
