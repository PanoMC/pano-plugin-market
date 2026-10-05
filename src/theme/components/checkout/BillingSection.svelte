<div class="card">
  <div class="card-body vstack gap-3">
    <h2 class="h5 mb-0">{$_('theme.checkout.billing')}</h2>

    {#if req.mode === 'OPTIONAL'}
      <div class="form-check">
        <input
          id="market-checkout-billing-open"
          class="form-check-input"
          type="checkbox"
          checked={req.open}
          disabled={req.forcedOpen}
          onchange={(event) => onopen(event.currentTarget.checked)} />
        <label class="form-check-label" for="market-checkout-billing-open"
          >{$_('theme.checkout.billing-add')}</label>
      </div>
    {/if}

    {#if req.open}
      <fieldset class="d-flex flex-wrap gap-3">
        <legend class="visually-hidden">{$_('theme.checkout.billing-type')}</legend>
        {#each TYPES as type (type)}
          <div class="form-check">
            <input
              id="market-checkout-billing-type-{type}"
              class="form-check-input"
              type="radio"
              name="market-checkout-billing-type"
              value={type}
              checked={req.type === type}
              onchange={() => onchange({ type })} />
            <label class="form-check-label" for="market-checkout-billing-type-{type}"
              >{$_(`theme.checkout.billing-type-${type}`)}</label>
          </div>
        {/each}
      </fieldset>

      {#if shippingRequired}
        <div class="form-check">
          <input
            id="market-checkout-billing-same"
            class="form-check-input"
            type="checkbox"
            checked={sameAsShipping}
            onchange={(event) => onsame(event.currentTarget.checked)} />
          <label class="form-check-label" for="market-checkout-billing-same"
            >{$_('theme.checkout.billing-same')}</label>
        </div>
      {/if}

      {#if req.showAddress && req.fields.length > 0}
        <AddressForm
          kind="billing"
          required={req.required}
          value={info}
          {errors}
          countries={COUNTRY_CODES}
          fields={req.fields}
          showCompany={req.showCompany}
          onchange={(patch) => onchange(patch)}
          {onblur} />
      {:else if req.copy && req.copyFields.length > 0}
        <p class="small text-body-secondary mb-0" id="market-checkout-billing-copy-missing">
          {$_('theme.checkout.billing-copy-missing')}
        </p>
        <AddressForm
          kind="billing"
          required={copyRequired}
          value={info}
          {errors}
          countries={COUNTRY_CODES}
          fields={req.copyFields}
          onchange={(patch) => onchange(patch)}
          {onblur} />
      {/if}

      {#if !req.showAddress && req.showCompany && req.copy}
        <div>
          <label class="form-label" for={fieldId('billing', 'company')}>
            {$_('theme.checkout.address.company')}
            <span class="text-danger" aria-hidden="true">*</span>
          </label>
          <input
            id={fieldId('billing', 'company')}
            class={['form-control', errors.company && 'is-invalid']}
            type="text"
            maxlength={ADDRESS_TEXT_MAX}
            autocomplete="billing organization"
            aria-required="true"
            aria-invalid={errors.company ? 'true' : undefined}
            value={info.company ?? ''}
            oninput={(event) => onchange({ company: event.currentTarget.value })}
            onblur={() => onblur('company')} />
          {#if errors.company}
            <div class="invalid-feedback">{$_(fieldErrorKey('company', errors.company))}</div>
          {/if}
        </div>
      {/if}

      {#if req.showIdentity || req.showCompany}
        <div class="row g-2">
          {#if req.showIdentity}
            <div class="col-md-6">
              <label class="form-label" for={fieldId('billing', 'identityNumber')}>
                {$_('theme.checkout.address.identityNumber')}
                {#if req.identityRequired}
                  <span class="text-danger" aria-hidden="true">*</span>
                {/if}
              </label>
              <input
                id={fieldId('billing', 'identityNumber')}
                class={['form-control', errors.identityNumber && 'is-invalid']}
                type="text"
                inputmode="numeric"
                maxlength={IDENTITY_MAX}
                autocomplete="off"
                aria-required={req.identityRequired ? 'true' : undefined}
                aria-invalid={errors.identityNumber ? 'true' : undefined}
                value={info.identityNumber ?? ''}
                oninput={(event) => onchange({ identityNumber: event.currentTarget.value })}
                onblur={() => onblur('identityNumber')} />
              {#if errors.identityNumber}
                <div class="invalid-feedback">
                  {$_(fieldErrorKey('identityNumber', errors.identityNumber))}
                </div>
              {/if}
            </div>
          {/if}

          {#if req.showCompany}
            <div class="col-md-6">
              <label class="form-label" for={fieldId('billing', 'taxOffice')}
                >{$_('theme.checkout.address.taxOffice')}</label>
              <input
                id={fieldId('billing', 'taxOffice')}
                class={['form-control', errors.taxOffice && 'is-invalid']}
                type="text"
                maxlength={TAX_MAX}
                autocomplete="off"
                aria-invalid={errors.taxOffice ? 'true' : undefined}
                value={info.taxOffice ?? ''}
                oninput={(event) => onchange({ taxOffice: event.currentTarget.value })}
                onblur={() => onblur('taxOffice')} />
              {#if errors.taxOffice}
                <div class="invalid-feedback">
                  {$_(fieldErrorKey('taxOffice', errors.taxOffice))}
                </div>
              {/if}
            </div>
            <div class="col-md-6">
              <label class="form-label" for={fieldId('billing', 'taxNumber')}>
                {$_('theme.checkout.address.taxNumber')}
                <span class="text-danger" aria-hidden="true">*</span>
              </label>
              <input
                id={fieldId('billing', 'taxNumber')}
                class={['form-control', errors.taxNumber && 'is-invalid']}
                type="text"
                maxlength={TAX_MAX}
                autocomplete="off"
                aria-required="true"
                aria-invalid={errors.taxNumber ? 'true' : undefined}
                value={info.taxNumber ?? ''}
                oninput={(event) => onchange({ taxNumber: event.currentTarget.value })}
                onblur={() => onblur('taxNumber')} />
              {#if errors.taxNumber}
                <div class="invalid-feedback">
                  {$_(fieldErrorKey('taxNumber', errors.taxNumber))}
                </div>
              {/if}
            </div>
          {/if}
        </div>
      {/if}
    {/if}
  </div>
</div>

<script>
  import { _ } from '../../../i18n.js';
  import { fieldErrorKey, fieldId } from '../../lib/checkoutModel.js';
  import { COUNTRY_CODES } from '../../lib/countries.js';
  import { ADDRESS_TEXT_MAX, IDENTITY_MAX, TAX_MAX } from '../../lib/validation.js';
  import AddressForm from './AddressForm.svelte';

  const TYPES = ['INDIVIDUAL', 'COMPANY'];

  /**
   * req: billingRequirements() of lib/checkoutModel.js (mode, open, required, fields, ...);
   * info: draft.billingInfo; errors: {<field>: code}; shippingRequired: the shipping section is shown;
   * sameAsShipping: draft.billingSameAsShipping.
   * onchange(patch of billingInfo); onopen(bool); onsame(bool); onblur(field).
   */
  let {
    req,
    info = {},
    errors = {},
    shippingRequired = false,
    sameAsShipping = true,
    onchange = () => {},
    onopen = () => {},
    onsame = () => {},
    onblur = () => {},
  } = $props();

  // the fields the copied shipping address lacks are required inputs
  const copyRequired = $derived(new Set(req.copyFields ?? []));
</script>
