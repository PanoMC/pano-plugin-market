<div class="vstack gap-3 animate__animated animate__fadeIn">
  {#if ctx?.shippingEnabled === false}
    <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
      <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('pages.create-product.shipping.disabled-title')}</b>
        <div>{$_('pages.create-product.shipping.disabled-body')}</div>
        {#if can(user, 'SET')}
          <a class="alert-link" href="{base}/market/settings?section=shipping-methods">
            {$_('pages.create-product.shipping.disabled-link')}
          </a>
        {/if}
      </div>
    </div>
  {/if}

  <div class="card">
    <div class="card-body p-4">
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="p-weight">
          {$_('pages.create-product.shipping.weight')} *
        </label>
        <div class="col-sm-9">
          <div class="input-group">
            <input
              id="p-weight"
              type="number"
              min="1"
              step="1"
              class="form-control"
              class:is-invalid={errors.weightGrams}
              data-field="weightGrams"
              placeholder="250"
              bind:value={product.weightGrams} />
            <span class="input-group-text">{WEIGHT_UNIT}</span>
          </div>
          {@render feedback('weightGrams')}
        </div>
      </div>

      <div class="row mb-3 align-items-start">
        <div class="col-sm-3 col-form-label">{$_('pages.create-product.shipping.dimensions')}</div>
        <div class="col-sm-9">
          <div class="row g-2">
            {#each DIMENSIONS as name (name)}
              <div class="col-sm-4">
                <div class="input-group">
                  <input
                    type="number"
                    min="1"
                    step="1"
                    class="form-control"
                    class:is-invalid={errors[name]}
                    data-field={name}
                    placeholder={$_(`pages.create-product.shipping.${name}`)}
                    aria-label={$_(`pages.create-product.shipping.${name}`)}
                    bind:value={product[name]} />
                  <span class="input-group-text">{LENGTH_UNIT}</span>
                </div>
                {@render feedback(name)}
              </div>
            {/each}
          </div>
        </div>
      </div>

      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="p-sku">
          {$_('pages.create-product.shipping.sku')}
        </label>
        <div class="col-sm-9">
          <input
            id="p-sku"
            type="text"
            maxlength="64"
            autocomplete="off"
            class="form-control font-monospace"
            class:is-invalid={errors.sku}
            data-field="sku"
            placeholder={$_('pages.create-product.shipping.sku')}
            bind:value={product.sku} />
          {@render feedback('sku')}
        </div>
      </div>

      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="p-hs">
          {$_('pages.create-product.shipping.hs-code')}
        </label>
        <div class="col-sm-9">
          <input
            id="p-hs"
            type="text"
            maxlength="16"
            autocomplete="off"
            class="form-control font-monospace"
            class:is-invalid={errors.hsCode}
            data-field="hsCode"
            placeholder="6109.10"
            bind:value={product.hsCode} />
          {@render feedback('hsCode')}
        </div>
      </div>

      <div class="row align-items-center">
        <label class="col-sm-3 col-form-label" for="p-origin">
          {$_('pages.create-product.shipping.origin-country')}
        </label>
        <div class="col-sm-9">
          <select
            id="p-origin"
            class="form-select"
            class:is-invalid={errors.originCountry}
            data-field="originCountry"
            bind:value={product.originCountry}>
            <option value="">{$_('pages.create-product.shipping.origin-none')}</option>
            {#each countries as country (country.code)}
              <option value={country.code}>{country.name} ({country.code})</option>
            {/each}
          </select>
          {@render feedback('originCountry')}
        </div>
      </div>
    </div>
  </div>
</div>

{#snippet feedback(key)}
  {#if errors[key]}
    <div class="invalid-feedback d-block">{$_(extraErrorKey(errors[key]))}</div>
  {/if}
{/snippet}

<script>
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { can } from '../../utils/permissions.js';
  import { currentLocale } from '../../utils/locale.js';
  import { countryOptions } from './countries.js';
  import { extraErrorKey } from './extras.js';

  // Props contract of every tab: product (bindable), errors (dotted path -> code), ctx.
  let { product = $bindable(), errors = {}, ctx = null } = $props();

  const DIMENSIONS = ['lengthMm', 'widthMm', 'heightMm'];
  // SI unit symbols, the same in every locale (the API stores grams and millimetres)
  const WEIGHT_UNIT = 'g';
  const LENGTH_UNIT = 'mm';

  const user = $derived($page.data?.user);
  const countries = $derived(countryOptions(currentLocale()));
</script>
