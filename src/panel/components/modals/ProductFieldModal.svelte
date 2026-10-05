<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_(
            index === null ? 'modals.product-field.title-add' : 'modals.product-field.title-edit',
          )}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          <div>
            <input
              type="text"
              class="form-control"
              class:is-invalid={errors.label}
              maxlength="255"
              autocomplete="off"
              data-field="label"
              placeholder={$_('modals.product-field.label')}
              aria-label={$_('modals.product-field.label')}
              bind:value={draft.label}
              oninput={onLabelInput} />
            {@render feedback('label')}
          </div>

          <div>
            <input
              type="text"
              class="form-control font-monospace"
              class:is-invalid={errors.fieldKey}
              maxlength="32"
              autocomplete="off"
              data-field="fieldKey"
              readonly={!draft.isNew}
              placeholder={$_('modals.product-field.field-key')}
              aria-label={$_('modals.product-field.field-key')}
              bind:value={draft.fieldKey}
              oninput={() => (keyTouched = true)} />
            {@render feedback('fieldKey')}
            {#if !draft.isNew}
              <div class="form-text">{$_('modals.product-field.key-locked')}</div>
            {/if}
          </div>

          <div class="row g-3 align-items-center">
            <div class="col-sm-6">
              <label class="form-label mb-1" for="field-type"
                >{$_('modals.product-field.type')}</label>
              <select
                id="field-type"
                class="form-select"
                data-field="type"
                value={draft.type}
                onchange={(e) => setType(e.currentTarget.value)}>
                {#each FIELD_TYPES as type (type)}
                  <option value={type}>{$_(`modals.product-field.types.${type}`)}</option>
                {/each}
              </select>
            </div>
            <div class="col-sm-6">
              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="field-required"
                  bind:checked={draft.required} />
                <label class="form-check-label" for="field-required">
                  {$_('modals.product-field.required')}
                </label>
              </div>
              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="field-usable"
                  disabled={draft.type === 'CHECKBOX'}
                  checked={draft.type !== 'CHECKBOX' && draft.usableInCommands}
                  onchange={(e) => (draft.usableInCommands = e.currentTarget.checked)} />
                <label class="form-check-label" for="field-usable">
                  {$_('modals.product-field.usable-in-commands')}
                </label>
              </div>
            </div>
          </div>

          <div>
            <input
              type="text"
              class="form-control"
              class:is-invalid={errors.helpText}
              maxlength="512"
              autocomplete="off"
              data-field="helpText"
              placeholder={$_('modals.product-field.help-text')}
              aria-label={$_('modals.product-field.help-text')}
              bind:value={draft.helpText} />
            {@render feedback('helpText')}
          </div>

          <div class="row g-3">
            <div class="col-sm-6">
              <input
                type="text"
                class="form-control"
                class:is-invalid={errors.placeholder}
                maxlength="255"
                autocomplete="off"
                data-field="placeholder"
                placeholder={$_('modals.product-field.placeholder')}
                aria-label={$_('modals.product-field.placeholder')}
                bind:value={draft.placeholder} />
              {@render feedback('placeholder')}
            </div>
            <div class="col-sm-6">
              {#if draft.type === 'CHECKBOX'}
                <select
                  class="form-select"
                  class:is-invalid={errors.defaultValue}
                  data-field="defaultValue"
                  aria-label={$_('modals.product-field.default-value')}
                  bind:value={draft.defaultValue}>
                  <option value="">{$_('modals.product-field.default-none')}</option>
                  <option value="true">{$_('common.yes')}</option>
                  <option value="false">{$_('common.no')}</option>
                </select>
              {:else if draft.type === 'SELECT'}
                <select
                  class="form-select"
                  class:is-invalid={errors.defaultValue}
                  data-field="defaultValue"
                  aria-label={$_('modals.product-field.default-value')}
                  bind:value={draft.defaultValue}>
                  <option value="">{$_('modals.product-field.default-none')}</option>
                  {#each optionRows.filter((row) => row.key !== '') as row (row.key)}
                    <option value={row.key}>{row.value || row.key}</option>
                  {/each}
                </select>
              {:else}
                <input
                  type="text"
                  class="form-control"
                  class:is-invalid={errors.defaultValue}
                  maxlength="255"
                  autocomplete="off"
                  data-field="defaultValue"
                  placeholder={$_('modals.product-field.default-value')}
                  aria-label={$_('modals.product-field.default-value')}
                  bind:value={draft.defaultValue} />
              {/if}
              {@render feedback('defaultValue')}
            </div>
          </div>

          {#if draft.type === 'SELECT'}
            <div data-field="options">
              <div class="form-label mb-1">{$_('modals.product-field.options')}</div>
              <KeyValueList
                bind:rows={optionRows}
                max={MAX_OPTIONS}
                keyPattern={OPTION_VALUE_PATTERN}
                keyPlaceholder={$_('modals.product-field.option-value')}
                valuePlaceholder={$_('modals.product-field.option-label')} />
              {@render feedback('options')}
              {#each optionErrorKeys as key (key)}
                <div class="invalid-feedback d-block">{$_(fieldRowErrorKey(errors[key]))}</div>
              {/each}
            </div>
          {/if}

          {#if draft.type === 'TEXT'}
            <div>
              <input
                type="text"
                class="form-control font-monospace"
                class:is-invalid={errors.pattern}
                maxlength="255"
                autocomplete="off"
                data-field="pattern"
                placeholder={$_('modals.product-field.pattern')}
                aria-label={$_('modals.product-field.pattern')}
                bind:value={draft.pattern} />
              {@render feedback('pattern')}
            </div>
            <div class="row g-3">
              <div class="col-sm-6">
                <input
                  type="number"
                  min="0"
                  max="128"
                  step="1"
                  class="form-control"
                  class:is-invalid={errors.minLength}
                  data-field="minLength"
                  placeholder={$_('modals.product-field.min-length')}
                  aria-label={$_('modals.product-field.min-length')}
                  bind:value={draft.minLength} />
                {@render feedback('minLength')}
              </div>
              <div class="col-sm-6">
                <input
                  type="number"
                  min="0"
                  max="128"
                  step="1"
                  class="form-control"
                  class:is-invalid={errors.maxLength}
                  data-field="maxLength"
                  placeholder={$_('modals.product-field.max-length')}
                  aria-label={$_('modals.product-field.max-length')}
                  bind:value={draft.maxLength} />
                {@render feedback('maxLength')}
              </div>
            </div>
          {/if}

          {#if draft.type === 'NUMBER'}
            <div class="row g-3">
              <div class="col-sm-6">
                <input
                  type="number"
                  step="1"
                  class="form-control"
                  class:is-invalid={errors.minValue}
                  data-field="minValue"
                  placeholder={$_('modals.product-field.min-value')}
                  aria-label={$_('modals.product-field.min-value')}
                  bind:value={draft.minValue} />
                {@render feedback('minValue')}
              </div>
              <div class="col-sm-6">
                <input
                  type="number"
                  step="1"
                  class="form-control"
                  class:is-invalid={errors.maxValue}
                  data-field="maxValue"
                  placeholder={$_('modals.product-field.max-value')}
                  aria-label={$_('modals.product-field.max-value')}
                  bind:value={draft.maxValue} />
                {@render feedback('maxValue')}
              </div>
            </div>
          {/if}

          <div class="border rounded p-3">
            <div class="text-body-secondary small mb-2">{$_('common.preview')}</div>
            <FieldPreview field={previewField} />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit">{$_('common.save')}</button>
        </div>
      </form>
    </div>
  </div>
</div>

{#snippet feedback(key)}
  {#if errors[key]}
    <div class="invalid-feedback d-block">{$_(fieldRowErrorKey(errors[key]))}</div>
  {/if}
{/snippet}

<script>
  import { _ } from '../../../i18n';
  import { tick } from 'svelte';
  import KeyValueList from '../KeyValueList.svelte';
  import FieldPreview from '../product/FieldPreview.svelte';
  import {
    FIELD_TYPES,
    MAX_OPTIONS,
    OPTION_VALUE_PATTERN,
    blankField,
    fieldRowErrorKey,
    suggestFieldKey,
    validateField,
  } from '../product/fields.js';

  // A form modal for one custom field (13 §8.5). open({ field, index, siblings }): `field` is the row
  // to edit (null = a new one), `index` its position, `siblings` the other rows (for key uniqueness).
  // onSave(field, index) receives the draft in the state shape; the caller stores it.
  let { onSave = () => {} } = $props();

  let modalElement = $state(null);
  let draft = $state(blankField());
  let optionRows = $state([]);
  let index = $state(null);
  let siblings = $state([]);
  let errors = $state({});
  let keyTouched = $state(false);

  const optionErrorKeys = $derived(Object.keys(errors).filter((key) => key.startsWith('options.')));
  // The preview shows the draft with the option rows folded in.
  const previewField = $derived({
    ...draft,
    options: optionRows.map((row) => ({ value: row.key, label: row.value })),
  });

  export function open({ field = null, index: at = null, siblings: others = [] } = {}) {
    const source = field ?? blankField();
    draft = { ...blankField(), ...JSON.parse(JSON.stringify(source)) };
    // a row without the flag came from the server: its key is locked (01 §2.5, 13 §8.5)
    draft.isNew = field === null ? true : source.isNew === true;
    optionRows = (draft.options ?? []).map((o) => ({ key: o.value, value: o.label }));
    index = at;
    siblings = others;
    errors = {};
    keyTouched = field !== null;
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
  }

  function onLabelInput() {
    if (draft.isNew && !keyTouched) draft.fieldKey = suggestFieldKey(draft.label);
  }

  function setType(type) {
    draft.type = type;
    if (type === 'CHECKBOX') draft.usableInCommands = false;
    // a default that cannot exist in the new type would only confuse
    draft.defaultValue = '';
  }

  async function submit(event) {
    event.preventDefault();
    const candidate = {
      ...draft,
      options: optionRows.map((row) => ({ value: row.key, label: row.value })),
    };
    errors = validateField(candidate, siblings);
    const first = Object.keys(errors)[0];
    if (first) {
      await tick();
      const holder = modalElement?.querySelector(`[data-field="${first.split('.')[0]}"]`);
      const control = holder?.matches('input,select')
        ? holder
        : holder?.querySelector('input,select');
      control?.focus();
      return;
    }
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    onSave(candidate, index);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
