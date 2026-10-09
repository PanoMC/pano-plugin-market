<div class="market-address-form row g-2">
  {#each shown as field (field)}
    <div class={COLUMNS[field]}>
      <label class="market-address-form__label form-label" for={fieldId(kind, field)}>
        {$_(`theme.checkout.address.${field}`)}
        {#if required.has(field)}
          <span class="text-danger" aria-hidden="true">*</span>
        {/if}
      </label>

      {#if field === 'country'}
        <select
          id={fieldId(kind, field)}
          class={['market-address-form__select', 'form-select', errors[field] && 'is-invalid']}
          autocomplete="{kind} country"
          aria-required={required.has(field) ? 'true' : undefined}
          aria-invalid={errors[field] ? 'true' : undefined}
          aria-describedby={errors[field] ? `${fieldId(kind, field)}-error` : undefined}
          value={value[field] ?? ''}
          onchange={(event) => onchange({ [field]: event.currentTarget.value })}
          onblur={() => onblur(field)}>
          <option value="">{$_('theme.checkout.address.country-choose')}</option>
          {#each options as option (option.code)}
            <option value={option.code}>{option.label}</option>
          {/each}
        </select>
      {:else}
        <input
          id={fieldId(kind, field)}
          class={['market-address-form__input', 'form-control', errors[field] && 'is-invalid']}
          type={field === 'phone' ? 'tel' : 'text'}
          maxlength={field === 'postalCode' ? POSTAL_CODE_MAX : ADDRESS_TEXT_MAX}
          autocomplete="{kind} {AUTOCOMPLETE[field]}"
          aria-required={required.has(field) ? 'true' : undefined}
          aria-invalid={errors[field] ? 'true' : undefined}
          aria-describedby={describedBy(field)}
          value={value[field] ?? ''}
          oninput={(event) => onchange({ [field]: event.currentTarget.value })}
          onblur={() => onblur(field)} />
      {/if}

      {#if errors[field]}
        <div class="invalid-feedback" id="{fieldId(kind, field)}-error">
          {$_(fieldErrorKey(field, errors[field]))}
        </div>
      {/if}
      {#if field === 'phone' && !errors[field]}
        <div class="form-text" id="{fieldId(kind, field)}-hint">
          {$_('theme.checkout.address.phone-hint')}
        </div>
      {/if}
    </div>
  {/each}
</div>

<script>
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { plugin } from '@panomc/sdk/controllers';
  import { ADDRESS_FIELDS, fieldErrorKey, fieldId } from '../../lib/checkoutModel.js';
  import { countryOptions } from '../../lib/countries.js';
  import { ADDRESS_TEXT_MAX, POSTAL_CODE_MAX } from '../../lib/validation.js';

  const market = plugin('market');
  const { _ } = market;
  const { countryName } = market.require('format').actions;

  const COLUMNS = {
    firstName: 'col-md-6',
    lastName: 'col-md-6',
    company: 'col-12',
    phone: 'col-md-6',
    country: 'col-md-6',
    state: 'col-md-6',
    city: 'col-md-6',
    district: 'col-md-6',
    neighborhood: 'col-md-6',
    line1: 'col-12',
    line2: 'col-12',
    postalCode: 'col-md-6',
  };

  const AUTOCOMPLETE = {
    firstName: 'given-name',
    lastName: 'family-name',
    company: 'organization',
    phone: 'tel',
    state: 'address-level1',
    city: 'address-level2',
    district: 'off',
    neighborhood: 'off',
    line1: 'address-line1',
    line2: 'address-line2',
    postalCode: 'postal-code',
  };

  /**
   * One form for the shipping and the billing address (14 §10.5).
   * kind: 'shipping' | 'billing'; required: Set of field names; value: the address object;
   * errors: {field: message code}; countries: ISO codes of the select (shipping: config.shippingCountries,
   * billing: every country); fields: the fields to render (default all; billing mode OFF passes the named ones);
   * showCompany: the billing COMPANY type. onchange(patch of the address); onblur(field).
   */
  let {
    kind = 'shipping',
    required = new Set(),
    value = {},
    errors = {},
    countries = [],
    fields = null,
    showCompany = false,
    onchange = () => {},
    onblur = () => {},
  } = $props();

  const options = $derived(
    countryOptions(countries, countryName, $currentLanguage?.code ?? undefined),
  );

  // company only for a billing COMPANY, neighbourhood only for Turkey
  const shown = $derived(
    (fields ?? ADDRESS_FIELDS).filter(
      (field) =>
        ADDRESS_FIELDS.includes(field) &&
        (field !== 'company' || showCompany) &&
        (field !== 'neighborhood' || value.country === 'TR'),
    ),
  );

  const describedBy = (field) =>
    errors[field]
      ? `${fieldId(kind, field)}-error`
      : field === 'phone'
        ? `${fieldId(kind, field)}-hint`
        : undefined;
</script>
