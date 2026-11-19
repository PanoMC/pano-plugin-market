<div class="card animate__animated animate__fadeIn">
  <div class="card-body p-4">
    <div class="row mb-3 align-items-center">
      <label class="col-sm-3 col-form-label" for="p-meta-title">
        {$_('pages.create-product.seo.title')}
      </label>
      <div class="col-sm-9">
        <input
          id="p-meta-title"
          type="text"
          class="form-control"
          class:is-invalid={errors.metaTitle}
          maxlength="255"
          autocomplete="off"
          data-field="metaTitle"
          placeholder={product.name || $_('pages.create-product.seo.title')}
          bind:value={product.metaTitle} />
        <div class="d-flex justify-content-between">
          <div>{@render feedback('metaTitle')}</div>
          <small class="text-body-secondary">{(product.metaTitle ?? '').length} / 255</small>
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-sm-3 col-form-label" for="p-meta-description">
        {$_('pages.create-product.seo.description')}
      </label>
      <div class="col-sm-9">
        <textarea
          id="p-meta-description"
          class="form-control"
          class:is-invalid={errors.metaDescription}
          rows="3"
          maxlength="512"
          autocomplete="off"
          data-field="metaDescription"
          placeholder={product.shortDescription || $_('pages.create-product.seo.description')}
          bind:value={product.metaDescription}></textarea>
        <div class="d-flex justify-content-between">
          <div>{@render feedback('metaDescription')}</div>
          <small class="text-body-secondary">{(product.metaDescription ?? '').length} / 512</small>
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
  import { _ } from '../../../i18n';
  import { extraErrorKey } from './extras.js';

  // Props contract of every tab: product (bindable), errors (dotted path -> code).
  let { product = $bindable(), errors = {} } = $props();
</script>
