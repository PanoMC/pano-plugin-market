<MarketLayout area="settings">
  {#snippet left()}
    <a class="btn btn-link px-0" href="{base}/market/settings?section=shipping-methods">
      <i class="fa-solid fa-arrow-left me-1" aria-hidden="true"></i>
      {$_('pages.shipping-method.back')}
    </a>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <form onsubmit={submit} novalidate>
      {#if lossy}
        <div class="alert alert-warning d-flex align-items-start" role="alert">
          <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
          <div>{$_('pages.shipping-method.mixed-basis')}</div>
        </div>
      {/if}
      {#if zones.length === 0}
        <div class="alert alert-warning d-flex align-items-start" role="alert">
          <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
          <div>
            {$_('pages.shipping-method.no-zones')}
            <a class="alert-link" href="{base}/market/settings?section=shipping-zones">
              {$_('pages.shipping-method.open-zones')}
            </a>
          </div>
        </div>
      {/if}

      <div class="row g-3">
        <div class="col-lg-8">
          <div class="vstack gap-3">
            <div class="card">
              <CardHeader>
                <div slot="left">{$_('pages.shipping-method.general')}</div>
              </CardHeader>
              <div class="card-body vstack gap-3">
                <div>
                  <div class="form-floating">
                    <input
                      id="shippingMethodName"
                      type="text"
                      class="form-control"
                      class:is-invalid={shown.name}
                      autocomplete="off"
                      placeholder={$_('pages.shipping-method.name')}
                      bind:value={form.name} />
                    <label for="shippingMethodName">{$_('pages.shipping-method.name')}</label>
                  </div>
                  {@render fieldError(shown.name)}
                </div>

                <div>
                  <div class="form-floating">
                    <textarea
                      id="shippingMethodDescription"
                      class="form-control"
                      class:is-invalid={shown.description}
                      style="height: 6rem;"
                      placeholder={$_('pages.shipping-method.description')}
                      bind:value={form.description}></textarea>
                    <label for="shippingMethodDescription"
                      >{$_('pages.shipping-method.description')}</label>
                  </div>
                  {@render fieldError(shown.description)}
                </div>

                <div class="row g-3">
                  <div class="col-md-6">
                    <div class="form-floating">
                      <select
                        id="shippingMethodProvider"
                        class="form-select"
                        class:is-invalid={shown.providerId}
                        value={form.providerId}
                        onchange={(e) => chooseProvider(e.currentTarget.value)}>
                        {#each providers as option (option.id)}
                          <option value={option.id}>
                            {option.name}{option.enabled
                              ? ''
                              : ` (${$_('pages.shipping-method.provider-off')})`}
                          </option>
                        {/each}
                      </select>
                      <label for="shippingMethodProvider"
                        >{$_('pages.shipping-method.provider')}</label>
                    </div>
                    {@render fieldError(shown.providerId)}
                  </div>

                  {#if !manual}
                    <div class="col-md-6">
                      <div class="form-floating">
                        {#if servicesState.status === 'failed'}
                          <input
                            id="shippingMethodService"
                            type="text"
                            class="form-control"
                            class:is-invalid={shown.serviceCode}
                            autocomplete="off"
                            placeholder={$_('pages.shipping-method.service-code')}
                            bind:value={form.serviceCode} />
                          <label for="shippingMethodService"
                            >{$_('pages.shipping-method.service-code')}</label>
                        {:else}
                          <select
                            id="shippingMethodService"
                            class="form-select"
                            class:is-invalid={shown.serviceCode}
                            disabled={servicesState.status === 'loading'}
                            bind:value={form.serviceCode}>
                            <option value="">{$_('pages.shipping-method.service-any')}</option>
                            {#each serviceOptions as service (service.code)}
                              <option value={service.code}>{service.name}</option>
                            {/each}
                          </select>
                          <label for="shippingMethodService"
                            >{$_('pages.shipping-method.service')}</label>
                        {/if}
                      </div>
                      {@render fieldError(shown.serviceCode)}
                    </div>
                  {/if}
                </div>
              </div>
            </div>

            <div class="card">
              <CardHeader>
                <div slot="left">{$_('pages.shipping-method.pricing')}</div>
              </CardHeader>
              <div class="card-body vstack gap-3">
                <div>
                  <div class="mb-1">{$_('pages.shipping-method.rate-source')}</div>
                  <div
                    class="btn-group flex-wrap"
                    role="group"
                    aria-label={$_('pages.shipping-method.rate-source')}>
                    {#each RATE_SOURCES as source (source)}
                      {@const allowed = sourceAllowed(source)}
                      <input
                        type="radio"
                        class="btn-check"
                        name="shippingMethodRateSource"
                        id="shippingMethodRateSource-{source}"
                        autocomplete="off"
                        checked={form.rateSource === source}
                        disabled={!allowed}
                        onchange={() => setRateSource(source)} />
                      {#if allowed}
                        <label
                          class="btn btn-outline-secondary"
                          for="shippingMethodRateSource-{source}">
                          {$_(`enums.shipping-rate-source.${source}`)}
                        </label>
                      {:else}
                        <label
                          class="btn btn-outline-secondary disabled"
                          for="shippingMethodRateSource-{source}"
                          use:tooltip={[$_('pages.shipping-method.rate-source-needs-quote')]}>
                          {$_(`enums.shipping-rate-source.${source}`)}
                        </label>
                      {/if}
                    {/each}
                  </div>
                  {@render fieldError(shown.rateSource)}
                </div>

                <div class="row g-3">
                  <div class="col-md-6">
                    <label class="form-label" for="shippingMethodFree">
                      {$_('pages.shipping-method.free-threshold')}
                    </label>
                    <MoneyInput
                      id="shippingMethodFree"
                      {currency}
                      {exponent}
                      invalid={!!shown.freeShippingThreshold}
                      bind:value={form.freeShippingThreshold} />
                    {@render fieldError(shown.freeShippingThreshold)}
                  </div>
                  <div class="col-md-6">
                    <label class="form-label" for="shippingMethodFee">
                      {$_('pages.shipping-method.handling-fee')}
                    </label>
                    <MoneyInput
                      id="shippingMethodFee"
                      {currency}
                      {exponent}
                      invalid={!!shown.handlingFee}
                      bind:value={form.handlingFee} />
                    {@render fieldError(shown.handlingFee)}
                  </div>
                </div>

                <div>
                  <div class="form-check form-switch">
                    <input
                      id="shippingMethodVatDefault"
                      class="form-check-input"
                      type="checkbox"
                      role="switch"
                      bind:checked={form.vatDefault} />
                    <label class="form-check-label" for="shippingMethodVatDefault">
                      {$_('pages.shipping-method.vat-default')}
                    </label>
                  </div>
                  {#if !form.vatDefault}
                    <div class="mt-2 col-md-6">
                      <MoneyInput
                        id="shippingMethodVat"
                        currency="%"
                        invalid={!!shown.vatPercent}
                        bind:value={form.vatPercent} />
                      {@render fieldError(shown.vatPercent)}
                    </div>
                  {/if}
                </div>
              </div>
            </div>

            <div class="card">
              <CardHeader>
                <div slot="left">{$_('pages.shipping-method.delivery')}</div>
              </CardHeader>
              <div class="card-body">
                <div class="row g-3">
                  <div class="col-md-4">
                    <div class="form-floating">
                      <input
                        id="shippingMethodMinDays"
                        type="text"
                        inputmode="numeric"
                        class="form-control"
                        class:is-invalid={shown.minDeliveryDays}
                        autocomplete="off"
                        placeholder={$_('pages.shipping-method.min-days')}
                        bind:value={form.minDeliveryDays} />
                      <label for="shippingMethodMinDays"
                        >{$_('pages.shipping-method.min-days')}</label>
                    </div>
                    {@render fieldError(shown.minDeliveryDays)}
                  </div>
                  <div class="col-md-4">
                    <div class="form-floating">
                      <input
                        id="shippingMethodMaxDays"
                        type="text"
                        inputmode="numeric"
                        class="form-control"
                        class:is-invalid={shown.maxDeliveryDays}
                        autocomplete="off"
                        placeholder={$_('pages.shipping-method.max-days')}
                        bind:value={form.maxDeliveryDays} />
                      <label for="shippingMethodMaxDays"
                        >{$_('pages.shipping-method.max-days')}</label>
                    </div>
                    {@render fieldError(shown.maxDeliveryDays)}
                  </div>
                  <div class="col-md-4">
                    <div class="form-floating">
                      <input
                        id="shippingMethodMaxWeight"
                        type="text"
                        inputmode="numeric"
                        class="form-control"
                        class:is-invalid={shown.maxWeightGrams}
                        autocomplete="off"
                        placeholder={$_('pages.shipping-method.max-weight')}
                        bind:value={form.maxWeightGrams} />
                      <label for="shippingMethodMaxWeight"
                        >{$_('pages.shipping-method.max-weight')}</label>
                    </div>
                    {@render fieldError(shown.maxWeightGrams)}
                  </div>
                </div>
              </div>
            </div>

            {#if manual}
              <div class="card">
                <CardHeader>
                  <div slot="left">{$_('pages.shipping-method.tracking')}</div>
                </CardHeader>
                <div class="card-body vstack gap-3">
                  <div>
                    <div class="form-floating">
                      <input
                        id="shippingMethodCarrierName"
                        type="text"
                        class="form-control"
                        class:is-invalid={shown.carrierName}
                        autocomplete="off"
                        placeholder={$_('pages.shipping-method.carrier-name')}
                        bind:value={form.carrierName} />
                      <label for="shippingMethodCarrierName"
                        >{$_('pages.shipping-method.carrier-name')}</label>
                    </div>
                    {@render fieldError(shown.carrierName)}
                  </div>
                  <div>
                    <div class="form-floating">
                      <input
                        id="shippingMethodTemplate"
                        type="text"
                        inputmode="url"
                        class="form-control"
                        class:is-invalid={shown.trackingUrlTemplate}
                        autocomplete="off"
                        placeholder={$_('pages.shipping-method.tracking-template')}
                        bind:value={form.trackingUrlTemplate} />
                      <label for="shippingMethodTemplate">
                        {$_('pages.shipping-method.tracking-template')}
                      </label>
                    </div>
                    <div class="form-text">
                      {$_('pages.shipping-method.tracking-template-hint', {
                        values: { token: TRACKING_TOKEN },
                      })}
                    </div>
                    {@render fieldError(shown.trackingUrlTemplate)}
                  </div>
                </div>
              </div>
            {/if}

            <div class="card">
              <CardHeader>
                <div slot="left">{$_('pages.shipping-method.rates')}</div>
              </CardHeader>
              <div class="card-body vstack gap-3">
                {#if form.rateSource === 'CARRIER'}
                  <div>
                    <div class="mb-1">{$_('pages.shipping-method.carrier-zones')}</div>
                    {#each zones as zone (zone.id)}
                      <div class="form-check">
                        <input
                          id="shippingMethodZone-{zone.id}"
                          class="form-check-input"
                          type="checkbox"
                          checked={blocks.some((b) => b.zoneId === zone.id)}
                          onchange={(e) => toggleCarrierZone(zone.id, e.currentTarget.checked)} />
                        <label class="form-check-label" for="shippingMethodZone-{zone.id}">
                          {zone.name}
                        </label>
                      </div>
                    {/each}
                  </div>
                {:else}
                  {#each blocks as block (block.zoneId)}
                    {@const result = check.byZone[block.zoneId]}
                    {@const cells = submitted ? rateErrorMap(result) : {}}
                    <div
                      class="border rounded p-3"
                      class:border-danger={submitted && result && !result.ok}>
                      <div class="row g-2 align-items-center mb-3">
                        <div class="col fw-semibold text-break">{zoneName(block.zoneId)}</div>
                        <div class="col-12 col-md-4">
                          <select
                            class="form-select"
                            aria-label={$_('pages.shipping-method.basis')}
                            value={block.basis}
                            onchange={(e) => setBasis(block.zoneId, e.currentTarget.value)}>
                            {#each RATE_BASES as basis (basis)}
                              <option value={basis}>{$_(`enums.rate-basis.${basis}`)}</option>
                            {/each}
                          </select>
                        </div>
                        <div class="col-auto">
                          <button
                            type="button"
                            class="btn btn-outline-danger"
                            title={$_('pages.shipping-method.remove-zone')}
                            aria-label={$_('pages.shipping-method.remove-zone')}
                            onclick={() => removeBlock(block.zoneId)}>
                            <i class="fa-solid fa-trash" aria-hidden="true"></i>
                          </button>
                        </div>
                      </div>

                      {#if block.basis === 'FLAT'}
                        <label class="form-label" for="rate-{block.zoneId}-0-price">
                          {$_('pages.shipping-method.price')}
                        </label>
                        <div class="col-md-6">
                          <MoneyInput
                            id="rate-{block.zoneId}-0-price"
                            {currency}
                            {exponent}
                            invalid={!!cells['0.price']}
                            bind:value={block.rows[0].price} />
                        </div>
                      {:else}
                        <div class="table-responsive">
                          <table class="table table-sm align-middle mb-2">
                            <thead>
                              <tr>
                                <th class="text-nowrap" scope="col"
                                  >{$_('pages.shipping-method.from')}</th>
                                <th class="text-nowrap" scope="col"
                                  >{$_('pages.shipping-method.to')}</th>
                                <th class="text-nowrap" scope="col"
                                  >{$_('pages.shipping-method.price')}</th>
                                {#if basisHasPerUnit(block.basis)}
                                  <th class="text-nowrap" scope="col">
                                    {block.basis === 'WEIGHT'
                                      ? $_('pages.shipping-method.per-kg')
                                      : $_('pages.shipping-method.per-item')}
                                  </th>
                                {/if}
                                <th scope="col"></th>
                              </tr>
                            </thead>
                            <tbody>
                              {#each block.rows as row, index (index)}
                                <tr>
                                  <td style="min-width: 9rem;">
                                    <MoneyInput
                                      id="rate-{block.zoneId}-{index}-from"
                                      currency={rangeUnit(block.basis)}
                                      exponent={rangeExponent(block.basis)}
                                      invalid={!!cells[`${index}.rangeFrom`]}
                                      bind:value={row.rangeFrom} />
                                  </td>
                                  <td style="min-width: 9rem;">
                                    <MoneyInput
                                      id="rate-{block.zoneId}-{index}-to"
                                      currency={rangeUnit(block.basis)}
                                      exponent={rangeExponent(block.basis)}
                                      placeholder={$_('pages.shipping-method.no-limit')}
                                      invalid={!!cells[`${index}.rangeTo`]}
                                      bind:value={row.rangeTo} />
                                  </td>
                                  <td style="min-width: 9rem;">
                                    <MoneyInput
                                      id="rate-{block.zoneId}-{index}-price"
                                      {currency}
                                      {exponent}
                                      invalid={!!cells[`${index}.price`]}
                                      bind:value={row.price} />
                                  </td>
                                  {#if basisHasPerUnit(block.basis)}
                                    <td style="min-width: 9rem;">
                                      <MoneyInput
                                        id="rate-{block.zoneId}-{index}-per-unit"
                                        {currency}
                                        {exponent}
                                        invalid={!!cells[`${index}.perUnitPrice`]}
                                        bind:value={row.perUnitPrice} />
                                    </td>
                                  {/if}
                                  <td class="text-end">
                                    <button
                                      type="button"
                                      class="btn btn-link text-danger"
                                      disabled={block.rows.length <= 1}
                                      title={$_('pages.shipping-method.remove-row')}
                                      aria-label={$_('pages.shipping-method.remove-row')}
                                      onclick={() => removeRow(block, index)}>
                                      <i class="fa-solid fa-xmark" aria-hidden="true"></i>
                                    </button>
                                  </td>
                                </tr>
                              {/each}
                            </tbody>
                          </table>
                        </div>
                        <button
                          type="button"
                          class="btn btn-outline-secondary btn-sm"
                          disabled={rowTotal >= MAX_RATE_ROWS}
                          onclick={() => addRow(block)}>
                          <i class="fa-solid fa-plus me-1" aria-hidden="true"></i>
                          {$_('pages.shipping-method.add-row')}
                        </button>
                      {/if}

                      {#each blockMessages(result, cells) as code (code)}
                        <div class="invalid-feedback d-block">
                          {$_(`pages.shipping-method.errors.${code}`)}
                        </div>
                      {/each}
                      {#each result?.warnings ?? [] as warning (warning.row)}
                        <div class="text-warning small mt-1">
                          <i class="fa-solid fa-triangle-exclamation me-1" aria-hidden="true"></i>
                          {$_('pages.shipping-method.gap', {
                            values: {
                              from: formatBound(block.basis, warning.from),
                              to: formatBound(block.basis, warning.to),
                            },
                          })}
                        </div>
                      {/each}
                    </div>
                  {/each}

                  {#if freeZones.length > 0}
                    <div class="row">
                      <div class="col-md-6">
                        <select
                          class="form-select"
                          aria-label={$_('pages.shipping-method.add-zone')}
                          value=""
                          onchange={(e) => {
                            addZone(Number(e.currentTarget.value));
                            e.currentTarget.value = '';
                          }}>
                          <option value="">{$_('pages.shipping-method.add-zone')}</option>
                          {#each freeZones as zone (zone.id)}
                            <option value={zone.id}>{zone.name}</option>
                          {/each}
                        </select>
                      </div>
                    </div>
                  {/if}
                {/if}

                {#if submitted && (check.general || serverErrors.rates)}
                  <div class="invalid-feedback d-block">
                    {$_(`pages.shipping-method.errors.${serverErrors.rates ?? check.general}`)}
                  </div>
                {/if}
              </div>
            </div>
          </div>
        </div>

        <div class="col-lg-4">
          <div class="vstack gap-3">
            <div class="card">
              <div class="card-body vstack gap-3">
                <div class="d-flex justify-content-between align-items-center">
                  <label class="form-check-label" for="shippingMethodActive"
                    >{$_('common.status')}</label>
                  <div class="d-flex align-items-center gap-2">
                    <span>{form.active ? $_('common.active') : $_('common.inactive')}</span>
                    <div class="form-check form-switch m-0">
                      <input
                        id="shippingMethodActive"
                        class="form-check-input"
                        type="checkbox"
                        role="switch"
                        bind:checked={form.active} />
                    </div>
                  </div>
                </div>
                <button type="submit" class="btn btn-primary w-100" disabled={saving}>
                  {#if saving}
                    <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                  {/if}
                  {$_('common.save')}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </form>
  {/if}
</MarketLayout>

{#snippet fieldError(code)}
  {#if code}
    <div class="invalid-feedback d-block">{$_(`pages.shipping-method.errors.${code}`)}</div>
  {/if}
{/snippet}

<script module>
  import { api } from '@panomc/sdk/plugin-api';
  import { failureOf } from '../utils/api.js';
  import { loadContext } from '../utils/context.js';

  /**
   * GET /shipping/methods (the method is found by `?id`), GET /shipping/zones, GET /shipping/carriers and
   * GET /context (13 §19.2). Any failure becomes an explicit error state: a form that silently started
   * from defaults would overwrite the stored method on save.
   *
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.shipping-method.title');

    const id = searchParams.get('id');
    const [methodsRes, zonesRes, carriersRes, ctx] = await Promise.all([
      api.panel.get({ path: '/shipping/methods', request: event }),
      api.panel.get({ path: '/shipping/zones', request: event }),
      api.panel.get({ path: '/shipping/carriers', request: event }),
      loadContext(event),
    ]);

    const failure = [methodsRes, zonesRes, carriersRes].map(failureOf).find(Boolean);
    if (failure) return { data: { id, error: failure } };

    const methods = Array.isArray(methodsRes.items) ? methodsRes.items : [];
    const method = id ? (methods.find((m) => String(m.id) === String(id)) ?? null) : null;
    if (id && !method) return { data: { id, error: 'NOT_FOUND' } };

    return {
      data: {
        id,
        method,
        zones: Array.isArray(zonesRes.items) ? zonesRes.items : [],
        carriers: carriersRes.items ?? [],
        ctx,
      },
    };
  }
</script>

<script>
  import { untrack } from 'svelte';
  import { base, goto } from '@panomc/sdk/svelte';
  import { CardHeader } from '@panomc/sdk/components/panel';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import LoadError from '../components/LoadError.svelte';
  import MoneyInput from '../components/MoneyInput.svelte';
  import { call } from '../utils/api.js';
  import { currentLocale, fmt } from '../utils/locale.js';
  import { providerName } from '../utils/payment-methods.js';
  import { toastError } from '../utils/toast.js';
  import {
    MANUAL_PROVIDER,
    MAX_RATE_ROWS,
    RATE_BASES,
    RATE_SOURCES,
    basisHasPerUnit,
    basisRangeIsInteger,
    blankBlock,
    blankRow,
    blocksFromRates,
    buildMethodBody,
    carrierBlocks,
    findCarrier,
    methodServerErrors,
    methodToForm,
    nextRowFrom,
    keepsStoredSource,
    providerOptions,
    rangeQuantum,
    rateErrorMap,
    rateSourceAllowed,
    rowCount,
    switchRateSource,
    validateBlocks,
    validateMethod,
    withBasis,
    withProvider,
  } from '../utils/shipping-rates.js';

  let { data } = $props();

  // `{tracking}` is passed as a value: written in a message it would read as an ICU argument.
  const TRACKING_TOKEN = '{tracking}';

  const zones = $derived(data.zones ?? []);
  const carriers = $derived(data.carriers ?? []);
  const ctx = $derived(data.ctx ?? null);
  const currency = $derived(ctx?.currency ?? '');
  const exponent = $derived(
    ctx?.currencies?.find?.((c) => c.code === ctx?.currency)?.exponent ?? 2,
  );

  const rawTranslate = (key) => $rawI18n(key, { default: key });

  function seedOf(method) {
    const loaded = blocksFromRates(method?.rates);
    return { form: methodToForm(method), blocks: loaded.blocks, lossy: loaded.lossy };
  }

  const initial = untrack(() => seedOf(data.method ?? null));
  let seededFor = untrack(() => data.method ?? null);
  let form = $state(initial.form);
  let blocks = $state(initial.blocks);
  // rule rows set aside while the method is a CARRIER one (see switchRateSource)
  let stash = $state.raw(null);
  let lossy = $state(initial.lossy);
  let baseline = $state(JSON.stringify({ form: initial.form, blocks: initial.blocks }));
  let submitted = $state(false);
  let saving = $state(false);
  let finished = $state(false);
  let serverErrors = $state({ fields: {}, rates: null });
  let servicesState = $state.raw({ status: 'idle', list: [] });

  const isEdit = $derived(Boolean(data.method));
  const manual = $derived(form.providerId === MANUAL_PROVIDER);
  const provider = $derived(findCarrier(carriers, form.providerId));
  const providers = $derived(
    providerOptions(carriers, form.providerId, (c) =>
      providerName(c, currentLocale(), rawTranslate),
    ),
  );
  const serviceOptions = $derived.by(() => {
    const list = servicesState.list.filter((s) => s && typeof s.code === 'string');
    const stored = form.serviceCode;
    return stored !== '' && !list.some((s) => s.code === stored)
      ? [...list, { code: stored, name: stored }]
      : list;
  });
  // The rate source and provider the method was loaded with: while that provider is unavailable the
  // stored source stays selectable and saveable (10 §5.1), only choosing a carrier source newly is blocked.
  const storedRateSource = $derived(data.method?.rateSource ?? null);
  const storedProviderId = $derived(data.method ? data.method.providerId || MANUAL_PROVIDER : null);
  const sourceAllowed = (source) =>
    rateSourceAllowed(source, provider) ||
    (!manual &&
      keepsStoredSource(source, provider, storedRateSource, {
        providerId: form.providerId,
        storedProviderId,
      }));
  const fieldErrors = $derived(
    validateMethod(form, { provider, storedRateSource, storedProviderId }),
  );
  const check = $derived(validateBlocks(blocks, { zones, rateSource: form.rateSource, exponent }));
  const shown = $derived({ ...(submitted ? fieldErrors : {}), ...serverErrors.fields });
  const freeZones = $derived(zones.filter((z) => !blocks.some((b) => b.zoneId === z.id)));
  const rowTotal = $derived(rowCount(blocks));
  const dirty = $derived(
    !finished && JSON.stringify($state.snapshot({ form, blocks })) !== baseline,
  );

  function seed(method) {
    const next = seedOf(method);
    seededFor = method;
    form = next.form;
    blocks = next.blocks;
    stash = null;
    lossy = next.lossy;
    baseline = JSON.stringify({ form: next.form, blocks: next.blocks });
    submitted = false;
    finished = false;
    serverErrors = { fields: {}, rates: null };
  }

  // A navigation between two methods (or to a new one) keeps this component: re-seed from the new data.
  $effect(() => {
    const method = data.method ?? null;
    untrack(() => {
      if (method !== seededFor) seed(method);
    });
  });

  // The service list of a non-manual provider (13 §19.2): a failed request turns the select into a text input.
  $effect(() => {
    const id = form.providerId;
    if (!id || id === MANUAL_PROVIDER) {
      servicesState = { status: 'idle', list: [] };
      return;
    }
    let cancelled = false;
    servicesState = { status: 'loading', list: [] };
    call(api.panel.get({ path: `/shipping/carriers/${encodeURIComponent(id)}/services` })).then(
      (result) => {
        if (cancelled) return;
        servicesState = result.ok
          ? { status: 'ok', list: Array.isArray(result.body.services) ? result.body.services : [] }
          : { status: 'failed', list: [] };
      },
    );
    return () => {
      cancelled = true;
    };
  });

  // A dirty form guards navigation away.
  $effect(() => {
    if (!dirty) return;
    const handler = (event) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  });

  const zoneName = (id) => zones.find((z) => z.id === id)?.name ?? `#${id}`;
  const rangeUnit = (basis) =>
    basis === 'WEIGHT'
      ? $_('pages.shipping-method.unit-gram')
      : basis === 'QUANTITY'
        ? $_('pages.shipping-method.unit-piece')
        : currency;
  const rangeExponent = (basis) => (basisRangeIsInteger(basis) ? 0 : exponent);
  const formatBound = (basis, value) =>
    basis === 'AMOUNT' ? fmt.money(value, currency) : `${value} ${rangeUnit(basis)}`;

  // Messages under a block: the order / overlap errors once each, and one note when a cell is empty or
  // malformed (the cell itself is marked).
  const CELL_CODES = ['REQUIRED', 'INVALID', 'NEGATIVE'];
  function blockMessages(result, cells) {
    if (!submitted || !result) return [];
    const codes = [
      ...new Set(result.errors.map((e) => e.code).filter((c) => !CELL_CODES.includes(c))),
    ];
    if (Object.values(cells).some((c) => CELL_CODES.includes(c))) codes.push('CELL');
    return codes;
  }

  function applySwitch(from, to) {
    const next = switchRateSource($state.snapshot(blocks), from, to, stash);
    blocks = next.blocks;
    stash = next.stash;
  }

  function chooseProvider(id) {
    const previous = form.rateSource;
    form = withProvider($state.snapshot(form), id, carriers);
    serverErrors = { fields: {}, rates: null };
    if (form.rateSource !== previous) applySwitch(previous, form.rateSource);
  }

  function setRateSource(source) {
    if (source === form.rateSource) return;
    applySwitch(form.rateSource, source);
    form.rateSource = source;
  }

  function addZone(zoneId) {
    if (!Number.isFinite(zoneId) || blocks.some((b) => b.zoneId === zoneId)) return;
    blocks = [...blocks, blankBlock(zoneId)];
  }

  function toggleCarrierZone(zoneId, checked) {
    if (checked) {
      if (!blocks.some((b) => b.zoneId === zoneId))
        blocks = [...blocks, ...carrierBlocks([zoneId])];
    } else blocks = blocks.filter((b) => b.zoneId !== zoneId);
  }

  const removeBlock = (zoneId) => (blocks = blocks.filter((b) => b.zoneId !== zoneId));

  function setBasis(zoneId, basis) {
    blocks = blocks.map((b) => (b.zoneId === zoneId ? withBasis($state.snapshot(b), basis) : b));
  }

  function addRow(block) {
    block.rows.push(
      blankRow(block.basis, {
        rangeFrom: nextRowFrom($state.snapshot(block.rows), rangeQuantum(block.basis, exponent)),
      }),
    );
  }

  const removeRow = (block, index) => {
    if (block.rows.length > 1) block.rows.splice(index, 1);
  };

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    serverErrors = { fields: {}, rates: null };
    if (Object.keys(fieldErrors).length > 0 || !check.ok) return;

    const body = buildMethodBody($state.snapshot(form), $state.snapshot(blocks));
    saving = true;
    let result;
    try {
      result = isEdit
        ? await call(api.panel.put({ path: `/shipping/methods/${data.method.id}`, body }))
        : await call(api.panel.post({ path: '/shipping/methods', body }));
    } finally {
      saving = false;
    }

    if (!result.ok) {
      if (result.error === 'INVALID_SETTINGS')
        serverErrors = methodServerErrors(result.body.fieldErrors);
      toastError($_, result);
      if (result.error === 'NOT_FOUND') {
        finished = true;
        await goto(`${base}/market/settings?section=shipping-methods`);
      }
      return;
    }

    finished = true;
    showSuccessToast(
      isEdit
        ? $_('pages.shipping-method.toast-updated')
        : $_('pages.shipping-method.toast-created'),
    );
    await goto(`${base}/market/settings?section=shipping-methods`);
  }
</script>
