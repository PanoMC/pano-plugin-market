<!-- MPU-07 carries the three limits the old Restrictions tab had so nothing regresses;
     MPU-08 replaces this file with the full Limits tab (13 §8.7). -->
<div class="card animate__animated animate__fadeIn">
  <div class="card-body p-4">
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
        </div>
      </div>
    {/if}
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import ProductSelector from '../ProductSelector.svelte';

  let { product = $bindable(), errors = {} } = $props();

  let hasRequiredProducts = $state((product.requiredProducts ?? []).length > 0);
  let hasPermission = $state(product.requiredPermission !== '');

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
