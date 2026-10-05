<div class="vstack gap-3 animate__animated animate__fadeIn">
  {#each schemas as entry (entry.providerId)}
    {@const values = valuesOf(entry)}
    <div class="card" data-field="providerMeta.{entry.providerId}">
      <CardHeader>
        <div slot="left">{entry.name}</div>
      </CardHeader>
      <div class="card-body p-4 vstack gap-3">
        {#each groupFields(entry.schema) as block (block.key ?? '')}
          {@const shown = block.fields.filter((f) => isVisible(f, values, entry.schema))}
          {#if shown.length > 0}
            {#if block.label}
              <div class="text-body-secondary small text-uppercase mt-2">
                {text(block.label)}
              </div>
            {/if}
            {#each shown as field (field.key)}
              {@const id = `provider-${entry.providerId}-${field.key}`}
              {@const code = errors[`providerMeta.${entry.providerId}.${field.key}`]}
              {#if field.type === 'NOTICE'}
                <div
                  class="alert alert-{field.noticeLevel === 'WARNING'
                    ? 'warning'
                    : 'info'} d-flex align-items-start mb-0"
                  role="alert">
                  <i
                    class="fa-solid {field.noticeLevel === 'WARNING'
                      ? 'fa-triangle-exclamation'
                      : 'fa-circle-info'} me-3 mt-1"
                    aria-hidden="true"></i>
                  <div>{text(field.label)}</div>
                </div>
              {:else if field.type === 'READONLY'}
                <div class="input-group">
                  <input
                    type="text"
                    class="form-control"
                    readonly
                    aria-label={text(field.label)}
                    value={field.readonly?.value ?? ''} />
                </div>
              {:else if field.type === 'SWITCH'}
                <div class="form-check form-switch">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    role="switch"
                    {id}
                    data-field="providerMeta.{entry.providerId}.{field.key}"
                    checked={!!values[field.key]}
                    onchange={(e) => set(entry, field, e.currentTarget.checked)} />
                  <label class="form-check-label" for={id}>{label(field)}</label>
                  {@render help(field)}
                </div>
              {:else}
                <div>
                  <div class="form-floating">
                    {#if field.type === 'TEXTAREA' || field.type === 'SECRET_TEXTAREA'}
                      <textarea
                        class="form-control"
                        class:is-invalid={code}
                        {id}
                        data-field="providerMeta.{entry.providerId}.{field.key}"
                        placeholder={label(field)}
                        style="height: 100px;"
                        value={values[field.key] ?? ''}
                        autocomplete={isSecretField(field) ? 'off' : undefined}
                        onfocus={(e) => onFocus(entry, field, e.currentTarget)}
                        onblur={(e) => onBlur(entry, field, e.currentTarget)}
                        oninput={(e) => onInput(entry, field, e.currentTarget.value)}></textarea>
                    {:else if field.type === 'SELECT'}
                      <select
                        class="form-select"
                        class:is-invalid={code}
                        {id}
                        data-field="providerMeta.{entry.providerId}.{field.key}"
                        value={values[field.key] ?? ''}
                        onchange={(e) => set(entry, field, e.currentTarget.value)}>
                        {#each field.options ?? [] as option (option.value)}
                          <option value={option.value}>{text(option.label)}</option>
                        {/each}
                      </select>
                    {:else}
                      <input
                        class="form-control"
                        class:is-invalid={code}
                        {id}
                        type={isSecretField(field)
                          ? 'password'
                          : (INPUT_TYPE[field.type] ?? 'text')}
                        inputmode={field.type === 'NUMBER' ? 'numeric' : undefined}
                        autocomplete="off"
                        data-field="providerMeta.{entry.providerId}.{field.key}"
                        placeholder={label(field)}
                        value={values[field.key] ?? ''}
                        onfocus={(e) => onFocus(entry, field, e.currentTarget)}
                        onblur={(e) => onBlur(entry, field, e.currentTarget)}
                        oninput={(e) => onInput(entry, field, e.currentTarget.value)} />
                    {/if}
                    <label for={id}>{label(field)}</label>
                  </div>
                  {#if code}
                    <div class="invalid-feedback d-block">{$_(metaErrorKey(code))}</div>
                  {/if}
                  {@render help(field)}
                </div>
              {/if}
            {/each}
          {/if}
        {/each}
      </div>
    </div>
  {/each}
</div>

{#snippet help(field)}
  {#if field.help}
    <div class="form-text">{text(field.help)}</div>
  {/if}
{/snippet}

<script>
  import { CardHeader } from '@panomc/sdk/components/panel';
  import { _ as rawTranslate } from '@panomc/sdk/utils/language';
  import { _ } from '../../../i18n';
  import { currentLocale } from '../../utils/locale.js';
  import {
    groupFields,
    initialValues,
    isSecretField,
    isVisible,
    metaErrorKey,
    resolveText,
    secretOnBlur,
    secretOnFocus,
    secretOnInput,
  } from './provider-meta.js';

  // One card per provider that declares a per-product schema (`ctx.productMetaSchemas`), the form
  // generated from that schema (13 §8.8, field types of 13 §16.3). Values live in
  // `product.providerMeta[providerId]` and are written only when the admin edits a field; the
  // save path (model.js buildPayload) fills in the defaults of the untouched ones.
  // Props contract of every tab: product (bindable), errors (dotted path -> code), ctx.
  let { product = $bindable(), errors = {}, ctx = null } = $props();

  const INPUT_TYPE = { PASSWORD: 'password', URL: 'url' };

  const schemas = $derived(ctx?.productMetaSchemas ?? []);

  // Provider keys live under the provider's own `plugins.<pluginId>.*`, so the raw translator is used.
  const text = (value) => resolveText(value, currentLocale(), (key) => $rawTranslate(key));
  const label = (field) => `${text(field.label)}${field.required ? ' *' : ''}`;

  function valuesOf(entry) {
    return initialValues(entry.schema, product.providerMeta?.[entry.providerId]);
  }

  function set(entry, field, value) {
    const current = product.providerMeta?.[entry.providerId] ?? {};
    product.providerMeta = {
      ...product.providerMeta,
      [entry.providerId]: { ...valuesOf(entry), ...current, [field.key]: value },
    };
  }

  // Mask protocol (13 §16.3): a secret that was stored comes back as the mask. Focus on the mask
  // empties the input (typing then replaces the secret instead of appending to the mask), blur with
  // nothing typed puts the mask back (= keep the stored secret). `hadMask` remembers per
  // providerId + key that the field started as the mask.
  const hadMask = new Set();
  const maskId = (entry, field) => `${entry.providerId}:${field.key}`;

  function onFocus(entry, field, element) {
    if (!isSecretField(field)) return;
    const current = valuesOf(entry)[field.key];
    const next = secretOnFocus(current);
    if (next === current) return;
    hadMask.add(maskId(entry, field));
    element.value = next;
    set(entry, field, next);
  }

  function onBlur(entry, field, element) {
    if (!isSecretField(field)) return;
    const current = valuesOf(entry)[field.key] ?? '';
    const next = secretOnBlur(current, hadMask.has(maskId(entry, field)));
    if (next === current) return;
    element.value = next;
    set(entry, field, next);
  }

  function onInput(entry, field, value) {
    set(entry, field, isSecretField(field) ? secretOnInput(value) : value);
  }
</script>
