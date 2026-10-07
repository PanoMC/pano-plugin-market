<div class="vstack gap-3 animate__animated animate__fadeIn">
  <div class="card">
    <div class="card-body vstack gap-2">
      <div class="form-check form-switch">
        <input
          class="form-check-input"
          type="checkbox"
          role="switch"
          id="product-has-variants"
          disabled={product.kind === 'CREDIT_PACK'}
          checked={product.hasVariants}
          onchange={onHasVariantsChange} />
        <label class="form-check-label" for="product-has-variants">
          {$_('pages.create-product.variants.enable')}
        </label>
      </div>
      <div class="form-text">
        {product.kind === 'CREDIT_PACK'
          ? $_('pages.create-product.variants.pack-locked')
          : $_('pages.create-product.variants.enable-help')}
      </div>
      {#if errors.variants}
        <div class="invalid-feedback d-block" data-field="variants">
          {$_(fieldErrorKey(errors.variants))}
        </div>
      {/if}
    </div>
  </div>

  {#if product.hasVariants}
    <div class="card">
      <CardHeader>
        <div slot="left">{$_('pages.create-product.variants.options')}</div>
        <div slot="right">
          <button
            type="button"
            class="btn btn-sm btn-link"
            disabled={axes.length >= MAX_AXES}
            onclick={addAxis}>
            <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
            {$_('pages.create-product.variants.add-axis')}
          </button>
        </div>
      </CardHeader>
      <div class="card-body vstack gap-3">
        {#if errors.variantOptions}
          <div class="invalid-feedback d-block" data-field="variantOptions">
            {$_(fieldErrorKey(errors.variantOptions))}
          </div>
        {/if}
        {#each axes as axis, ai (ai)}
          <div class="border rounded p-3 vstack gap-2">
            <div class="input-group">
              <input
                type="text"
                class="form-control"
                class:is-invalid={errors[`variantOptions.${ai}.label`]}
                data-field="variantOptions.{ai}.label"
                maxlength="255"
                autocomplete="off"
                placeholder={$_('pages.create-product.variants.axis-label')}
                aria-label={$_('pages.create-product.variants.axis-label')}
                value={axis.label}
                oninput={(e) => onAxisLabel(axis, e.currentTarget.value)} />
              <span class="input-group-text font-monospace small">{axis.key}</span>
              <button
                class="btn btn-outline-secondary"
                type="button"
                aria-label={$_('common.remove')}
                onclick={() => removeAxis(ai)}>
                <i class="fa-solid fa-xmark" aria-hidden="true"></i>
              </button>
            </div>
            {#if errors[`variantOptions.${ai}.label`]}
              <div class="invalid-feedback d-block">
                {$_(fieldErrorKey(errors[`variantOptions.${ai}.label`]))}
              </div>
            {/if}

            {#each axis.values as value, vi (vi)}
              <div class="input-group input-group-sm ms-3">
                <input
                  type="text"
                  class="form-control"
                  class:is-invalid={errors[`variantOptions.${ai}.values.${vi}.label`]}
                  data-field="variantOptions.{ai}.values.{vi}.label"
                  maxlength="255"
                  autocomplete="off"
                  placeholder={$_('pages.create-product.variants.value-label')}
                  aria-label={$_('pages.create-product.variants.value-label')}
                  value={value.label}
                  oninput={(e) => onValueLabel(axis, value, e.currentTarget.value)} />
                <span class="input-group-text font-monospace small">{value.key}</span>
                <button
                  class="btn btn-outline-secondary"
                  type="button"
                  aria-label={$_('common.remove')}
                  onclick={() => removeValue(axis, vi)}>
                  <i class="fa-solid fa-xmark" aria-hidden="true"></i>
                </button>
              </div>
            {/each}
            {#if errors[`variantOptions.${ai}.values`]}
              <div class="invalid-feedback d-block ms-3">
                {$_(fieldErrorKey(errors[`variantOptions.${ai}.values`]))}
              </div>
            {/if}
            <div>
              <button
                class="btn btn-link btn-sm px-0 ms-3"
                type="button"
                disabled={axis.values.length >= MAX_AXIS_VALUES}
                onclick={() => addValue(axis)}>
                <i class="fa-solid fa-plus me-1" aria-hidden="true"></i>
                {$_('pages.create-product.variants.add-value')}
              </button>
            </div>
          </div>
        {/each}

        <div class="d-flex flex-wrap align-items-center gap-3">
          <button type="button" class="btn btn-secondary" onclick={generate}>
            {$_('pages.create-product.variants.generate')}
          </button>
          {#if generateMessage}
            <span class="text-danger">{generateMessage}</span>
          {:else if generated}
            <span class="text-body-secondary">{generated}</span>
          {/if}
        </div>
      </div>
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.create-product.variants.title', {
            values: { count: product.variants.length },
          })}
        </div>
        <div slot="right">
          <button
            type="button"
            class="btn btn-sm btn-link"
            disabled={product.variants.length >= MAX_VARIANTS}
            onclick={addVariant}>
            <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
            {$_('pages.create-product.variants.add-variant')}
          </button>
        </div>
      </CardHeader>

      {#if product.variants.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.create-product.variants.name')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.create-product.variants.sku')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.create-product.variants.price')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.create-product.variants.compare-at')}
                </th>
                {#if showCredit}
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('pages.create-product.credit-price')}
                  </th>
                {/if}
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.create-product.variants.stock')}
                </th>
                {#if product.physical}
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('pages.create-product.variants.weight')}
                  </th>
                {/if}
                {#if showPeriod}
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('pages.create-product.variants.period-count')}
                  </th>
                {/if}
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
              </tr>
            </thead>
            <tbody>
              {#each product.variants as variant, i (variant._key)}
                {@const orphan = isOrphan(product.variantOptions, variant)}
                <tr>
                  <th class="align-middle" scope="row">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        aria-expanded="false"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                      </button>
                      <div
                        class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                        <button
                          type="button"
                          class="dropdown-item"
                          disabled={i === 0}
                          onclick={() => move(i, -1)}>
                          {$_('common.move-up')}
                        </button>
                        <button
                          type="button"
                          class="dropdown-item"
                          disabled={i === product.variants.length - 1}
                          onclick={() => move(i, 1)}>
                          {$_('common.move-down')}
                        </button>
                        <button type="button" class="dropdown-item" onclick={() => duplicate(i)}>
                          {$_('pages.create-product.variants.duplicate')}
                        </button>
                        {#if isEdit && variant.id}
                          <button
                            type="button"
                            class="dropdown-item"
                            onclick={() => onAdjustStock(variant)}>
                            {$_('pages.create-product.adjust-stock')}
                          </button>
                        {/if}
                        <button
                          type="button"
                          class="dropdown-item"
                          onclick={() => toggle(variant._key)}>
                          {$_('common.details')}
                        </button>
                        <button
                          type="button"
                          class="dropdown-item link-danger"
                          onclick={() => removeVariant(i)}>
                          {$_('common.remove')}
                        </button>
                      </div>
                    </div>
                  </th>
                  <td style="min-width: 12rem;">
                    <input
                      type="text"
                      class="form-control"
                      class:is-invalid={errors[`variants.${i}.name`]}
                      data-field="variants.{i}.name"
                      maxlength="255"
                      autocomplete="off"
                      placeholder={$_('pages.create-product.variants.name')}
                      aria-label={$_('pages.create-product.variants.name')}
                      bind:value={variant.name} />
                    {#if orphan}
                      <span class="badge text-bg-warning mt-1">
                        {$_('pages.create-product.variants.not-in-options')}
                      </span>
                    {/if}
                    {@render rowError(`variants.${i}.name`)}
                  </td>
                  <td style="min-width: 8rem;">
                    <input
                      type="text"
                      class="form-control"
                      class:is-invalid={errors[`variants.${i}.sku`]}
                      data-field="variants.{i}.sku"
                      maxlength="64"
                      autocomplete="off"
                      placeholder={$_('pages.create-product.variants.sku')}
                      aria-label={$_('pages.create-product.variants.sku')}
                      bind:value={variant.sku} />
                    {@render rowError(`variants.${i}.sku`)}
                  </td>
                  <td style="min-width: 9rem;">
                    <div data-field="variants.{i}.price">
                      <MoneyInput
                        bind:value={variant.price}
                        {exponent}
                        invalid={!!errors[`variants.${i}.price`]}
                        placeholder={$_('pages.create-product.variants.product-price')} />
                    </div>
                    {@render rowError(`variants.${i}.price`)}
                  </td>
                  <td style="min-width: 9rem;">
                    <div data-field="variants.{i}.compareAtPrice">
                      <MoneyInput
                        bind:value={variant.compareAtPrice}
                        {exponent}
                        invalid={!!errors[`variants.${i}.compareAtPrice`]}
                        placeholder={$_('pages.create-product.variants.compare-at')} />
                    </div>
                    {@render rowError(`variants.${i}.compareAtPrice`)}
                  </td>
                  {#if showCredit}
                    <td style="min-width: 8rem;">
                      <div data-field="variants.{i}.creditPrice">
                        <MoneyInput
                          bind:value={variant.creditPrice}
                          invalid={!!errors[`variants.${i}.creditPrice`]}
                          placeholder={$_('pages.create-product.variants.product-price')} />
                      </div>
                      {@render rowError(`variants.${i}.creditPrice`)}
                    </td>
                  {/if}
                  <td style="min-width: 7rem;">
                    {#if variant.id}
                      <span>{variant.stock === null ? $_('common.unlimited') : variant.stock}</span>
                    {:else}
                      <input
                        type="number"
                        min="0"
                        step="1"
                        class="form-control"
                        class:is-invalid={errors[`variants.${i}.stock`]}
                        data-field="variants.{i}.stock"
                        placeholder={$_('common.unlimited')}
                        aria-label={$_('pages.create-product.variants.stock')}
                        bind:value={variant.stock} />
                      {@render rowError(`variants.${i}.stock`)}
                    {/if}
                  </td>
                  {#if product.physical}
                    <td style="min-width: 7rem;">
                      <input
                        type="number"
                        min="1"
                        step="1"
                        class="form-control"
                        class:is-invalid={errors[`variants.${i}.weightGrams`]}
                        data-field="variants.{i}.weightGrams"
                        placeholder={$_('pages.create-product.variants.product-weight')}
                        aria-label={$_('pages.create-product.variants.weight')}
                        bind:value={variant.weightGrams} />
                      {@render rowError(`variants.${i}.weightGrams`)}
                    </td>
                  {/if}
                  {#if showPeriod}
                    <td style="min-width: 7rem;">
                      <input
                        type="number"
                        min="1"
                        step="1"
                        class="form-control"
                        class:is-invalid={errors[`variants.${i}.periodCount`]}
                        data-field="variants.{i}.periodCount"
                        placeholder={String(product.periodCount ?? '')}
                        aria-label={$_('pages.create-product.variants.period-count')}
                        bind:value={variant.periodCount} />
                      {@render rowError(`variants.${i}.periodCount`)}
                    </td>
                  {/if}
                  <td class="align-middle">
                    <div class="form-check form-switch m-0">
                      <input
                        class="form-check-input"
                        type="checkbox"
                        role="switch"
                        aria-label={$_('common.status')}
                        checked={variant.status === 'ACTIVE'}
                        onchange={(e) =>
                          (variant.status = e.currentTarget.checked ? 'ACTIVE' : 'INACTIVE')} />
                    </div>
                  </td>
                </tr>
                {#if open[variant._key] || errorRows.has(i)}
                  <tr>
                    <td colspan={columnCount}>
                      <div class="row g-3 p-2">
                        <div class="col-lg-6 vstack gap-2">
                          <div class="fw-medium">
                            {$_('pages.create-product.variants.attributes')}
                          </div>
                          <KeyValueList
                            bind:rows={variant.attributes}
                            max={MAX_ATTRIBUTES}
                            keyPattern={ATTRIBUTE_KEY_PATTERN}
                            keyPlaceholder={$_('pages.create-product.variants.attribute-key')}
                            valuePlaceholder={$_(
                              'pages.create-product.variants.attribute-value',
                            )} />
                          {#if attributeError(i)}
                            <div
                              class="invalid-feedback d-block"
                              data-field="variants.{i}.attributes">
                              {$_(fieldErrorKey(attributeError(i)))}
                            </div>
                          {/if}
                          <div class="form-text">
                            {$_('pages.create-product.variants.attributes-help')}
                          </div>
                        </div>
                        <div class="col-lg-6 vstack gap-2">
                          <div class="fw-medium">{$_('pages.create-product.variants.image')}</div>
                          {#if variant.imageFile}
                            <div class="d-flex align-items-center gap-2">
                              <span class="text-truncate">{variant.imageFile.name}</span>
                              <button
                                type="button"
                                class="btn btn-sm btn-link link-danger"
                                onclick={() => clearImage(variant)}>
                                {$_('common.remove')}
                              </button>
                            </div>
                          {:else if variant.imageFileName && !variant.removeImage}
                            <div class="d-flex align-items-center gap-3">
                              <img
                                class="rounded border object-fit-cover"
                                width="64"
                                height="64"
                                src="{base}/api/panel/market/products/image/{variant.imageFileName}"
                                alt={$_('pages.create-product.variants.image')} />
                              <button
                                type="button"
                                class="btn btn-sm btn-link link-danger"
                                onclick={() => clearImage(variant)}>
                                {$_('common.remove')}
                              </button>
                            </div>
                          {:else}
                            <DragAndDropZone
                              style="aspect-ratio: 21/9;"
                              icon="fas fa-image fa-2x"
                              title={$_('pages.create-product.variants.image-title')}
                              accept={['image/png', 'image/jpeg', 'image/gif', 'image/webp']}
                              maxFileSize={5 * 1024 * 1024}
                              on:drop={(e) => setImage(variant, e.detail)}
                              on:error={() =>
                                showErrorToast($_('pages.create-product.variants.image-error'))} />
                          {/if}
                        </div>
                        {#if multi}
                          <div class="col-12 vstack gap-2">
                            <div class="fw-medium">
                              {$_('pages.create-product.currency-prices')}
                            </div>
                            <PriceGrid
                              bind:rows={variant.prices}
                              {ctx}
                              {errors}
                              path="variants.{i}.prices" />
                          </div>
                        {/if}
                      </div>
                    </td>
                  </tr>
                {/if}
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  {/if}
</div>

<ConfirmModal bind:this={confirmModal} />

{#snippet rowError(path)}
  {#if errors[path]}
    <div class="invalid-feedback d-block">{$_(fieldErrorKey(errors[path]))}</div>
  {/if}
{/snippet}

<script>
  import { CardHeader, DragAndDropZone, NoContent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { _, showErrorToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import KeyValueList from '../KeyValueList.svelte';
  import MoneyInput from '../MoneyInput.svelte';
  import PriceGrid from './PriceGrid.svelte';
  import {
    ATTRIBUTE_KEY_PATTERN,
    MAX_ATTRIBUTES,
    currencyExponent,
    ensurePriceRows,
    errorDetailRows,
    fieldErrorKey,
    isMulti,
  } from './model.js';
  import {
    MAX_AXES,
    MAX_AXIS_VALUES,
    MAX_VARIANTS,
    blankVariant,
    duplicateVariant,
    generateVariants,
    isOrphan,
    keyFromLabel,
    moveItem,
    renameAxisKey,
    renameValueKey,
    renumber,
    uniqueKey,
    uniqueName,
  } from '../../utils/variants.js';

  let {
    product = $bindable(),
    errors = {},
    ctx = null,
    isEdit = false,
    onAdjustStock = () => {},
  } = $props();

  let confirmModal = $state(null);
  let open = $state({});
  let generateMessage = $state('');
  let generated = $state('');

  const axes = $derived(product.variantOptions);
  const exponent = $derived(currencyExponent(ctx));
  const multi = $derived(isMulti(ctx));
  const showCredit = $derived(!!ctx?.creditsEnabled && product.kind !== 'CREDIT_PACK');
  const showPeriod = $derived(product.billingMode !== 'ONE_TIME');
  const columnCount = $derived(
    5 + (showCredit ? 1 : 0) + (product.physical ? 1 : 0) + (showPeriod ? 1 : 0) + 1,
  );

  function onHasVariantsChange(event) {
    const input = event.currentTarget;
    if (input.checked) {
      product.hasVariants = true;
      return;
    }
    if (product.variants.length === 0) {
      product.hasVariants = false;
      return;
    }
    // Keep the switch on until the admin confirms: the rows would be dropped from the payload.
    input.checked = true;
    confirmModal?.open({
      icon: 'fa-solid fa-triangle-exclamation',
      title: $_('pages.create-product.variants.disable-title'),
      description: $_('pages.create-product.variants.disable-description'),
      confirmLabel: $_('pages.create-product.variants.disable-confirm'),
      variant: 'danger',
      onConfirm: () => {
        product.variants = [];
        product.hasVariants = false;
      },
    });
  }

  // ---- option axes ----

  function addAxis() {
    if (axes.length >= MAX_AXES) return;
    product.variantOptions = [
      ...axes,
      {
        key: uniqueKey(
          'option',
          axes.map((a) => a.key),
        ),
        label: '',
        locked: false,
        values: [],
      },
    ];
  }

  function removeAxis(index) {
    const removed = axes[index];
    product.variantOptions = axes.filter((_axis, i) => i !== index);
    // The variants forget the removed axis; their combination no longer exists.
    product.variants = product.variants.map((variant) => {
      if (!variant.optionValues || !(removed.key in variant.optionValues)) return variant;
      const { [removed.key]: _gone, ...rest } = variant.optionValues;
      return { ...variant, optionValues: rest };
    });
  }

  function onAxisLabel(axis, label) {
    axis.label = label;
    if (axis.locked) return;
    const others = axes.filter((a) => a !== axis).map((a) => a.key);
    const next = uniqueKey(keyFromLabel(label) || 'option', others);
    if (next !== axis.key) {
      product.variants = renameAxisKey(product.variants, axis.key, next);
      axis.key = next;
    }
  }

  function addValue(axis) {
    if (axis.values.length >= MAX_AXIS_VALUES) return;
    axis.values = [
      ...axis.values,
      {
        key: uniqueKey(
          'value',
          axis.values.map((v) => v.key),
        ),
        label: '',
        locked: false,
      },
    ];
  }

  function removeValue(axis, index) {
    axis.values = axis.values.filter((_value, i) => i !== index);
  }

  function onValueLabel(axis, value, label) {
    value.label = label;
    if (value.locked) return;
    const others = axis.values.filter((v) => v !== value).map((v) => v.key);
    const next = uniqueKey(keyFromLabel(label) || 'value', others);
    if (next !== value.key) {
      product.variants = renameValueKey(product.variants, axis.key, value.key, next);
      value.key = next;
    }
  }

  function generate() {
    generateMessage = '';
    generated = '';
    const result = generateVariants(
      $state.snapshot(product.variantOptions),
      $state.snapshot(product.variants),
    );
    if (result.error === 'TOO_MANY') {
      generateMessage = $_('pages.create-product.variants.too-many', {
        values: { count: result.count, max: MAX_VARIANTS },
      });
      return;
    }
    if (result.error) {
      generateMessage = $_('pages.create-product.variants.no-options');
      return;
    }
    product.variants = result.variants.map((variant) => ({
      ...variant,
      prices: variant.prices?.length ? variant.prices : ensurePriceRows([], ctx),
    }));
    generated = $_('pages.create-product.variants.generated', {
      values: { added: result.added, kept: result.kept, orphans: result.orphans },
    });
  }

  // ---- variant rows ----

  function addVariant() {
    if (product.variants.length >= MAX_VARIANTS) return;
    product.variants = [
      ...product.variants,
      blankVariant({ position: product.variants.length, prices: ensurePriceRows([], ctx) }),
    ];
  }

  function move(index, delta) {
    product.variants = renumber(moveItem(product.variants, index, delta));
  }

  function duplicate(index) {
    if (product.variants.length >= MAX_VARIANTS) return;
    const copy = duplicateVariant($state.snapshot(product.variants[index]));
    copy.name = uniqueName(
      copy.name,
      product.variants.map((v) => v.name),
    );
    const rows = [...product.variants];
    rows.splice(index + 1, 0, copy);
    product.variants = renumber(rows);
  }

  function removeVariant(index) {
    product.variants = renumber(product.variants.filter((_v, i) => i !== index));
  }

  function toggle(key) {
    open[key] = !open[key];
  }

  // rows whose error sits in the Details row are rendered open so the input exists to be marked
  const errorRows = $derived(errorDetailRows(errors));

  function attributeError(index) {
    const prefix = `variants.${index}.attributes`;
    const path = Object.keys(errors).find((p) => p === prefix || p.startsWith(`${prefix}.`));
    return path ? errors[path] : null;
  }

  function setImage(variant, file) {
    variant.imageFile = file;
    variant.imageToken = `${file.name}:${file.size}:${file.lastModified}`;
    variant.removeImage = false;
  }

  function clearImage(variant) {
    if (variant.imageFile) {
      variant.imageFile = null;
      variant.imageToken = '';
    } else if (variant.imageFileName) {
      variant.removeImage = true;
    }
  }
</script>
