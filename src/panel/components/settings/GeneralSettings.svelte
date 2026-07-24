<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { showToast } from '@panomc/sdk/toasts';
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';

  let { settings: initialSettings = {} } = $props();

  // Local writable copy of the loaded settings: re-derived if the page load()
  // re-runs, reassigned by the client-side refresh() after a save (navigating
  // would remount the whole plugin page and drop the active settings tab).
  let settings = $derived(initialSettings);

  // Writable deriveds: seeded from the loaded settings and editable via bind:value;
  // they re-sync to server truth whenever the settings state changes
  // (e.g. after the refresh() following a save).
  let storeName = $derived(settings.storeName ?? '');
  let storeDescription = $derived(settings.storeDescription ?? '');
  let currency = $derived(settings.currency ?? 'TRY');
  let statsCurrency = $derived(settings.statsCurrency ?? settings.currency ?? 'TRY');
  let exchangeRateMode = $derived(settings.exchangeRateMode ?? 'AUTO');
  let exchangeRate = $derived(settings.exchangeRate ?? 1);
  let exchangeRateAutoIntervalHours = $derived(settings.exchangeRateAutoIntervalHours ?? 24);
  let vatPercent = $derived(settings.vatPercent ?? 20);
  let showVatInPrice = $derived(settings.showVatInPrice ?? true);
  let testMode = $derived(settings.testMode ?? false);
  let allowGuestCheckout = $derived(settings.allowGuestCheckout ?? true);
  let minimumOrderAmount = $derived(settings.minimumOrderAmount ?? 0);
  let removeCents = $derived(settings.removeCents ?? false);
  let showBestsellers = $derived(settings.showBestsellers ?? true);
  let showFeaturedProducts = $derived(settings.showFeaturedProducts ?? true);
  let showComparisons = $derived(settings.showComparisons ?? true);
  let sendEmailAfterPurchase = $derived(settings.sendEmailAfterPurchase ?? true);
  let combineDiscountsAndCoupons = $derived(settings.combineDiscountsAndCoupons ?? true);

  // Currency symbols come from the settings response (never hardcode ₺); they
  // reflect the last saved currencies until the next save + refresh().
  let salesSymbol = $derived(settings.currencySymbol ?? currency);
  let statsSymbol = $derived(settings.statsCurrencySymbol ?? statsCurrency);

  // The exchange-rate panel only makes sense when the two currencies differ.
  let needsConversion = $derived(statsCurrency !== currency);

  let saving = $state(false);
  let refreshingRate = $state(false);

  // Save is enabled only when an editable field diverges from the loaded settings.
  // Compared against the same baselines the writable deriveds seed from, so after
  // a save + refresh() the deriveds re-sync and this returns to false. The manual
  // rate only counts while in MANUAL mode (it isn't sent otherwise).
  let isDirty = $derived(
    storeName !== (settings.storeName ?? '') ||
      storeDescription !== (settings.storeDescription ?? '') ||
      currency !== (settings.currency ?? 'TRY') ||
      statsCurrency !== (settings.statsCurrency ?? settings.currency ?? 'TRY') ||
      exchangeRateMode !== (settings.exchangeRateMode ?? 'AUTO') ||
      Number(exchangeRateAutoIntervalHours) !== (settings.exchangeRateAutoIntervalHours ?? 24) ||
      Number(vatPercent) !== (settings.vatPercent ?? 20) ||
      showVatInPrice !== (settings.showVatInPrice ?? true) ||
      testMode !== (settings.testMode ?? false) ||
      allowGuestCheckout !== (settings.allowGuestCheckout ?? true) ||
      Number(minimumOrderAmount) !== (settings.minimumOrderAmount ?? 0) ||
      removeCents !== (settings.removeCents ?? false) ||
      showBestsellers !== (settings.showBestsellers ?? true) ||
      showFeaturedProducts !== (settings.showFeaturedProducts ?? true) ||
      showComparisons !== (settings.showComparisons ?? true) ||
      sendEmailAfterPurchase !== (settings.sendEmailAfterPurchase ?? true) ||
      combineDiscountsAndCoupons !== (settings.combineDiscountsAndCoupons ?? true) ||
      (exchangeRateMode === 'MANUAL' && Number(exchangeRate) !== (settings.exchangeRate ?? 1))
  );

  const currencies = $derived([
    { value: 'TRY', label: $_('settings.general.currency.try') },
    { value: 'USD', label: $_('settings.general.currency.usd') },
    { value: 'EUR', label: $_('settings.general.currency.eur') },
    { value: 'GBP', label: $_('settings.general.currency.gbp') }
  ]);

  function formatRate(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return String(value ?? '');
    // Trim trailing zeros while keeping meaningful precision.
    return number.toLocaleString(undefined, { maximumFractionDigits: 6 });
  }

  async function refresh() {
    const body = await ApiUtil.get({ path: '/api/panel/market/settings' });
    if (body && !body.error) {
      settings = body;
    }
  }

  async function handleRefreshRate() {
    refreshingRate = true;
    try {
      const body = await ApiUtil.post({
        path: '/api/panel/market/settings/exchange-rate/refresh'
      });

      // No body: demo mode (ApiUtil already toasted) or a swallowed error.
      if (!body) return;

      if (body.error) {
        if (body.error === 'EXCHANGE_RATE_FETCH_FAILED') {
          showToast($_('settings.general.exchange-rate.toast-refresh-failed'));
        } else {
          showToast($_('settings.general.exchange-rate.toast-refresh-error'));
        }
        return;
      }

      // Reload settings so the new rate + timestamp are reflected in the UI.
      await refresh();
      showToast($_('settings.general.exchange-rate.toast-refresh-success'));
    } catch (e) {
      showToast($_('settings.general.exchange-rate.toast-refresh-error'));
    } finally {
      refreshingRate = false;
    }
  }

  async function handleSave() {
    saving = true;
    try {
      const body = {
        storeName,
        storeDescription,
        currency,
        statsCurrency,
        exchangeRateMode,
        exchangeRateAutoIntervalHours: Number(exchangeRateAutoIntervalHours) || 0,
        vatPercent: Number(vatPercent) || 0,
        showVatInPrice,
        testMode,
        allowGuestCheckout,
        minimumOrderAmount: Number(minimumOrderAmount) || 0,
        removeCents,
        showBestsellers,
        showFeaturedProducts,
        showComparisons,
        sendEmailAfterPurchase,
        combineDiscountsAndCoupons
      };

      // Only send the manual rate when in MANUAL mode, so a Kaydet does not
      // clobber a freshly auto-fetched rate with a stale editor value.
      if (exchangeRateMode === 'MANUAL') {
        body.exchangeRate = Number(exchangeRate) || 0;
      }

      const result = await ApiUtil.post({
        path: '/api/panel/market/settings',
        body
      });

      if (result.error) {
        showToast($_('settings.general.toast-error'));
        return;
      }

      showToast($_('settings.general.toast-success'));
      await refresh();
    } catch (e) {
      showToast($_('settings.general.toast-error'));
    } finally {
      saving = false;
    }
  }
</script>

<div class="card">
  <div class="card-header">
    <h6 class="mb-0">{$_('settings.general.heading')}</h6>
    <small class="text-body-secondary">{$_('settings.general.subheading')}</small>
  </div>

  <div class="card-body">
    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="storeNameInput">{$_('settings.general.store-name')}</label>
      <div class="col-md-6">
        <input type="text" class="form-control" id="storeNameInput" placeholder={$_('settings.general.store-name')} bind:value={storeName} />
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="storeDescInput">{$_('settings.general.store-description')}</label>
      <div class="col-md-6">
        <textarea class="form-control" id="storeDescInput" style="height: 80px;" placeholder={$_('settings.general.store-description')} bind:value={storeDescription}></textarea>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="currencyInput">{$_('settings.general.sales-currency-label')}</label>
      <div class="col-md-6">
        <select class="form-select" id="currencyInput" bind:value={currency}>
          {#each currencies as c (c.value)}
            <option value={c.value}>{c.label}</option>
          {/each}
        </select>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="statsCurrencyInput">{$_('settings.general.stats-currency-label')}</label>
      <div class="col-md-6">
        <select class="form-select" id="statsCurrencyInput" bind:value={statsCurrency}>
          {#each currencies as c (c.value)}
            <option value={c.value}>{c.label}</option>
          {/each}
        </select>
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-12">
        {#if needsConversion}
          <div class="border rounded p-3">
            <div class="d-flex align-items-center mb-3">
              <i class="fas fa-right-left me-2 text-body-secondary"></i>
              <h6 class="mb-0">{$_('settings.general.exchange-rate.heading')}</h6>
            </div>

            <div class="row mb-3">
              <div class="col-md-6 col-form-label">{$_('settings.general.exchange-rate.current-label')}</div>
              <div class="col-md-6 d-flex align-items-center">
                <span class="fw-semibold">
                  {$_('settings.general.exchange-rate.rate-display', { values: { salesSymbol, rate: formatRate(exchangeRate), statsSymbol } })}
                </span>
              </div>
            </div>

            <div class="row mb-3">
              <label class="col-md-6 col-form-label" for="exchangeRateModeInput">{$_('settings.general.exchange-rate.mode-label')}</label>
              <div class="col-md-6">
                <select class="form-select" id="exchangeRateModeInput" bind:value={exchangeRateMode}>
                  <option value="AUTO">{$_('settings.general.exchange-rate.mode-auto')}</option>
                  <option value="MANUAL">{$_('settings.general.exchange-rate.mode-manual')}</option>
                </select>
              </div>
            </div>

            {#if exchangeRateMode === 'AUTO'}
              <div class="row mb-3">
                <label class="col-md-6 col-form-label" for="exchangeRateIntervalInput">{$_('settings.general.exchange-rate.interval-label')}</label>
                <div class="col-md-6">
                  <div class="input-group">
                    <input type="number" min="1" step="1" class="form-control" id="exchangeRateIntervalInput" bind:value={exchangeRateAutoIntervalHours} />
                    <span class="input-group-text">{$_('settings.general.exchange-rate.interval-suffix')}</span>
                  </div>
                </div>
              </div>

              <div class="row mb-3">
                <div class="col-md-6 col-form-label">{$_('settings.general.exchange-rate.last-update-label')}</div>
                <div class="col-md-6 d-flex align-items-center">
                  {#if settings.exchangeRateUpdatedAt}
                    <span class="text-body-secondary"><DateComponent time={settings.exchangeRateUpdatedAt} relativeFormat={true} /></span>
                  {:else}
                    <span class="text-body-secondary">{$_('settings.general.exchange-rate.never-updated')}</span>
                  {/if}
                </div>
              </div>

              <div class="row">
                <div class="col-md-6 offset-md-6">
                  <button type="button" class="btn btn-outline-secondary btn-sm" onclick={handleRefreshRate} disabled={refreshingRate}>
                    {#if refreshingRate}
                      <span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                      {$_('settings.general.exchange-rate.refreshing')}
                    {:else}
                      <i class="fas fa-rotate me-2"></i>
                      {$_('settings.general.exchange-rate.refresh-now')}
                    {/if}
                  </button>
                </div>
              </div>
            {:else}
              <div class="row mb-0">
                <label class="col-md-6 col-form-label" for="exchangeRateInput">{$_('settings.general.exchange-rate.manual-label')}</label>
                <div class="col-md-6">
                  <div class="input-group">
                    <span class="input-group-text">{$_('settings.general.exchange-rate.manual-prefix', { values: { salesSymbol } })}</span>
                    <input type="number" min="0" step="0.0001" class="form-control" id="exchangeRateInput" bind:value={exchangeRate} />
                    <span class="input-group-text">{statsSymbol}</span>
                  </div>
                </div>
              </div>
            {/if}
          </div>
        {:else}
          <div class="text-body-secondary small">
            <i class="fas fa-circle-info me-1"></i>{$_('settings.general.exchange-rate.no-conversion')}
          </div>
        {/if}
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="vatInput">{$_('settings.general.vat-label')}</label>
      <div class="col-md-6">
        <input type="number" min="0" max="100" step="0.5" class="form-control" id="vatInput" placeholder={$_('settings.general.vat-placeholder')} bind:value={vatPercent} />
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="minOrderInput">{$_('settings.general.min-order-amount')}</label>
      <div class="col-md-6">
        <input type="number" min="0" step="1" class="form-control" id="minOrderInput" placeholder={$_('settings.general.min-order-amount')} bind:value={minimumOrderAmount} />
      </div>
    </div>

    <hr class="my-4" />

    <div class="row mb-3">
      <label class="col-md-6" for="vatInPriceInput">{$_('settings.general.show-vat-in-price')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="vatInPriceInput" bind:checked={showVatInPrice} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="guestCheckoutInput">{$_('settings.general.allow-guest-checkout')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="guestCheckoutInput" bind:checked={allowGuestCheckout} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="testModeInput">{$_('settings.general.test-mode')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="testModeInput" bind:checked={testMode} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="removeCentsInput">{$_('settings.general.remove-cents')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="removeCentsInput" bind:checked={removeCents} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="showBestsellersInput">{$_('settings.general.show-bestsellers')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="showBestsellersInput" bind:checked={showBestsellers} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="showFeaturedProductsInput">{$_('settings.general.show-featured-products')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="showFeaturedProductsInput" bind:checked={showFeaturedProducts} />
        </div>
      </div>
    </div>

    
    <div class="row mb-3">
      <label class="col-md-6" for="showComparisonsInput">{$_('settings.general.show-comparisons')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="showComparisonsInput" bind:checked={showComparisons} />
        </div>
      </div>
    </div>
<div class="row mb-3">
      <label class="col-md-6" for="sendEmailAfterPurchaseInput">{$_('settings.general.send-email-after-purchase')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="sendEmailAfterPurchaseInput" bind:checked={sendEmailAfterPurchase} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="combineDiscountsAndCouponsInput">{$_('settings.general.combine-discounts-and-coupons')}</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="combineDiscountsAndCouponsInput" bind:checked={combineDiscountsAndCoupons} />
        </div>
      </div>
    </div>
  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary" onclick={handleSave} disabled={saving || !isDirty}>
      {#if saving}
        <span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
      {/if}
      {$_('common.save')}
    </button>
  </div>
</div>
