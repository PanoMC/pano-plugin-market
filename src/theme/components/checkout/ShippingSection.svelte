<div class="card">
  <div class="card-body vstack gap-3">
    <h2 class="h5 mb-0">{$_('theme.checkout.shipping')}</h2>

    {#if loggedIn && addresses.length > 0}
      <fieldset class="vstack gap-2">
        <legend class="form-label fs-6">{$_('theme.checkout.saved-addresses')}</legend>
        <div class="list-group">
          {#each addresses as saved (saved.id)}
            <label class="list-group-item d-flex gap-2">
              <input
                class="form-check-input flex-shrink-0"
                type="radio"
                name="market-checkout-address-choice"
                value={saved.id}
                checked={shippingAddressId === saved.id}
                onchange={() => onchange({ shippingAddressId: saved.id })} />
              <span>
                <span class="fw-semibold">{saved.label || summaryName(saved)}</span>
                {#if saved.isDefault}
                  <span class="badge text-bg-secondary ms-1"
                    >{$_('theme.checkout.address-default')}</span>
                {/if}
                <span class="d-block small text-body-secondary">{summary(saved)}</span>
              </span>
            </label>
          {/each}
          <label class="list-group-item d-flex gap-2">
            <input
              class="form-check-input flex-shrink-0"
              type="radio"
              name="market-checkout-address-choice"
              value="new"
              checked={shippingAddressId === null}
              onchange={() => onchange({ shippingAddressId: null })} />
            <span>{$_('theme.checkout.address-new')}</span>
          </label>
        </div>
      </fieldset>
    {/if}

    {#if shippingAddressId === null}
      <AddressForm
        kind="shipping"
        {required}
        value={address}
        {errors}
        countries={countryCodes}
        onchange={(patch) => onchange({ shippingAddress: { ...address, ...patch } })}
        {onblur} />

      {#if loggedIn && addresses.length < MAX_SAVED_ADDRESSES}
        <div class="form-check">
          <input
            id="market-checkout-save-address"
            class="form-check-input"
            type="checkbox"
            checked={saveAddress}
            onchange={(event) => onchange({ saveAddress: event.currentTarget.checked })} />
          <label class="form-check-label" for="market-checkout-save-address"
            >{$_('theme.checkout.save-address')}</label>
        </div>
      {/if}
    {/if}

    {#if addressReady}
      <fieldset
        class="vstack gap-2"
        aria-busy={quoting ? 'true' : undefined}
        aria-describedby={errors.method ? `${methodId}-error` : undefined}>
        <legend class="form-label fs-6">{$_('theme.checkout.shipping-method')}</legend>

        {#if options.length > 0}
          <div class={['list-group', quoting && 'opacity-50']}>
            {#each options as option, index (option.methodId)}
              <label class="list-group-item d-flex gap-2">
                <input
                  id={index === 0 ? methodId : undefined}
                  class={['form-check-input', 'flex-shrink-0', errors.method && 'is-invalid']}
                  type="radio"
                  name="market-checkout-shipping-method"
                  value={option.methodId}
                  checked={selectedMethodId === option.methodId}
                  onchange={() => onchange({ shippingMethodId: option.methodId })} />
                <span class="flex-grow-1">
                  <span class="fw-semibold">{option.name}</span>
                  {#if option.description}
                    <span class="d-block small text-body-secondary">{option.description}</span>
                  {/if}
                  {#if days(option)}
                    <span class="d-block small text-body-secondary">{days(option)}</span>
                  {/if}
                </span>
                {#if option.free}
                  <span class="badge text-bg-success align-self-start"
                    >{$_('theme.checkout.shipping-free')}</span>
                {:else}
                  <span class="text-nowrap">
                    {formatMoney(option.price, option.currency || quote?.currency, {
                      removeCents,
                    })}
                  </span>
                {/if}
              </label>
            {/each}
          </div>
          {#if errors.method}
            <div class="invalid-feedback d-block" id="{methodId}-error">
              {$_('theme.checkout.shipping-method-required')}
            </div>
          {/if}
        {:else if !quoting}
          <div class="alert alert-warning mb-0" role="alert">
            {$_('theme.errors.SHIPPING_UNAVAILABLE')}
          </div>
        {/if}
      </fieldset>
    {/if}
  </div>
</div>

<script>
  import { _ } from '../../../i18n.js';
  import { fieldId } from '../../lib/checkoutModel.js';
  import { formatMoney } from '../../utils/format.js';
  import AddressForm from './AddressForm.svelte';

  const MAX_SAVED_ADDRESSES = 10;

  /**
   * loggedIn / addresses: the buyer's saved addresses (GET me/addresses); shippingAddressId: the chosen saved
   * address or null for a typed one; address: the typed address; saveAddress: "Save this address".
   * errors: {<address field>: code, method?: code}; required: Set of required field names;
   * countryCodes: config.shippingCountries; quote: the current quote; addressReady: the address is complete
   * (methods are shown only then); selectedMethodId: the draft's shippingMethodId.
   * onchange(patch of the draft); onblur(field).
   */
  let {
    loggedIn = false,
    addresses = [],
    shippingAddressId = null,
    address = {},
    saveAddress = false,
    errors = {},
    required = new Set(),
    countryCodes = [],
    quote = null,
    quoting = false,
    addressReady = false,
    selectedMethodId = null,
    removeCents = false,
    onchange = () => {},
    onblur = () => {},
  } = $props();

  const methodId = fieldId('shipping', 'method');
  const options = $derived(Array.isArray(quote?.shippingOptions) ? quote.shippingOptions : []);

  const summaryName = (saved) => [saved.firstName, saved.lastName].filter(Boolean).join(' ');
  const summary = (saved) =>
    [saved.line1, saved.district, saved.city, saved.country].filter(Boolean).join(', ');

  function days(option) {
    const min = Number(option.minDays);
    const max = Number(option.maxDays);
    const hasMin = Number.isFinite(min) && min > 0;
    const hasMax = Number.isFinite(max) && max > 0;

    if (hasMin && hasMax && min !== max)
      return $_('theme.checkout.shipping-days', { minDays: min, maxDays: max });
    if (hasMax || hasMin)
      return $_('theme.checkout.shipping-days-single', { days: hasMax ? max : min });

    return '';
  }
</script>
