<div class="vstack gap-3 animate__animated animate__fadeIn">
  <div class="card">
    <div class="card-body p-4 vstack gap-3">
      <!-- price -->
      <div class="row align-items-center">
        <label class="col-sm-3 col-form-label" for="product-price">
          {$_('pages.create-product.price')}
        </label>
        <div class="col-sm-9" data-field="price">
          <MoneyInput
            id="product-price"
            bind:value={product.price}
            {exponent}
            currency={ctx?.currencySymbol || ctx?.currency || ''}
            invalid={!!errors.price}
            placeholder="0.00" />
          {#if errors.price}
            <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.price))}</div>
          {/if}
        </div>
      </div>

      <div class="row align-items-center">
        <label class="col-sm-3 col-form-label" for="product-compare-at">
          {$_('pages.create-product.compare-at-price')}
        </label>
        <div class="col-sm-9" data-field="compareAtPrice">
          <MoneyInput
            id="product-compare-at"
            bind:value={product.compareAtPrice}
            {exponent}
            currency={ctx?.currencySymbol || ctx?.currency || ''}
            invalid={!!errors.compareAtPrice}
            placeholder={$_('pages.create-product.compare-at-placeholder')} />
          {#if errors.compareAtPrice}
            <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.compareAtPrice))}</div>
          {/if}
        </div>
      </div>

      {#if showCreditPrice}
        <div class="row align-items-center">
          <label class="col-sm-3 col-form-label" for="product-credit-price">
            {$_('pages.create-product.credit-price')}
          </label>
          <div class="col-sm-9" data-field="creditPrice">
            <MoneyInput
              id="product-credit-price"
              bind:value={product.creditPrice}
              currency={ctx?.creditName || $_('pages.create-product.credit')}
              invalid={!!errors.creditPrice}
              placeholder="0" />
            {#if errors.creditPrice}
              <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.creditPrice))}</div>
            {:else}
              <div class="form-text">{$_('pages.create-product.credit-price-help')}</div>
            {/if}
          </div>
        </div>
      {/if}

      {#if product.kind === 'CREDIT_PACK'}
        <div class="row align-items-center">
          <label class="col-sm-3 col-form-label" for="product-credit-amount">
            {$_('pages.create-product.credit-amount')}
          </label>
          <div class="col-sm-9" data-field="creditAmount">
            <MoneyInput
              id="product-credit-amount"
              bind:value={product.creditAmount}
              currency={ctx?.creditName || $_('pages.create-product.credit')}
              invalid={!!errors.creditAmount}
              placeholder="0" />
            {#if errors.creditAmount}
              <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.creditAmount))}</div>
            {:else}
              <div class="form-text">{$_('pages.create-product.credit-amount-help')}</div>
            {/if}
          </div>
        </div>
      {/if}

      <!-- VAT -->
      <div class="row align-items-center">
        <div class="col-sm-3">
          <span class="col-form-label">{$_('pages.create-product.vat')}</span>
        </div>
        <div class="col-sm-9 vstack gap-2">
          <div class="form-check form-switch">
            <input
              class="form-check-input"
              type="checkbox"
              role="switch"
              id="product-vat-default"
              checked={!vatOverride}
              onchange={onVatDefaultChange} />
            <label class="form-check-label" for="product-vat-default">
              {$_('pages.create-product.vat-default', {
                values: { percent: ctx?.vatPercent ?? 0 },
              })}
            </label>
          </div>
          {#if vatOverride}
            <div data-field="vatPercent">
              <div class="input-group">
                <input
                  type="number"
                  step="0.01"
                  min="0"
                  max="100"
                  class="form-control"
                  class:is-invalid={errors.vatPercent}
                  aria-label={$_('pages.create-product.vat')}
                  placeholder="0"
                  bind:value={product.vatPercent} />
                <span class="input-group-text">%</span>
              </div>
              {#if errors.vatPercent}
                <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.vatPercent))}</div>
              {/if}
            </div>
          {/if}
        </div>
      </div>

      <hr class="my-1 opacity-25" />

      <!-- billing -->
      <div class="row align-items-center">
        <div class="col-sm-3">
          <span class="col-form-label">{$_('pages.create-product.billing-mode')}</span>
        </div>
        <div class="col-sm-9" data-field="billingMode">
          <div class="btn-group" role="group" aria-label={$_('pages.create-product.billing-mode')}>
            {#each BILLING_MODES as mode (mode)}
              <input
                type="radio"
                class="btn-check"
                name="product-billing-mode"
                id="product-billing-{mode}"
                autocomplete="off"
                value={mode}
                disabled={billingLocked}
                bind:group={product.billingMode} />
              <label class="btn btn-outline-secondary" for="product-billing-{mode}">
                {$_(`enums.billing-mode.${mode}`)}
              </label>
            {/each}
          </div>
          {#if billingLocked}
            <div class="form-text">{$_('pages.create-product.billing-locked')}</div>
          {/if}
        </div>
      </div>

      {#if product.billingMode !== 'ONE_TIME'}
        <div class="row align-items-center">
          <label class="col-sm-3 col-form-label" for="product-period-count">
            {$_('pages.create-product.period')}
          </label>
          <div class="col-sm-9">
            <div class="row g-2">
              <div class="col-6" data-field="periodCount">
                <input
                  type="number"
                  min="1"
                  step="1"
                  id="product-period-count"
                  class="form-control"
                  class:is-invalid={errors.periodCount}
                  placeholder={$_('pages.create-product.period-count')}
                  bind:value={product.periodCount} />
                {#if errors.periodCount}
                  <div class="invalid-feedback d-block">
                    {$_(fieldErrorKey(errors.periodCount))}
                  </div>
                {/if}
              </div>
              <div class="col-6" data-field="periodUnit">
                <select
                  class="form-select"
                  class:is-invalid={errors.periodUnit}
                  aria-label={$_('pages.create-product.period-unit')}
                  bind:value={product.periodUnit}>
                  {#each units as unit (unit)}
                    <option value={unit}>{$_(`pages.create-product.units.${unit}`)}</option>
                  {/each}
                </select>
                {#if errors.periodUnit}
                  <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.periodUnit))}</div>
                {/if}
              </div>
            </div>
          </div>
        </div>
      {/if}

      {#if product.billingMode === 'SUBSCRIPTION'}
        <div class="row align-items-center">
          <label class="col-sm-3 col-form-label" for="product-max-cycles">
            {$_('pages.create-product.max-cycles')}
          </label>
          <div class="col-sm-9" data-field="subscriptionMaxCycles">
            <input
              type="number"
              min="1"
              step="1"
              id="product-max-cycles"
              class="form-control"
              class:is-invalid={errors.subscriptionMaxCycles}
              placeholder={$_('pages.create-product.max-cycles-placeholder')}
              bind:value={product.subscriptionMaxCycles} />
            {#if errors.subscriptionMaxCycles}
              <div class="invalid-feedback d-block">
                {$_(fieldErrorKey(errors.subscriptionMaxCycles))}
              </div>
            {/if}
          </div>
        </div>
      {/if}

      {#if !product.hasVariants}
        <hr class="my-1 opacity-25" />

        <!-- stock -->
        {#if isEdit}
          <div class="row align-items-center">
            <div class="col-sm-3">
              <span class="col-form-label">{$_('pages.create-product.stock')}</span>
            </div>
            <div class="col-sm-9 d-flex align-items-center gap-3">
              <span class="fw-medium">
                {product.hasStockLimit ? product.stock : $_('common.unlimited')}
              </span>
              <button
                type="button"
                class="btn btn-sm btn-secondary"
                onclick={() => onAdjustStock(null)}>
                {$_('pages.create-product.adjust-stock')}
              </button>
            </div>
          </div>
        {:else}
          <div class="row align-items-center">
            <label class="col-sm-3 col-form-label" for="product-stock-switch">
              {$_('pages.create-product.limit-stock')}
            </label>
            <div class="col-sm-9">
              <div class="form-check form-switch m-0">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="product-stock-switch"
                  bind:checked={product.hasStockLimit} />
              </div>
            </div>
          </div>
          {#if product.hasStockLimit}
            <div class="row align-items-center">
              <label class="col-sm-3 col-form-label" for="product-stock">
                {$_('pages.create-product.stock-amount')}
              </label>
              <div class="col-sm-9" data-field="stock">
                <input
                  type="number"
                  min="0"
                  step="1"
                  id="product-stock"
                  class="form-control"
                  class:is-invalid={errors.stock}
                  placeholder="0"
                  bind:value={product.stock} />
                {#if errors.stock}
                  <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.stock))}</div>
                {/if}
              </div>
            </div>
          {/if}
        {/if}
      {/if}
    </div>
  </div>

  {#if multi}
    <div class="card">
      <CardHeader>
        <div slot="left">{$_('pages.create-product.currency-prices')}</div>
      </CardHeader>
      <PriceGrid bind:rows={product.prices} {ctx} {errors} path="prices" />
    </div>
  {/if}
</div>

<script>
  import { CardHeader } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import MoneyInput from '../MoneyInput.svelte';
  import PriceGrid from './PriceGrid.svelte';
  import { BILLING_MODES, currencyExponent, fieldErrorKey, isMulti, unitsFor } from './model.js';

  let {
    product = $bindable(),
    errors = {},
    ctx = null,
    isEdit = false,
    onAdjustStock = () => {},
  } = $props();

  const exponent = $derived(currencyExponent(ctx));
  const multi = $derived(isMulti(ctx));
  const units = $derived(unitsFor(product.billingMode));
  const billingLocked = $derived(product.kind !== 'STANDARD');
  const showCreditPrice = $derived(
    !!ctx?.creditsEnabled &&
      product.kind !== 'CREDIT_PACK' &&
      product.billingMode !== 'SUBSCRIPTION',
  );

  let vatOverride = $state(product.vatPercent !== null);

  // An emptied override input is read as "store default" (an empty part clears it on edit).
  function onVatDefaultChange(event) {
    vatOverride = !event.currentTarget.checked;
    product.vatPercent = vatOverride ? (ctx?.vatPercent ?? 0) : null;
  }
</script>
