<div class="card animate__animated animate__fadeIn">
  <div class="card-body p-4">
    <div class="row mb-3 align-items-center">
      <label class="col-sm-3 col-form-label" for="p-limit-player">
        {$_('pages.create-product.limits.per-player')}
      </label>
      <div class="col-sm-9">
        <input
          id="p-limit-player"
          type="number"
          min="1"
          step="1"
          class="form-control"
          class:is-invalid={errors.limitPerPlayer}
          data-field="limitPerPlayer"
          placeholder={$_('pages.create-product.limits.no-limit')}
          bind:value={product.limitPerPlayer} />
        {@render feedback('limitPerPlayer')}
      </div>
    </div>

    <div class="row mb-3 align-items-center">
      <label class="col-sm-3 col-form-label" for="p-max-quantity">
        {$_('pages.create-product.limits.max-quantity')}
      </label>
      <div class="col-sm-9">
        <input
          id="p-max-quantity"
          type="number"
          min="1"
          step="1"
          class="form-control"
          class:is-invalid={errors.maxQuantityPerOrder}
          data-field="maxQuantityPerOrder"
          disabled={quantityLocked}
          placeholder={$_('pages.create-product.limits.no-limit')}
          bind:value={product.maxQuantityPerOrder} />
        {#if quantityLocked}
          <div class="form-text">{$_('pages.create-product.limits.max-quantity-locked')}</div>
        {/if}
        {@render feedback('maxQuantityPerOrder')}
      </div>
    </div>

    <div class="row mb-3 align-items-center">
      <label class="col-sm-3 col-form-label" for="p-cooldown">
        {$_('pages.create-product.limits.cooldown')}
      </label>
      <div class="col-sm-9">
        <div class="input-group" data-field="cooldownSeconds">
          <input
            id="p-cooldown"
            type="number"
            min="1"
            step="1"
            class="form-control"
            class:is-invalid={errors.cooldownSeconds}
            placeholder={$_('pages.create-product.limits.no-limit')}
            value={cooldownValue}
            oninput={(e) => setCooldown(e.currentTarget.value, cooldownUnit)} />
          <select
            class="form-select flex-grow-0 w-auto"
            aria-label={$_('pages.create-product.limits.cooldown-unit')}
            value={cooldownUnit}
            onchange={(e) => setCooldown(cooldownValue, e.currentTarget.value)}>
            {#each COOLDOWN_UNITS as unit (unit)}
              <option value={unit}>{$_(`pages.create-product.units.${unit}`)}</option>
            {/each}
          </select>
        </div>
        {@render feedback('cooldownSeconds')}
      </div>
    </div>

    <hr class="my-4 opacity-25" />

    <div class="row mb-3 align-items-center">
      <label class="col-sm-3 col-form-label" for="p-required-switch">
        {$_('pages.create-product.required-products-switch')}
      </label>
      <div class="col-sm-9">
        <div class="form-check form-switch m-0">
          <input
            class="form-check-input"
            type="checkbox"
            role="switch"
            id="p-required-switch"
            checked={hasRequiredProducts}
            onchange={onRequiredChange} />
        </div>
      </div>
    </div>

    {#if hasRequiredProducts}
      <div class="row mb-3">
        <div class="col-sm-3 col-form-label">{$_('pages.create-product.required-products')}</div>
        <div class="col-sm-9">
          <ProductSelector bind:selected={product.requiredProducts} multiple={true} />
          <div class="form-text small mt-2">
            {$_('pages.create-product.required-products-help')}
          </div>
        </div>
      </div>

      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="p-require-one">
          {$_('pages.create-product.require-one')}
        </label>
        <div class="col-sm-9">
          <div class="form-check form-switch m-0 d-flex align-items-center gap-2">
            <input
              class="form-check-input"
              type="checkbox"
              role="switch"
              id="p-require-one"
              bind:checked={product.requireOnlyOne} />
            <span>{$_('pages.create-product.require-one-help')}</span>
          </div>
        </div>
      </div>
    {/if}

    <hr class="my-4 opacity-25" />

    <div class="row mb-3 align-items-center">
      <label class="col-sm-3 col-form-label" for="p-permission-switch">
        {$_('pages.create-product.permission-switch')}
      </label>
      <div class="col-sm-9">
        <div class="form-check form-switch m-0">
          <input
            class="form-check-input"
            type="checkbox"
            role="switch"
            id="p-permission-switch"
            checked={hasPermission}
            onchange={onPermissionChange} />
        </div>
      </div>
    </div>

    {#if hasPermission}
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="p-permission-node">
          {$_('pages.create-product.permission-node')}
        </label>
        <div class="col-sm-9">
          <input
            type="text"
            id="p-permission-node"
            data-field="requiredPermission"
            class="form-control"
            class:is-invalid={errors.requiredPermission}
            placeholder={$_('pages.create-product.permission-placeholder')}
            bind:value={product.requiredPermission} />
          {@render feedback('requiredPermission')}
        </div>
      </div>
    {/if}
  </div>
</div>

{#snippet feedback(key)}
  {#if errors[key]}
    <div class="invalid-feedback d-block">{$_(extraErrorKey(errors[key]))}</div>
  {/if}
{/snippet}

<script>
  import { untrack } from 'svelte';
  import { _ } from '../../../i18n';
  import ProductSelector from '../ProductSelector.svelte';
  import {
    COOLDOWN_UNITS,
    extraErrorKey,
    joinCooldown,
    maxQuantityLocked,
    splitCooldown,
  } from './extras.js';

  // Props contract of every tab: product (bindable), errors (dotted path -> code), ctx, products.
  let { product = $bindable(), errors = {} } = $props();

  let hasRequiredProducts = $state((product.requiredProducts ?? []).length > 0);
  let hasPermission = $state(product.requiredPermission !== '');

  // maxQuantityPerOrder is fixed to 1 for timed / subscription products and tiered categories (13 §8.7);
  // the value shown is the value sent, so the per-unit check of the Actions tab sees the same number.
  const quantityLocked = $derived(maxQuantityLocked(product));
  $effect(() => {
    if (quantityLocked && product.maxQuantityPerOrder !== 1) product.maxQuantityPerOrder = 1;
  });

  // cooldown is stored in seconds and edited as a number plus a unit; a replaced product (refresh
  // after a save) is re-split, an unfinished entry (NaN) is left alone
  const initialCooldown = untrack(() => splitCooldown(product.cooldownSeconds));
  let cooldownValue = $state(initialCooldown.value);
  let cooldownUnit = $state(initialCooldown.unit);

  $effect(() => {
    const seconds = product.cooldownSeconds;
    const own = joinCooldown(
      untrack(() => cooldownValue),
      untrack(() => cooldownUnit),
    );
    if (Object.is(own, seconds) || (own === null && seconds === null)) return;
    const split = splitCooldown(seconds);
    cooldownValue = split.value;
    cooldownUnit = split.unit;
  });

  function setCooldown(value, unit) {
    cooldownValue = value;
    cooldownUnit = unit;
    product.cooldownSeconds = joinCooldown(value, unit);
  }

  function onRequiredChange(event) {
    hasRequiredProducts = event.currentTarget.checked;
    if (!hasRequiredProducts) {
      product.requiredProducts = [];
      product.requireOnlyOne = false;
    }
  }

  function onPermissionChange(event) {
    hasPermission = event.currentTarget.checked;
    if (!hasPermission) product.requiredPermission = '';
  }
</script>
