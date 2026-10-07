<InvoiceSequenceModal bind:this={sequenceModal} onSaved={onSequenceSaved} />

{#if sellerNameNotice(draft)}
  <div class="alert alert-info d-flex align-items-start mb-3" role="alert">
    <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('settings.billing.seller-notice.title')}</b>
      <div>{$_('settings.billing.seller-notice.body')}</div>
    </div>
  </div>
{/if}

<div class="vstack gap-3">
  <div class="card">
    <div class="card-body">
      <div class="mb-3">
        <div class="mb-2">{$_('settings.billing.info-mode')}</div>
        {#each BILLING_INFO_MODES as mode (mode)}
          <div class="form-check">
            <input
              id="setting-billingInfoMode-{mode}"
              class="form-check-input"
              class:is-invalid={shown.billingInfoMode}
              type="radio"
              name="billingInfoMode"
              value={mode}
              bind:group={draft.billingInfoMode} />
            <label class="form-check-label" for="setting-billingInfoMode-{mode}">
              {$_(`enums.billing-info-mode.${mode}`)}
              <span class="form-text d-block mt-0">
                {$_(`settings.billing.info-mode-hint.${mode}`)}
              </span>
            </label>
          </div>
        {/each}
        {#if shown.billingInfoMode}
          <div class="invalid-feedback d-block">{message('billingInfoMode')}</div>
        {/if}
      </div>

      <hr class="my-4" />

      <SwitchRow
        id="setting-invoiceEnabled"
        label={$_('settings.billing.invoice-enabled')}
        hint={$_('settings.billing.invoice-enabled-hint')}
        error={message('invoiceEnabled')}
        bind:checked={draft.invoiceEnabled} />

      {#if draft.invoiceEnabled}
        <SettingRow
          id="setting-invoiceSeries"
          label={$_('settings.billing.series')}
          hint={$_('settings.billing.series-hint')}
          error={message('invoiceSeries')}>
          <input
            id="setting-invoiceSeries"
            type="text"
            class="form-control"
            class:is-invalid={shown.invoiceSeries}
            maxlength="12"
            autocomplete="off"
            placeholder={$_('settings.billing.series')}
            bind:value={draft.invoiceSeries} />
        </SettingRow>

        <SettingRow
          id="setting-invoiceCreditNoteSeries"
          label={$_('settings.billing.credit-note-series')}
          error={message('invoiceCreditNoteSeries')}>
          <input
            id="setting-invoiceCreditNoteSeries"
            type="text"
            class="form-control"
            class:is-invalid={shown.invoiceCreditNoteSeries}
            maxlength="12"
            autocomplete="off"
            placeholder={$_('settings.billing.credit-note-series')}
            bind:value={draft.invoiceCreditNoteSeries} />
        </SettingRow>

        <SwitchRow
          id="setting-invoiceCreditOrders"
          label={$_('settings.billing.credit-orders')}
          hint={$_('settings.billing.credit-orders-hint')}
          error={message('invoiceCreditOrders')}
          bind:checked={draft.invoiceCreditOrders} />

        <SettingRow
          id="setting-invoiceLocale"
          label={$_('settings.billing.locale')}
          error={message('invoiceLocale')}>
          <select
            id="setting-invoiceLocale"
            class="form-select"
            class:is-invalid={shown.invoiceLocale}
            bind:value={draft.invoiceLocale}>
            <option value="">{$_('settings.billing.locale-buyer')}</option>
            {#each locales as locale (locale.code)}
              <option value={locale.code}>{locale.name}</option>
            {/each}
          </select>
        </SettingRow>

        <SwitchRow
          id="setting-invoiceShowLogo"
          label={$_('settings.billing.show-logo')}
          error={message('invoiceShowLogo')}
          bind:checked={draft.invoiceShowLogo} />

        <SettingRow
          id="setting-invoiceSellerName"
          label={$_('settings.billing.seller-name')}
          error={message('invoiceSellerName')}>
          <input
            id="setting-invoiceSellerName"
            type="text"
            class="form-control"
            class:is-invalid={shown.invoiceSellerName}
            autocomplete="off"
            placeholder={$_('settings.billing.seller-name')}
            bind:value={draft.invoiceSellerName} />
        </SettingRow>

        <SettingRow
          id="setting-invoiceSellerAddress"
          label={$_('settings.billing.seller-address')}
          error={message('invoiceSellerAddress')}>
          <textarea
            id="setting-invoiceSellerAddress"
            class="form-control"
            class:is-invalid={shown.invoiceSellerAddress}
            rows="3"
            placeholder={$_('settings.billing.seller-address')}
            bind:value={draft.invoiceSellerAddress}></textarea>
        </SettingRow>

        <SettingRow
          id="setting-invoiceSellerTaxOffice"
          label={$_('settings.billing.seller-tax-office')}
          error={message('invoiceSellerTaxOffice')}>
          <input
            id="setting-invoiceSellerTaxOffice"
            type="text"
            class="form-control"
            class:is-invalid={shown.invoiceSellerTaxOffice}
            autocomplete="off"
            placeholder={$_('settings.billing.seller-tax-office')}
            bind:value={draft.invoiceSellerTaxOffice} />
        </SettingRow>

        <SettingRow
          id="setting-invoiceSellerTaxNumber"
          label={$_('settings.billing.seller-tax-number')}
          error={message('invoiceSellerTaxNumber')}>
          <input
            id="setting-invoiceSellerTaxNumber"
            type="text"
            class="form-control"
            class:is-invalid={shown.invoiceSellerTaxNumber}
            autocomplete="off"
            placeholder={$_('settings.billing.seller-tax-number')}
            bind:value={draft.invoiceSellerTaxNumber} />
        </SettingRow>

        <SettingRow
          id="setting-invoiceFooter"
          label={$_('settings.billing.footer')}
          error={message('invoiceFooter')}>
          <textarea
            id="setting-invoiceFooter"
            class="form-control"
            class:is-invalid={shown.invoiceFooter}
            rows="3"
            placeholder={$_('settings.billing.footer')}
            bind:value={draft.invoiceFooter}></textarea>
        </SettingRow>
      {/if}
    </div>

    <div class="card-footer d-flex justify-content-start">
      <button
        type="button"
        class="btn btn-secondary"
        onclick={onSave}
        disabled={saving || !isDirty}>
        {#if saving}
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {/if}
        {$_('common.save')}
      </button>
    </div>
  </div>

  {#if settings.invoiceEnabled ?? true}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('settings.billing.sequences.title', { values: { count: sequences.length } })}
        </div>
        <div slot="right">
          <a class="btn btn-sm btn-link" href={previewUrl} target="_blank" rel="noopener">
            <i class="fa-solid fa-file-pdf me-2" aria-hidden="true"></i>
            {$_('settings.billing.preview-pdf')}
          </a>
        </div>
      </CardHeader>

      {#if sequences.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.billing.sequences.series')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.billing.sequences.next-number')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each sequences as sequence (sequence.series)}
                <tr>
                  <th scope="row" class="align-middle text-center">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        aria-expanded="false"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <span class="fas fa-ellipsis-v"></span>
                      </button>
                      <div class="dropdown-menu dropdown-menu-start">
                        <button
                          type="button"
                          class="dropdown-item"
                          onclick={() => sequenceModal?.open(sequence)}>
                          <i class="fas fa-pen me-2" aria-hidden="true"></i>
                          {$_('common.edit')}
                        </button>
                      </div>
                    </div>
                  </th>
                  <td class="align-middle">{sequence.series}</td>
                  <td class="align-middle">{sequence.nextNumber}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  {/if}
</div>

<script>
  import { untrack } from 'svelte';
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { Languages } from '@panomc/sdk/utils/language';
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import InvoiceSequenceModal from '../modals/InvoiceSequenceModal.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import {
    BILLING_INFO_MODES,
    SECTION_KEYS,
    activeKeys,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    languageOptions,
    seedValues,
    sellerNameNotice,
    validateBilling,
  } from '../../utils/settings.js';

  let { settings: initial = {} } = $props();

  const KEYS = SECTION_KEYS.billing;
  const start = untrack(() => initial ?? {});

  let sequenceModal = $state(null);
  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  const locales = $derived(languageOptions($Languages));
  const sequences = $derived(
    Array.isArray(settings.invoiceSequences) ? settings.invoiceSequences : [],
  );
  const previewUrl = $derived(
    `${base}/api/panel/market/settings/invoice/preview${
      settings.invoiceLocale ? `?locale=${encodeURIComponent(settings.invoiceLocale)}` : ''
    }`,
  );
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateBilling(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const keys = $derived(activeKeys('billing', draft));
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, keys)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  // The sequences table follows the server after an edit; the form keeps its unsaved values.
  async function onSequenceSaved() {
    const body = await fetchSettings();
    if (body) settings = { ...settings, invoiceSequences: body.invoiceSequences };
  }

  async function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    if (Object.keys(clientErrors).length > 0) {
      reportFailure({ status: 'invalid', errors: clientErrors }, KEYS);
      return;
    }
    saving = true;
    try {
      const result = await saveSection({
        baseline: settings,
        values: draft,
        keys,
        errors: clientErrors,
        order: KEYS,
      });
      if (result.status === 'saved') {
        const next = (await fetchSettings()) ?? { ...settings, ...result.sent };
        settings = next;
        draft = seedValues(next, KEYS);
        submitted = false;
        serverMark = null;
      } else if (result.status === 'failed' && Object.keys(result.errors).length > 0) {
        serverMark = { errors: result.errors, draftKey };
      }
    } finally {
      saving = false;
    }
  }
</script>
