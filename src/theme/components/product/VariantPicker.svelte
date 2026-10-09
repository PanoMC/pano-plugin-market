{#if axes.length}
  <div
    id={ID_VARIANT}
    tabindex="-1"
    class="market-variant-picker vstack gap-2"
    aria-invalid={error ? 'true' : undefined}>
    {#each axes as axis, axisIndex (axis.key)}
      <fieldset>
        <legend class="market-variant-picker__label form-label fs-6 mb-1"
          >{axis.label || axis.key}</legend>
        <div class="d-flex flex-wrap gap-2">
          {#each axis.values as value, valueIndex (value.key)}
            {@const inputId = `mp-axis-${axisIndex}-${valueIndex}`}
            {@const selectable = isValueSelectable(
              product.variants,
              axes,
              selection,
              axis.key,
              value.key,
            )}
            <input
              type="radio"
              class="btn-check"
              name="mp-axis-{axisIndex}"
              id={inputId}
              autocomplete="off"
              checked={String(selection[axis.key]) === String(value.key)}
              disabled={!selectable}
              onchange={() =>
                onselect(selectValue(product.variants, axes, selection, axis.key, value.key))} />
            <label
              class="market-variant-picker__action btn btn-outline-primary btn-sm"
              for={inputId}>{value.label || value.key}</label>
          {/each}
        </div>
      </fieldset>
    {/each}
    {#if error}
      <div class="text-danger small" role="alert">{$_(`theme.errors.${error}`)}</div>
    {/if}
  </div>
{:else if product.variants?.length}
  <div class="market-variant-picker">
    <label class="market-variant-picker__variant-label form-label" for={ID_VARIANT}
      >{$_('theme.product.variant-label')}</label>
    <select
      id={ID_VARIANT}
      class={['market-variant-picker__select', 'form-select', error && 'is-invalid']}
      value={variantId == null ? '' : String(variantId)}
      aria-invalid={error ? 'true' : undefined}
      onchange={(event) =>
        onvariant(event.currentTarget.value === '' ? null : Number(event.currentTarget.value))}>
      <option value="" disabled>{$_('theme.product.variant-placeholder')}</option>
      {#each product.variants as variant (variant.id)}
        <option value={String(variant.id)}>
          {variant.name}{variant.inStock === false ? ` (${$_('theme.store.sold-out')})` : ''}
        </option>
      {/each}
    </select>
    {#if error}
      <div class="invalid-feedback">{$_(`theme.errors.${error}`)}</div>
    {/if}
  </div>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { isValueSelectable, selectValue, usableAxes } from '../../lib/variants.js';
  import { ID_VARIANT } from './productModel.js';

  const market = plugin('market');
  const _ = market._;

  /**
   * Variant choice (14 §9.2). `selection` is { axisKey: valueKey } for axis buttons, `variantId` the select's
   * choice; the page owns both. `error` is a code (VARIANT_REQUIRED) or null.
   */
  let {
    product,
    selection = {},
    variantId = null,
    error = null,
    onselect = () => {},
    onvariant = () => {},
  } = $props();

  const axes = $derived(usableAxes(product.variantOptions));
</script>
