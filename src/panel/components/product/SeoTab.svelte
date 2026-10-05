<div class="card animate__animated animate__fadeIn">
  <div class="card-body p-4 vstack gap-3">
    <div>
      <input
        type="text"
        class="form-control"
        class:is-invalid={errors.metaTitle}
        maxlength="255"
        autocomplete="off"
        data-field="metaTitle"
        placeholder={product.name || $_('pages.create-product.seo.title')}
        aria-label={$_('pages.create-product.seo.title')}
        bind:value={product.metaTitle} />
      <div class="d-flex justify-content-between">
        <div>
          {#if errors.metaTitle}
            <div class="invalid-feedback d-block">{$_(extraErrorKey(errors.metaTitle))}</div>
          {/if}
        </div>
        <small class="text-body-secondary">{(product.metaTitle ?? '').length} / 255</small>
      </div>
    </div>

    <div>
      <textarea
        class="form-control"
        class:is-invalid={errors.metaDescription}
        rows="3"
        maxlength="512"
        autocomplete="off"
        data-field="metaDescription"
        placeholder={product.shortDescription || $_('pages.create-product.seo.description')}
        aria-label={$_('pages.create-product.seo.description')}
        bind:value={product.metaDescription}></textarea>
      <div class="d-flex justify-content-between">
        <div>
          {#if errors.metaDescription}
            <div class="invalid-feedback d-block">
              {$_(extraErrorKey(errors.metaDescription))}
            </div>
          {/if}
        </div>
        <small class="text-body-secondary">{(product.metaDescription ?? '').length} / 512</small>
      </div>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { extraErrorKey } from './extras.js';

  // Props contract of every tab: product (bindable), errors (dotted path -> code).
  let { product = $bindable(), errors = {} } = $props();
</script>
