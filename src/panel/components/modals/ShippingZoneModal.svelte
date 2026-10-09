<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {isEdit
            ? $_('modals.shipping-zone.heading-edit')
            : $_('modals.shipping-zone.heading-create')}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body">
          <div class="vstack gap-3">
            <div>
              <div class="form-floating">
                <input
                  id="shippingZoneName"
                  type="text"
                  class="form-control"
                  class:is-invalid={shown.name}
                  autocomplete="off"
                  placeholder={$_('modals.shipping-zone.name')}
                  bind:value={form.name} />
                <label for="shippingZoneName">{$_('modals.shipping-zone.name')}</label>
              </div>
              {#if shown.name}
                <div class="invalid-feedback d-block">
                  {$_(`modals.shipping-zone.errors.${shown.name}`)}
                </div>
              {/if}
            </div>

            <div class="form-check form-switch m-0">
              <input
                id="shippingZoneEverywhere"
                class="form-check-input"
                type="checkbox"
                role="switch"
                bind:checked={form.everywhere} />
              <label class="form-check-label" for="shippingZoneEverywhere">
                {$_('modals.shipping-zone.everywhere')}
              </label>
            </div>

            {#if !form.everywhere}
              <div>
                <input
                  type="search"
                  class="form-control mb-2"
                  autocomplete="off"
                  placeholder={$_('modals.shipping-zone.search-countries')}
                  aria-label={$_('modals.shipping-zone.search-countries')}
                  bind:value={countrySearch} />
                <div
                  class="border rounded p-2 overflow-auto"
                  class:border-danger={shown.countries}
                  style="max-height: 14rem;">
                  <div class="row row-cols-1 row-cols-md-2 g-1">
                    {#each visibleCountries as country (country.code)}
                      <div class="col">
                        <div class="form-check">
                          <input
                            id="shippingZoneCountry-{country.code}"
                            class="form-check-input"
                            type="checkbox"
                            checked={form.countries.includes(country.code)}
                            onchange={(e) =>
                              toggleCountry(country.code, e.currentTarget.checked)} />
                          <label class="form-check-label" for="shippingZoneCountry-{country.code}">
                            {country.name}
                            <span class="text-body-secondary">{country.code}</span>
                          </label>
                        </div>
                      </div>
                    {/each}
                  </div>
                </div>
                <div class="form-text">
                  {$_('modals.shipping-zone.selected', {
                    values: { count: form.countries.length },
                  })}
                </div>
              </div>
            {/if}
            {#if shown.countries}
              <div class="invalid-feedback d-block mt-0">
                {$_(`modals.shipping-zone.errors.${shown.countries}`)}
              </div>
            {/if}

            {#if !editableRegions && keptRegions}
              <div class="alert alert-info d-flex align-items-start mb-0" role="status">
                <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
                <div>
                  <b>{$_('modals.shipping-zone.regions-kept-title')}</b>
                  <div>{$_('modals.shipping-zone.regions-kept')}</div>
                </div>
              </div>
              {#if shown.regions}
                <div class="invalid-feedback d-block mt-0">
                  {$_(`modals.shipping-zone.errors.${shown.regions}`)}
                </div>
              {/if}
            {/if}

            {#if editableRegions}
              <div>
                <div class="mb-1">{$_('modals.shipping-zone.regions')}</div>
                <div class="vstack gap-2">
                  {#each form.countries as code (code)}
                    <div class="row g-2 align-items-center">
                      <div class="col-md-3">
                        {countryName(code, locale)} <span class="text-body-secondary">{code}</span>
                      </div>
                      <div class="col-md-9">
                        <TagInput
                          bind:values={form.regions[code]}
                          validate={isStateName}
                          max={MAX_STATES}
                          placeholder={$_('modals.shipping-zone.states-placeholder')} />
                      </div>
                    </div>
                  {/each}
                </div>
                {#if shown.regions}
                  <div class="invalid-feedback d-block">
                    {$_(`modals.shipping-zone.errors.${shown.regions}`)}
                  </div>
                {/if}
              </div>
            {/if}

            <div>
              <div class="mb-1">{$_('modals.shipping-zone.postal')}</div>
              <TagInput
                bind:values={form.postalPatterns}
                validate={isPostalPattern}
                max={MAX_POSTAL_PATTERNS}
                placeholder={$_('modals.shipping-zone.postal-placeholder')} />
              {#if shown.postalPatterns}
                <div class="invalid-feedback d-block">
                  {$_(`modals.shipping-zone.errors.${shown.postalPatterns}`)}
                </div>
              {/if}
            </div>

            <div class="form-check form-switch m-0">
              <input
                id="shippingZoneActive"
                class="form-check-input"
                type="checkbox"
                role="switch"
                bind:checked={form.active} />
              <label class="form-check-label" for="shippingZoneActive">{$_('common.active')}</label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {isEdit ? $_('common.save') : $_('common.create')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { _, showSuccessToast } from '../../../i18n';
  import TagInput from '../TagInput.svelte';
  import { countryName, countryOptions } from '../product/countries.js';
  import { call } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import {
    MAX_POSTAL_PATTERNS,
    MAX_STATES,
    blankZoneForm,
    buildZoneBody,
    hasHiddenRegions,
    isPostalPattern,
    isStateName,
    regionsEditable,
    validateZone,
    zoneServerErrors,
    zoneToForm,
  } from '../../utils/shipping-rates.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  // Create / edit form of one shipping zone (13 §19.1). `onSaved()` refreshes the list.
  let { onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let form = $state(blankZoneForm());
  let zone = $state.raw(null);
  let zones = $state.raw([]);
  let submitted = $state(false);
  let saving = $state(false);
  let serverErrors = $state({});
  let countrySearch = $state('');
  let locale = $state('en-US');

  const isEdit = $derived(zone !== null);
  const options = $derived(countryOptions(locale));
  const visibleCountries = $derived.by(() => {
    const term = countrySearch.trim().toLowerCase();
    if (term === '') return options;
    return options.filter(
      (c) => c.name.toLowerCase().includes(term) || c.code.toLowerCase() === term,
    );
  });
  const editableRegions = $derived(regionsEditable(form));
  const keptRegions = $derived(hasHiddenRegions(form));
  const clientErrors = $derived(validateZone(form, { zones, id: zone?.id ?? null }));
  const shown = $derived({ ...(submitted ? clientErrors : {}), ...serverErrors });

  /** `item` = the zone to edit or null to create; `list` = the loaded zones (one "Everywhere Else" check). */
  export function open(item, list = []) {
    zone = item;
    zones = list;
    locale = currentLocale();
    form = item ? zoneToForm(item) : blankZoneForm();
    // every selected country needs an array for its TagInput binding
    for (const code of form.countries) form.regions[code] ??= [];
    countrySearch = '';
    submitted = false;
    serverErrors = {};
    showModal(modalElement);
  }

  function toggleCountry(code, checked) {
    const next = checked
      ? [...form.countries.filter((c) => c !== code), code]
      : form.countries.filter((c) => c !== code);
    form.countries = next;
    // regions of a country that is no longer selected go away, a newly selected one starts empty
    const regions = {};
    for (const c of next) regions[c] = form.regions[c] ?? [];
    form.regions = regions;
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    serverErrors = {};
    if (Object.keys(clientErrors).length > 0) return;

    const body = buildZoneBody(form);
    saving = true;
    let response;
    try {
      response = isEdit
        ? await call(api.panel.put({ path: `/shipping/zones/${zone.id}`, body }))
        : await call(api.panel.post({ path: '/shipping/zones', body }));
    } finally {
      saving = false;
    }
    if (!response.ok) {
      if (response.error === 'INVALID_SETTINGS')
        serverErrors = zoneServerErrors(response.body.fieldErrors);
      toastError($_, response);
      if (response.error === 'NOT_FOUND') {
        hideModal(modalElement);
        onSaved();
      }
      return;
    }
    showSuccessToast(
      isEdit ? $_('modals.shipping-zone.toast-updated') : $_('modals.shipping-zone.toast-created'),
    );
    hideModal(modalElement);
    onSaved();
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
