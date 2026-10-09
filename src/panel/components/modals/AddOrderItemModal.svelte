<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.add-order-item.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <ProductSelector {products} bind:selected={selectedId} />

          {#if loadError}
            <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
              <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
              <div>
                <b>{$_('common.load-error-title')}</b>
                <div>{$_(errorKey(loadError))}</div>
                <button
                  class="btn alert-btn mt-2"
                  type="button"
                  onclick={() => loadProduct(selectedId)}>
                  {$_('common.try-again')}
                </button>
              </div>
            </div>
          {:else if loading}
            <div class="text-center text-body-secondary">
              <span class="spinner-border spinner-border-sm me-1" role="status"></span>
              {$_('common.loading')}
            </div>
          {:else if product}
            {#if product.hasVariants}
              <select
                class="form-select"
                class:is-invalid={errors.variant}
                aria-label={$_('modals.add-order-item.variant')}
                bind:value={variantId}>
                <option value="">{$_('modals.add-order-item.variant')}</option>
                {#each variants as variant (variant.id)}
                  <option value={variant.id}>{variant.name}</option>
                {/each}
              </select>
            {/if}

            <input
              class="form-control"
              class:is-invalid={errors.quantity}
              type="number"
              min="1"
              step="1"
              placeholder={$_('modals.add-order-item.quantity')}
              aria-label={$_('modals.add-order-item.quantity')}
              bind:value={quantity} />
            {#if errors.quantity === 'MAX_QUANTITY'}
              <div class="text-danger">
                {$_('modals.add-order-item.max-quantity', {
                  values: { max: product.maxQuantityPerOrder },
                })}
              </div>
            {/if}

            {#each product.fields ?? [] as field (field.fieldKey)}
              {#if field.type === 'CHECKBOX'}
                <div class="form-check">
                  <input
                    class="form-check-input"
                    class:is-invalid={errors.fields?.[field.fieldKey]}
                    type="checkbox"
                    id="add-order-field-{field.fieldKey}"
                    bind:checked={values[field.fieldKey]} />
                  <label class="form-check-label" for="add-order-field-{field.fieldKey}">
                    {field.label}{field.required ? ' *' : ''}
                  </label>
                </div>
              {:else if field.type === 'SELECT'}
                <select
                  class="form-select"
                  class:is-invalid={errors.fields?.[field.fieldKey]}
                  aria-label={field.label}
                  bind:value={values[field.fieldKey]}>
                  <option value="">{field.label}{field.required ? ' *' : ''}</option>
                  {#each field.options ?? [] as option (option.value)}
                    <option value={option.value}>{option.label}</option>
                  {/each}
                </select>
              {:else if field.type === 'NUMBER'}
                <input
                  class="form-control"
                  class:is-invalid={errors.fields?.[field.fieldKey]}
                  type="number"
                  step="1"
                  min={field.minValue ?? undefined}
                  max={field.maxValue ?? undefined}
                  placeholder={(field.placeholder || field.label) + (field.required ? ' *' : '')}
                  aria-label={field.label}
                  bind:value={values[field.fieldKey]} />
              {:else}
                <input
                  class="form-control"
                  class:is-invalid={errors.fields?.[field.fieldKey]}
                  type={field.type === 'EMAIL' ? 'email' : 'text'}
                  placeholder={(field.placeholder || field.label) + (field.required ? ' *' : '')}
                  aria-label={field.label}
                  bind:value={values[field.fieldKey]} />
              {/if}
            {/each}

            {#if needsServer}
              <select
                class="form-select"
                class:is-invalid={errors.serverId}
                aria-label={$_('modals.add-order-item.server')}
                bind:value={serverId}>
                <option value="">{$_('modals.add-order-item.server')}</option>
                {#each serverChoices as server (server.id)}
                  <option value={server.id}>{server.name}</option>
                {/each}
              </select>
            {/if}
          {/if}
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={!product || loading}>
            {$_('modals.add-order-item.add')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { _ } from '../../../i18n';
  import ProductSelector from '../ProductSelector.svelte';
  import { call, errorKey } from '../../utils/api.js';
  import { showModal, hideModal } from '../order-detail/send.js';
  import {
    activeVariants,
    buildLine,
    fieldInitial,
    needsServerChoice,
    serverOptions,
  } from '../create-order/model.js';

  // products: GET /products/simple rows; servers: GET /servers; force: lifts the quantity maximum;
  // onAdd(line, { name, variantName }) receives the validated CartLine.
  let { products = [], servers = [], force = false, onAdd = () => {} } = $props();

  let modalElement = $state(null);
  let selectedId = $state(null);
  let product = $state(null);
  let loading = $state(false);
  let loadError = $state(null);
  let variantId = $state('');
  let quantity = $state(1);
  let values = $state({});
  let serverId = $state('');
  let errors = $state({ fields: {} });
  let sequence = 0;

  const variants = $derived(activeVariants(product));
  const needsServer = $derived(needsServerChoice(product));
  const serverChoices = $derived(serverOptions(product, servers));

  export function open() {
    selectedId = null;
    product = null;
    loadError = null;
    loading = false;
    variantId = '';
    quantity = 1;
    values = {};
    serverId = '';
    errors = { fields: {} };
    sequence++;
    showModal(modalElement);
  }

  async function loadProduct(id) {
    const mine = ++sequence;
    product = null;
    loadError = null;
    errors = { fields: {} };
    if (id === null || id === undefined) return;
    loading = true;
    const result = await call(api.panel.get({ path: `/products/${id}` }));
    if (mine !== sequence) return;
    loading = false;
    if (!result.ok) {
      loadError = result.error;
      return;
    }
    const loaded = result.body.product ?? result.body;
    product = loaded;
    variantId = '';
    quantity = 1;
    serverId = '';
    values = Object.fromEntries((loaded.fields ?? []).map((f) => [f.fieldKey, fieldInitial(f)]));
  }

  $effect(() => {
    const id = selectedId;
    loadProduct(id);
  });

  function submit(event) {
    event.preventDefault();
    if (!product) return;
    const built = buildLine(product, { variantId, quantity, values, serverId }, { servers, force });
    if (built.errors) {
      errors = built.errors;
      return;
    }
    const variant = variants.find((v) => Number(v.id) === built.line.variantId);
    onAdd(built.line, { name: product.name, variantName: variant?.name ?? null });
    hideModal(modalElement);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
