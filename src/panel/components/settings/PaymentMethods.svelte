<script>
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput } from '@panomc/sdk/components/panel';
  import { showToast } from '@panomc/sdk/toasts';
  import { PAYMENT_METHODS, createDefaultMethodState } from '../../data/payment-methods.js';
  import PaymentMethodSettingsModal from '../modals/PaymentMethodSettingsModal.svelte';

  let methodState = $state(createDefaultMethodState());
  let activeMethod = $state(null);
  let regionFilter = $state('all'); // all | tr | global
  let search = $state('');

  const filteredMethods = $derived.by(() => {
    const term = search.trim().toLowerCase();
    return PAYMENT_METHODS.filter((m) => {
      if (regionFilter !== 'all' && m.region !== regionFilter) return false;
      if (!term) return true;
      return (
        m.name.toLowerCase().includes(term) ||
        m.description.toLowerCase().includes(term) ||
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

  function toggleEnabled(method) {
    if (!methodState[method.id]) return;

    if (!methodState[method.id].enabled && !isConfigured(method)) {
      showToast(`${method.name} için zorunlu ayarları doldurun.`);
      activeMethod = method;
      return;
    }

    methodState[method.id].enabled = !methodState[method.id].enabled;
    showToast(
      methodState[method.id].enabled
        ? `${method.name} aktifleştirildi.`
        : `${method.name} pasifleştirildi.`
    );
  }

  function openSettings(method) {
    activeMethod = method;
  }

  function onSettingsSaved(payload) {
    if (!payload) return;
    methodState[payload.id].settings = payload.settings;
    showToast(`${PAYMENT_METHODS.find((m) => m.id === payload.id)?.name} ayarları kaydedildi.`);
  }
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {filteredMethods.length} Ödeme Yöntemi
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        placeholder="Yöntem ara..."
        onchange={(val) => (search = val)} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={regionFilter === 'all'} onclick={() => (regionFilter = 'all')}>
        Tümü
      </CardFiltersItem>
      <CardFiltersItem button active={regionFilter === 'tr'} onclick={() => (regionFilter = 'tr')}>
        Türkiye
      </CardFiltersItem>
      <CardFiltersItem button active={regionFilter === 'global'} onclick={() => (regionFilter = 'global')}>
        Global
      </CardFiltersItem>
    </CardFilters>
  </CardHeader>

  <div class="card-body">
    {#if filteredMethods.length === 0}
      <div class="text-center text-body-secondary py-5">
        <i class="fas fa-circle-info mb-2 fs-3"></i>
        <div>Filtreye uygun ödeme yöntemi bulunamadı.</div>
      </div>
    {:else}
      <div class="row g-3">
        {#each filteredMethods as method (method.id)}
          {@const isEnabled = methodState[method.id]?.enabled}
          {@const configured = isConfigured(method)}
          <div class="col-md-6 col-xl-4">
            <div
              class="card h-100 position-relative"
              role="button"
              tabindex="0"
              style="cursor: pointer;"
              aria-label="{method.name} ayarlarını aç"
              data-bs-toggle="modal"
              data-bs-target="#paymentMethodSettingsModal"
              onclick={() => openSettings(method)}
              onkeydown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  openSettings(method);
                  e.currentTarget.click();
                }
              }}>
              <div
                class="position-absolute top-0 end-0 m-2 d-flex align-items-center gap-2"
                role="presentation"
                onclick={(e) => e.stopPropagation()}
                onkeydown={(e) => e.stopPropagation()}>
                <div
                  class="form-check form-switch m-0"
                  title={!configured ? 'Önce zorunlu ayarları doldurun' : null}>
                  <input
                    class="form-check-input"
                    type="checkbox"
                    role="switch"
                    id="pm-toggle-{method.id}"
                    checked={isEnabled}
                    disabled={!configured}
                    onchange={() => toggleEnabled(method)} />
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
                        alt="{method.name} logosu"
                        loading="lazy"
                        style="max-width: 70%; max-height: 70%; object-fit: contain;" />
                    {:else}
                      <i class="fas {method.icon} fs-5" style="color: {method.color};"></i>
                    {/if}
                  </div>
                  <div class="flex-grow-1 min-w-0">
                    <div class="d-flex align-items-baseline gap-2 flex-wrap">
                      <h6 class="mb-0 text-break">{method.name}</h6>
                      {#if method.region === 'tr'}
                        <span class="badge text-bg-primary">TR</span>
                      {/if}
                    </div>
                    <p class="small text-body-secondary mb-0">{method.description}</p>
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
