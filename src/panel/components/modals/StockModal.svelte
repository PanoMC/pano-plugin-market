<!-- Adjust Stock (13 §8.2, §8.11): used by the Pricing / Variants tabs and the products list.
     open({ productId, variants, variantId }) / onUpdated({ variantId, stock }). The modal is hidden
     before onUpdated runs. -->
<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.stock.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          {#if variants.length > 0}
            <select
              class="form-select"
              aria-label={$_('modals.stock.target')}
              bind:value={targetId}>
              <option value={0}>{$_('modals.stock.product')}</option>
              {#each variants as variant (variant.id)}
                <option value={variant.id}>{variant.name}</option>
              {/each}
            </select>
          {/if}

          <div class="btn-group" role="group" aria-label={$_('modals.stock.mode')}>
            {#each ['SET', 'ADJUST'] as option (option)}
              <input
                type="radio"
                class="btn-check"
                name="stock-mode"
                id="stock-mode-{option}"
                autocomplete="off"
                value={option}
                bind:group={mode} />
              <label class="btn btn-outline-secondary" for="stock-mode-{option}">
                {$_(`modals.stock.mode-${option}`)}
              </label>
            {/each}
          </div>

          {#if mode === 'SET'}
            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="stock-unlimited"
                bind:checked={unlimited} />
              <label class="form-check-label" for="stock-unlimited">
                {$_('common.unlimited')}
              </label>
            </div>
          {/if}

          {#if mode === 'ADJUST' || !unlimited}
            <div>
              <input
                type="number"
                step="1"
                min={mode === 'SET' ? 0 : undefined}
                class="form-control"
                class:is-invalid={invalid}
                placeholder={$_('modals.stock.value')}
                aria-label={$_('modals.stock.value')}
                bind:value />
              {#if invalid}
                <div class="invalid-feedback d-block">{$_(invalidKey)}</div>
              {/if}
            </div>
          {/if}
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_('common.save')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import { call, errorKey, marketPath } from '../../utils/api.js';

  let { onUpdated = () => {} } = $props();

  let modalElement = $state(null);
  let productId = $state(null);
  let variants = $state([]);
  let targetId = $state(0);
  let mode = $state('SET');
  let unlimited = $state(false);
  let value = $state(null);
  let saving = $state(false);
  let invalid = $state(false);
  let invalidKey = $state('pages.create-product.field-errors.INVALID');

  /** `variantId` preselects a variant row; 0 / null = the product itself. */
  export function open({ productId: id, variants: rows = [], variantId = 0 }) {
    productId = id;
    variants = rows.filter((v) => v.id);
    targetId = variantId ?? 0;
    mode = 'SET';
    unlimited = false;
    value = null;
    invalid = false;
    saving = false;
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
  }

  function fail(key) {
    invalid = true;
    invalidKey = key;
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || productId === null) return;
    invalid = false;

    let sent;
    if (mode === 'SET' && unlimited) sent = null;
    else {
      const n = Number(value);
      if (value === null || value === '' || !Number.isInteger(n))
        return fail('pages.create-product.field-errors.NOT_INTEGER');
      if (mode === 'SET' && n < 0) return fail('pages.create-product.field-errors.OUT_OF_RANGE');
      if (mode === 'ADJUST' && n === 0)
        return fail('pages.create-product.field-errors.OUT_OF_RANGE');
      sent = n;
    }

    saving = true;
    const body = { mode, value: sent };
    if (targetId) body.variantId = targetId;
    const result = await call(
      ApiUtil.post({ path: marketPath(`/products/${productId}/stock`), body }),
    );
    saving = false;

    if (!result.ok) {
      // A negative result of ADJUST is the only BAD_REQUEST of this endpoint: mark the value.
      if (result.error === 'BAD_REQUEST')
        return fail('pages.create-product.field-errors.OUT_OF_RANGE');
      showErrorToast($_(errorKey(result.error)));
      return;
    }
    showSuccessToast($_('modals.stock.success'));
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    onUpdated({ variantId: targetId || null, stock: result.body.stock ?? null });
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
