<div class="vstack gap-3">
  {#each blocks as block (block.key ?? '__loose')}
    {#if block.label}
      <div class="text-body-secondary small text-uppercase mt-3">{txt(block.label)}</div>
    {/if}

    {#each block.fields as field (field.key)}
      {@const fieldId = `${idPrefix}-${field.key}`}
      {@const label = txt(field.label) + (field.required ? ' *' : '')}
      {@const invalid = errors?.[field.key] !== undefined && errors?.[field.key] !== null}

      <div>
        {#if field.type === 'NOTICE'}
          <div
            class="alert {field.noticeLevel === 'WARNING' ? 'alert-warning' : 'alert-info'} d-flex align-items-start mb-0"
            role="alert">
            <i
              class="fa-solid {field.noticeLevel === 'WARNING'
                ? 'fa-triangle-exclamation'
                : 'fa-circle-info'} me-3 mt-1"
              aria-hidden="true"></i>
            <div>{txt(field.label)}</div>
          </div>
        {:else if field.type === 'READONLY'}
          <div class="input-group">
            <div class="form-floating">
              <input
                id={fieldId}
                type="text"
                class="form-control"
                readonly
                placeholder={label}
                value={readonlyValue(field, webhookUrls)} />
              <label for={fieldId}>{label}</label>
            </div>
            <CopyButton text={readonlyValue(field, webhookUrls)} />
          </div>
        {:else if field.type === 'SWITCH'}
          <div class="form-check form-switch">
            <input
              id={fieldId}
              class="form-check-input"
              class:is-invalid={invalid}
              type="checkbox"
              role="switch"
              disabled={readOnly}
              bind:checked={values[field.key]} />
            <label class="form-check-label user-select-none" for={fieldId}>{label}</label>
          </div>
        {:else if field.type === 'PASSWORD' || field.type === 'SECRET_TEXTAREA'}
          <SecretInput
            id={fieldId}
            {label}
            placeholder={field.placeholder ?? ''}
            multiline={field.type === 'SECRET_TEXTAREA'}
            revealPath={readOnly ? '' : revealPath}
            fieldKey={field.key}
            disabled={readOnly}
            {invalid}
            removable={!field.required}
            onrevealed={revealAll}
            bind:value={values[field.key]} />
        {:else if field.type === 'TEXTAREA'}
          <div class="form-floating">
            <textarea
              id={fieldId}
              class="form-control"
              class:is-invalid={invalid}
              style="height: 100px;"
              placeholder={field.placeholder || label}
              disabled={readOnly}
              bind:value={values[field.key]}></textarea>
            <label for={fieldId}>{label}</label>
          </div>
        {:else if field.type === 'SELECT'}
          <div class="form-floating">
            <select
              id={fieldId}
              class="form-select"
              class:is-invalid={invalid}
              disabled={readOnly}
              bind:value={values[field.key]}>
              {#if !field.required || !(field.options ?? []).some((o) => o.value === values[field.key])}
                <option value=""></option>
              {/if}
              {#each field.options ?? [] as option (option.value)}
                <option value={option.value}>{txt(option.label)}</option>
              {/each}
            </select>
            <label for={fieldId}>{label}</label>
          </div>
        {:else}
          <div class="form-floating">
            <input
              id={fieldId}
              type={field.type === 'URL' ? 'url' : 'text'}
              inputmode={field.type === 'NUMBER' ? 'numeric' : undefined}
              class="form-control"
              class:is-invalid={invalid}
              autocomplete="off"
              placeholder={field.placeholder || label}
              disabled={readOnly}
              bind:value={values[field.key]} />
            <label for={fieldId}>{label}</label>
          </div>
        {/if}

        {#if field.help && field.type !== 'NOTICE'}
          <div class="form-text">{txt(field.help)}</div>
        {/if}
        {#if invalid}
          <div class="invalid-feedback d-block">{message(errors[field.key])}</div>
        {/if}
      </div>
    {/each}
  {/each}
</div>

<script>
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _ } from '../../../i18n';
  import CopyButton from '../CopyButton.svelte';
  import SecretInput from './SecretInput.svelte';
  import { currentLocale } from '../../utils/locale.js';
  import {
    applyReveal,
    errorText,
    groupFields,
    readonlyValue,
    resolveText,
  } from '../../utils/schema-form.js';

  // Schema-driven form of a provider (13 §16.3). `values` holds one entry per storable field
  // (initialValues); `errors` maps a field key to a client code or the server's LocalizedText.
  let {
    schema,
    values = $bindable({}),
    errors = {},
    readOnly = false,
    webhookUrls = {},
    idPrefix = 'schema',
    revealPath = '',
  } = $props();

  // The raw SDK translator: a provider's own keys live under plugins.<pluginId>.*, and a missing
  // key must come back as the key itself so resolveText falls back to the literal.
  const rawTranslate = (key) => $rawI18n(key, { default: key });
  const txt = (text) => resolveText(text, currentLocale(), rawTranslate);
  const message = (error) =>
    errorText(error, { locale: currentLocale(), rawTranslate, translate: (key) => $_(key) });

  const blocks = $derived(groupFields(schema, values));

  // One password prompt unmasks every secret of the form that still shows the mask.
  function revealAll(revealed) {
    values = applyReveal(schema, values, revealed);
  }
</script>
