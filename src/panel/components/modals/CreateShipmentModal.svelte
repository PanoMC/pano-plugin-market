<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.create-shipment.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>

      {#if loading}
        <div class="modal-body text-center py-5">
          <span class="spinner-border" aria-hidden="true"></span>
          <span class="visually-hidden">{$_('common.loading')}</span>
        </div>
      {:else if loadError}
        <div class="modal-body">
          <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
            <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
            <div>
              <b>{$_('common.load-error-title')}</b>
              <div>{$_(errorKey(loadError))}</div>
            </div>
          </div>
        </div>
      {:else if shipping}
        <form onsubmit={submit}>
          <div class="modal-body vstack gap-3">
            {#if shipping.quoteExpired}
              <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
                <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
                <div><b>{$_('modals.create-shipment.quote-expired')}</b></div>
              </div>
            {/if}

            <div class="vstack gap-2" class:border-danger={invalid.items}>
              <b>{$_('modals.create-shipment.lines')}</b>
              {#each lines as line (line.orderItemId)}
                <div class="row g-2 align-items-center">
                  <div class="col">
                    {line.name}
                    {#if line.variantName}
                      <span class="text-body-secondary">· {line.variantName}</span>
                    {/if}
                    {#if line.sku}<span class="text-body-secondary">· {line.sku}</span>{/if}
                  </div>
                  <div class="col-auto" style="width: 150px;">
                    <div class="input-group">
                      <input
                        class="form-control"
                        class:is-invalid={invalid.items}
                        type="number"
                        min="0"
                        max={line.remaining}
                        step="1"
                        inputmode="numeric"
                        aria-label={$_('modals.create-shipment.quantity')}
                        bind:value={line.quantity}
                        onchange={() => onLineChange(line)} />
                      <span class="input-group-text">/ {line.remaining}</span>
                    </div>
                  </div>
                </div>
              {/each}
              {#if lines.length === 0}
                <div class="text-body-secondary">{$_('modals.create-shipment.nothing-left')}</div>
              {/if}
            </div>

            <div class="vstack gap-2">
              <b>{$_('modals.create-shipment.parcels')}</b>
              {#each parcels as parcel, index (index)}
                {@const problem = Array.isArray(invalid.parcels)
                  ? (invalid.parcels[index] ?? {})
                  : {}}
                <div class="row g-2 align-items-center">
                  <div class="col-6 col-md">
                    <input
                      class="form-control"
                      class:is-invalid={problem.weightGrams}
                      type="text"
                      inputmode="numeric"
                      placeholder={$_('modals.create-shipment.weight')}
                      aria-label={$_('modals.create-shipment.weight')}
                      oninput={clearRates}
                      bind:value={parcel.weightGrams} />
                  </div>
                  {#each ['lengthMm', 'widthMm', 'heightMm'] as dim (dim)}
                    <div class="col-6 col-md">
                      <input
                        class="form-control"
                        class:is-invalid={problem.dimensions}
                        type="text"
                        inputmode="numeric"
                        placeholder={$_(`modals.create-shipment.${dim}`)}
                        aria-label={$_(`modals.create-shipment.${dim}`)}
                        oninput={clearRates}
                        bind:value={parcel[dim]} />
                    </div>
                  {/each}
                  <div class="col-auto">
                    <button
                      type="button"
                      class="btn btn-link link-danger"
                      disabled={parcels.length <= 1}
                      title={$_('common.remove')}
                      aria-label={$_('common.remove')}
                      onclick={() => removeParcel(index)}>
                      <i class="fa-solid fa-trash" aria-hidden="true"></i>
                    </button>
                  </div>
                </div>
              {/each}
              {#if invalid.parcels === 'REQUIRED' || invalid.parcels === 'TOO_MANY'}
                <div class="text-danger small">
                  {$_(`modals.create-shipment.errors.parcels-${invalid.parcels}`)}
                </div>
              {/if}
              <div>
                <button type="button" class="btn btn-sm btn-link px-0" onclick={addParcel}>
                  <i class="fa-solid fa-plus me-1" aria-hidden="true"></i>
                  {$_('modals.create-shipment.add-parcel')}
                </button>
              </div>
            </div>

            <div>
              <label class="form-label" for="create-shipment-provider">
                {$_('modals.create-shipment.provider')}
              </label>
              <select
                id="create-shipment-provider"
                class="form-select"
                value={providerId}
                onchange={(event) => changeProvider(event.currentTarget.value)}>
                {#each choices as choice (choice.id)}
                  <option value={choice.id}>
                    {choice.id === MANUAL_PROVIDER
                      ? $_('modals.create-shipment.manual')
                      : choice.name}
                  </option>
                {/each}
              </select>
              {#if provider?.balance !== undefined && provider?.balance !== null}
                <div class="form-text">
                  {$_('modals.create-shipment.balance', {
                    values: { balance: formatBalance(provider.balance) },
                  })}
                </div>
              {/if}
            </div>

            {#if isManual(providerId)}
              <input
                class="form-control"
                class:is-invalid={invalid.carrierName}
                type="text"
                maxlength={CARRIER_MAX}
                placeholder={$_('modals.create-shipment.carrier-name')}
                aria-label={$_('modals.create-shipment.carrier-name')}
                bind:value={manual.carrierName} />
              <input
                class="form-control"
                class:is-invalid={invalid.trackingNumber}
                type="text"
                maxlength={TRACKING_MAX}
                placeholder={$_('modals.create-shipment.tracking-number')}
                aria-label={$_('modals.create-shipment.tracking-number')}
                bind:value={manual.trackingNumber} />
              <input
                class="form-control"
                class:is-invalid={invalid.trackingUrl}
                type="url"
                maxlength={URL_MAX}
                placeholder={$_('modals.create-shipment.tracking-url')}
                aria-label={$_('modals.create-shipment.tracking-url')}
                bind:value={manual.trackingUrl} />
            {:else}
              {#if (provider?.services ?? []).length > 0}
                <select
                  class="form-select"
                  aria-label={$_('modals.create-shipment.service')}
                  bind:value={serviceCode}
                  onchange={clearRates}>
                  <option value="">{$_('modals.create-shipment.service-any')}</option>
                  {#each provider.services as service (service.code)}
                    <option value={service.code}>{service.name}</option>
                  {/each}
                </select>
              {/if}

              {#if offersRates(providerId, provider)}
                <div>
                  <button
                    type="button"
                    class="btn btn-outline-secondary"
                    disabled={ratesLoading}
                    onclick={getRates}>
                    {#if ratesLoading}
                      <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                    {/if}
                    {$_('modals.create-shipment.get-rates')}
                  </button>
                </div>
                {#if rates.length > 0}
                  <div class="vstack gap-1" class:border-danger={invalid.rate}>
                    {#each rates as rate (rateKey(rate))}
                      <div class="form-check">
                        <input
                          class="form-check-input"
                          type="radio"
                          name="create-shipment-rate"
                          id="create-shipment-rate-{rateKey(rate)}"
                          value={rateKey(rate)}
                          bind:group={chosenRateKey} />
                        <label class="form-check-label" for="create-shipment-rate-{rateKey(rate)}">
                          {rate.carrierName ?? ''}
                          {rate.serviceName ?? rate.serviceCode}
                          · {fmt.money(rate.price, rate.currency)}
                          {#if rate.minDays != null || rate.maxDays != null}
                            <span class="text-body-secondary">
                              · {$_('modals.create-shipment.days', {
                                values: { range: dayRange(rate) },
                              })}
                            </span>
                          {/if}
                        </label>
                      </div>
                    {/each}
                  </div>
                {:else if ratesFetched && !ratesFailed}
                  <div class="text-body-secondary">{$_('modals.create-shipment.no-rates')}</div>
                {/if}
                {#if invalid.rate}
                  <div class="text-danger small">{$_('modals.create-shipment.errors.rate')}</div>
                {/if}
              {/if}
            {/if}

            <textarea
              class="form-control"
              class:is-invalid={invalid.note}
              rows="2"
              maxlength={NOTE_MAX}
              placeholder={$_('modals.create-shipment.note')}
              aria-label={$_('modals.create-shipment.note')}
              bind:value={note}></textarea>

            {#if fieldProblems.length > 0}
              <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
                <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
                <div>
                  {#each fieldProblems as text (text)}
                    <div>{text}</div>
                  {/each}
                </div>
              </div>
            {/if}
          </div>
          <div class="modal-footer">
            <button
              class="btn btn-primary w-100"
              type="submit"
              disabled={saving || lines.length === 0}>
              {#if saving}
                <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
              {/if}
              {$_('modals.create-shipment.cta')}
            </button>
          </div>
        </form>
      {/if}
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showErrorToast } from '../../../i18n';
  import { isStaleError } from '../order-detail/actions.js';
  import { fetchPath, hideModal, showModal } from '../order-detail/send.js';
  import { call, errorKey, marketPath } from '../../utils/api.js';
  import { fmt } from '../../utils/locale.js';
  import {
    CARRIER_MAX,
    MANUAL_PROVIDER,
    NOTE_MAX,
    TRACKING_MAX,
    URL_MAX,
    MAX_PARCELS,
    clampQuantity,
    createRequest,
    defaultProviderId,
    defaultServiceCode,
    emptyParcel,
    failureText,
    initialLines,
    initialParcels,
    isManual,
    offersRates,
    preselectRate,
    providerChoices,
    rateKey,
    ratesRequest,
    shipmentFieldErrors,
    validateParcels,
  } from '../../utils/shipments.js';

  // detail: GET /orders/:id (the order id comes from it). onDone(toastKey) refreshes the order and
  // toasts; onStale() refreshes after a stale failure (ORDER_NOT_SHIPPABLE, ...). `ctx` and `user`
  // belong to the shared external-modal contract (order-detail/external.js) and are not needed here.
  let { detail = null, onDone = async () => {}, onStale = async () => {} } = $props();

  let modalElement = $state(null);
  let loading = $state(false);
  let loadError = $state(null);
  let saving = $state(false);
  let shipping = $state(null);
  let lines = $state([]);
  let parcels = $state([emptyParcel()]);
  let providerId = $state(MANUAL_PROVIDER);
  let serviceCode = $state('');
  let manual = $state({ carrierName: '', trackingNumber: '', trackingUrl: '' });
  let note = $state('');
  let rates = $state([]);
  let ratesFetched = $state(false);
  let ratesFailed = $state(false);
  let ratesLoading = $state(false);
  let chosenRateKey = $state('');
  let invalid = $state({});
  let serverFieldErrors = $state({});

  const orderId = $derived(detail?.order?.id);
  const choices = $derived(providerChoices(shipping?.providers));
  const provider = $derived((shipping?.providers ?? []).find((p) => p.id === providerId) ?? null);
  const chosenRate = $derived(rates.find((r) => rateKey(r) === chosenRateKey) ?? null);
  const fieldProblems = $derived(
    Object.entries(serverFieldErrors).map(([field, code]) => {
      const key = `modals.create-shipment.errors.${field}-${code}`;
      const text = $_(key);
      return text === key || text === `plugins.pano-plugin-market.${key}`
        ? $_('errors.INVALID_SHIPMENT')
        : text;
    }),
  );

  const dayRange = (rate) =>
    rate.minDays != null && rate.maxDays != null && rate.minDays !== rate.maxDays
      ? `${rate.minDays}–${rate.maxDays}`
      : String(rate.maxDays ?? rate.minDays);

  const formatBalance = (balance) =>
    typeof balance === 'object' && balance !== null
      ? fmt.money(balance.amount, balance.currency)
      : String(balance);

  function clearRates() {
    rates = [];
    ratesFetched = false;
    ratesFailed = false;
    chosenRateKey = '';
  }

  // The typed quantity is bounded by the unshipped remainder as soon as the field loses focus.
  function onLineChange(line) {
    line.quantity = String(clampQuantity(line.quantity, line.remaining));
    clearRates();
  }

  function addParcel() {
    if (parcels.length >= MAX_PARCELS) return;
    parcels.push(emptyParcel());
    clearRates();
  }

  function removeParcel(index) {
    if (parcels.length <= 1) return;
    parcels.splice(index, 1);
    clearRates();
  }

  function changeProvider(id) {
    providerId = id;
    serviceCode = defaultServiceCode(
      shipping?.quote,
      choices.find((p) => p.id === id),
    );
    clearRates();
  }

  export async function open() {
    shipping = null;
    loadError = null;
    invalid = {};
    serverFieldErrors = {};
    saving = false;
    note = '';
    manual = { carrierName: '', trackingNumber: '', trackingUrl: '' };
    clearRates();
    showModal(modalElement);
    if (!orderId) return;
    loading = true;
    try {
      const result = await fetchPath(marketPath(`/orders/${orderId}/shipping`));
      if (!result.ok) {
        loadError = result.error;
        return;
      }
      const body = result.body;
      shipping = body;
      lines = initialLines(body.lines);
      parcels = initialParcels(body.suggestedParcels);
      providerId = defaultProviderId(body.quote, body.providers);
      serviceCode = defaultServiceCode(
        body.quote,
        (body.providers ?? []).find((p) => p.id === providerId),
      );
    } finally {
      loading = false;
    }
  }

  async function getRates() {
    if (ratesLoading || !orderId) return;
    const checked = validateParcels(parcels, { maxParcels: provider?.capabilities?.maxParcels });
    invalid = checked.error ?? {};
    if (checked.error) return;
    ratesLoading = true;
    ratesFailed = false;
    try {
      const request = ratesRequest(orderId, { providerId, parcels: checked.parcels, serviceCode });
      const result = await call(ApiUtil.post({ path: request.path, body: request.body }));
      if (!result.ok) {
        ratesFailed = true;
        rates = [];
        showErrorToast(failureText($_, result.error, result.body));
        return;
      }
      rates = Array.isArray(result.body.rates) ? result.body.rates : [];
      ratesFetched = true;
      const pre = preselectRate(rates, shipping?.quote);
      chosenRateKey = pre ? rateKey(pre) : '';
    } finally {
      ratesLoading = false;
    }
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !orderId) return;
    serverFieldErrors = {};
    const built = createRequest(orderId, {
      lines,
      parcels,
      providerId,
      provider,
      serviceCode,
      rate: chosenRate,
      rates,
      ratesFetched,
      ratesFailed,
      manual,
      note,
    });
    invalid = built.error ?? {};
    if (built.error) return;
    saving = true;
    try {
      const result = await call(
        ApiUtil.post({ path: built.request.path, body: built.request.body }),
      );
      if (result.ok) {
        hideModal(modalElement);
        await onDone('modals.create-shipment.toast');
        return;
      }
      showErrorToast(failureText($_, result.error, result.body));
      if (result.error === 'INVALID_SHIPMENT') {
        serverFieldErrors = shipmentFieldErrors(result.body);
        return;
      }
      if (result.error === 'SHIPPING_PROVIDER_ERROR') {
        // The row exists in CREATED and offers Retry: close and refresh (13 §20.2 step 6).
        hideModal(modalElement);
        await onDone();
        return;
      }
      if (isStaleError(result.error)) {
        hideModal(modalElement);
        await onStale();
      }
    } finally {
      saving = false;
    }
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
