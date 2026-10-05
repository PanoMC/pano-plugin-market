<div class="card">
  <div class="card-body">
    <SwitchRow
      id="setting-allowGuestCheckout"
      label={$_('settings.checkout.allow-guest-checkout')}
      hint={$_('settings.checkout.allow-guest-checkout-hint')}
      error={message('allowGuestCheckout')}
      bind:checked={draft.allowGuestCheckout} />
    <SwitchRow
      id="setting-allowGiftPurchase"
      label={$_('settings.checkout.allow-gift-purchase')}
      error={message('allowGiftPurchase')}
      bind:checked={draft.allowGiftPurchase} />

    <SettingRow
      id="setting-minimumOrderAmount"
      label={$_('settings.checkout.minimum-order-amount')}
      hint={$_('settings.checkout.minimum-order-amount-hint')}
      error={message('minimumOrderAmount')}>
      <MoneyInput
        id="setting-minimumOrderAmount"
        bind:value={draft.minimumOrderAmount}
        {currency}
        {exponent}
        invalid={Boolean(shown.minimumOrderAmount)}
        placeholder={$_('settings.checkout.minimum-order-amount')} />
    </SettingRow>

    <SwitchRow
      id="setting-combineDiscountsAndCoupons"
      label={$_('settings.checkout.combine-discounts-and-coupons')}
      error={message('combineDiscountsAndCoupons')}
      bind:checked={draft.combineDiscountsAndCoupons} />

    <SettingRow
      id="setting-orderExpiryMinutes"
      label={$_('settings.checkout.order-expiry-minutes')}
      hint={$_('settings.checkout.order-expiry-minutes-hint')}
      error={message('orderExpiryMinutes')}>
      <div class="input-group">
        <input
          id="setting-orderExpiryMinutes"
          type="number"
          min="5"
          max="10080"
          step="1"
          class="form-control"
          class:is-invalid={shown.orderExpiryMinutes}
          bind:value={draft.orderExpiryMinutes} />
        <span class="input-group-text">{$_('settings.checkout.minutes')}</span>
      </div>
    </SettingRow>

    <SettingRow
      id="setting-bankTransferExpiryHours"
      label={$_('settings.checkout.bank-transfer-expiry-hours')}
      hint={$_('settings.checkout.bank-transfer-expiry-hours-hint')}
      error={message('bankTransferExpiryHours')}>
      <div class="input-group">
        <input
          id="setting-bankTransferExpiryHours"
          type="number"
          min="1"
          max="720"
          step="1"
          class="form-control"
          class:is-invalid={shown.bankTransferExpiryHours}
          bind:value={draft.bankTransferExpiryHours} />
        <span class="input-group-text">{$_('settings.checkout.hours')}</span>
      </div>
    </SettingRow>

    <SwitchRow
      id="setting-autoRefundDuplicatePayments"
      label={$_('settings.checkout.auto-refund-duplicate-payments')}
      hint={$_('settings.checkout.auto-refund-duplicate-payments-hint')}
      error={message('autoRefundDuplicatePayments')}
      bind:checked={draft.autoRefundDuplicatePayments} />
    <SwitchRow
      id="setting-subscriptionManualFallback"
      label={$_('settings.checkout.subscription-manual-fallback')}
      hint={$_('settings.checkout.subscription-manual-fallback-hint')}
      error={message('subscriptionManualFallback')}
      bind:checked={draft.subscriptionManualFallback} />
  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary" onclick={onSave} disabled={saving || !isDirty}>
      {#if saving}
        <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
      {/if}
      {$_('common.save')}
    </button>
  </div>
</div>

<script>
  import { untrack } from 'svelte';
  import { _ } from '../../../i18n';
  import MoneyInput from '../MoneyInput.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import {
    SECTION_KEYS,
    buildSettingsBody,
    currencyExponent,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
    validateCheckout,
  } from '../../utils/settings.js';

  let { settings: initial = {}, ctx = null } = $props();

  const KEYS = SECTION_KEYS.checkout;
  const start = untrack(() => initial ?? {});

  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  const currency = $derived(ctx?.currency ?? settings.currency ?? '');
  const exponent = $derived(currencyExponent(ctx, currency));
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateCheckout(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, KEYS)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

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
        keys: KEYS,
        errors: clientErrors,
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
