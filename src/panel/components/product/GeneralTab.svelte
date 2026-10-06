<div class="card animate__animated animate__fadeIn">
  <div class="card-body d-flex flex-column gap-3">
    <div>
      <input
        type="text"
        class="form-control form-control-lg"
        class:is-invalid={errors.name}
        id="product-name"
        data-field="name"
        maxlength="255"
        autocomplete="off"
        placeholder={$_('pages.create-product.name-placeholder')}
        aria-label={$_('pages.create-product.name-placeholder')}
        bind:value={product.name}
        oninput={onNameInput} />
      {#if errors.name}
        <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.name))}</div>
      {/if}
    </div>

    <div>
      <div class="form-floating mb-0">
        <input
          type="text"
          class="form-control font-monospace"
          class:is-invalid={errors.slug}
          id="product-slug"
          data-field="slug"
          maxlength="255"
          autocomplete="off"
          placeholder={$_('pages.create-product.id-placeholder')}
          bind:value={product.slug}
          oninput={onSlugInput} />
        <label for="product-slug">{$_('pages.create-product.product-id')}</label>
      </div>
      {#if errors.slug}
        <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.slug))}</div>
      {/if}
    </div>

    <div>
      <textarea
        class="form-control"
        class:is-invalid={errors.shortDescription}
        id="product-short-description"
        data-field="shortDescription"
        rows="2"
        autocomplete="off"
        placeholder={$_('pages.create-product.short-description')}
        aria-label={$_('pages.create-product.short-description')}
        bind:value={product.shortDescription}></textarea>
      <div class="d-flex justify-content-between">
        <div>
          {#if errors.shortDescription}
            <div class="invalid-feedback d-block">
              {$_(fieldErrorKey(errors.shortDescription))}
            </div>
          {/if}
        </div>
        <small class="text-body-secondary">{(product.shortDescription ?? '').length} / 512</small>
      </div>
    </div>

    <div class="w-100 flex-grow-1 d-flex flex-column">
      <ClientEditor id="product-description" bind:content={product.description} />
    </div>

    <div class="form-check form-switch">
      <input
        class="form-check-input"
        type="checkbox"
        role="switch"
        id="product-sale-window"
        bind:checked={product.saleWindow} />
      <label class="form-check-label" for="product-sale-window">
        {$_('pages.create-product.sale-window')}
      </label>
    </div>

    {#if product.saleWindow}
      <div class="row g-2">
        <div class="col-md-6">
          <label for="product-duration-start" class="form-label mb-1 text-body-secondary">
            {$_('pages.create-product.start-date')}
          </label>
          <input
            type="datetime-local"
            id="product-duration-start"
            data-field="durationStart"
            class="form-control"
            class:is-invalid={errors.durationStart}
            bind:value={product.durationStart} />
        </div>
        <div class="col-md-6">
          <label for="product-duration-expiry" class="form-label mb-1 text-body-secondary">
            {$_('pages.create-product.end-date')}
          </label>
          <input
            type="datetime-local"
            id="product-duration-expiry"
            data-field="durationExpiry"
            class="form-control"
            class:is-invalid={errors.durationExpiry}
            bind:value={product.durationExpiry} />
          {#if errors.durationExpiry}
            <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors.durationExpiry))}</div>
          {/if}
        </div>
      </div>
    {/if}
  </div>
</div>

<script>
  import ClientEditor from '../ClientEditor.svelte';
  import { _ } from '../../../i18n';
  import { fieldErrorKey, slugify } from './model.js';

  let { product = $bindable(), errors = {} } = $props();

  // The slug follows the name until the admin edits it; a saved product never changes its slug on
  // its own (the URL is public).
  let slugTouched = $state(product.dbId !== null || product.slug !== '');

  function onNameInput(event) {
    if (!slugTouched) product.slug = slugify(event.currentTarget.value);
  }

  function onSlugInput(event) {
    if (event.currentTarget.value === '' && product.dbId === null) {
      slugTouched = false;
      product.slug = slugify(product.name);
    } else {
      slugTouched = true;
    }
  }
</script>
