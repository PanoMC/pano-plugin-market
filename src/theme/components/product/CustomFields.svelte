{#each fields as field (field.fieldKey)}
  {@const id = fieldId(field.fieldKey)}
  {@const error = errors[field.fieldKey]}
  {@const hint = field.helpText ? `${id}-help` : undefined}
  {@const feedback = error ? `${id}-error` : undefined}
  {@const described = [hint, feedback].filter(Boolean).join(' ') || undefined}
  <div class="mb-3">
    {#if field.type === 'CHECKBOX'}
      <div class="form-check">
        <input
          class={['form-check-input', error && 'is-invalid']}
          type="checkbox"
          {id}
          checked={values[field.fieldKey] === true}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={described}
          onchange={(event) => onchange(field.fieldKey, event.currentTarget.checked)}
          onblur={() => onblur(field.fieldKey)} />
        <label class="form-check-label" for={id}>
          {field.label}{#if field.required}<span class="text-danger" aria-hidden="true">
              *</span
            >{/if}
        </label>
        {#if error}
          <div class="invalid-feedback" id={feedback}>{$_(`theme.errors.${error}`)}</div>
        {/if}
      </div>
    {:else}
      <label class="form-label" for={id}>
        {field.label}{#if field.required}<span class="text-danger" aria-hidden="true"> *</span>{/if}
      </label>
      {#if field.type === 'SELECT'}
        <select
          {id}
          class={['form-select', error && 'is-invalid']}
          value={values[field.fieldKey] ?? ''}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={described}
          onchange={(event) => onchange(field.fieldKey, event.currentTarget.value)}
          onblur={() => onblur(field.fieldKey)}>
          <option value="" disabled={field.required}>{field.placeholder || ''}</option>
          {#each field.options || [] as option (option.value ?? option)}
            <option value={String(option.value ?? option)}
              >{option.label ?? option.value ?? option}</option>
          {/each}
        </select>
      {:else if field.type === 'NUMBER'}
        <input
          {id}
          type="number"
          step="1"
          min={field.minValue ?? undefined}
          max={field.maxValue ?? undefined}
          class={['form-control', error && 'is-invalid']}
          placeholder={field.placeholder || undefined}
          value={values[field.fieldKey] ?? ''}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={described}
          oninput={(event) => onchange(field.fieldKey, event.currentTarget.value)}
          onblur={() => onblur(field.fieldKey)} />
      {:else}
        <input
          {id}
          type={field.type === 'EMAIL' ? 'email' : 'text'}
          inputmode={field.type === 'DISCORD_ID' ? 'numeric' : undefined}
          maxlength={field.type === 'TEXT' ? (field.maxLength ?? 128) : undefined}
          class={['form-control', error && 'is-invalid']}
          placeholder={field.placeholder || undefined}
          autocomplete="off"
          value={values[field.fieldKey] ?? ''}
          aria-invalid={error ? 'true' : undefined}
          aria-describedby={described}
          oninput={(event) => onchange(field.fieldKey, event.currentTarget.value)}
          onblur={() => onblur(field.fieldKey)} />
      {/if}
      {#if error}
        <div class="invalid-feedback" id={feedback}>{$_(`theme.errors.${error}`)}</div>
      {/if}
    {/if}
    {#if field.helpText}
      <div class="form-text" id={hint}>{field.helpText}</div>
    {/if}
  </div>
{/each}

<script>
  import { _ } from '../../../i18n.js';
  import { fieldId } from './productModel.js';

  /** One control per field of ProductDetail.fields[]; values / errors are keyed by fieldKey. */
  let { fields = [], values = {}, errors = {}, onchange = () => {}, onblur = () => {} } = $props();
</script>
