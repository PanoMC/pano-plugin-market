<form class="vstack gap-3" onsubmit={submit} novalidate>
  {#each fields as field (field.key)}
    {#if isVisible(field, values, fields)}
      {@const fid = controlId(field.key)}
      {@const code = errors[field.key]}
      {#if field.type === 'NOTICE'}
        <div class={['alert', 'mb-0', noticeClass(field)]} role="status">
          {field.label}
        </div>
      {:else if field.type === 'READONLY'}
        <div>
          <div class="form-label mb-1">{field.label}</div>
          <div class="text-break" id={fid}>{readonlyValue(field)}</div>
          {#if field.help}
            <div class="form-text">{field.help}</div>
          {/if}
        </div>
      {:else if field.type === 'SWITCH'}
        <div class="form-check form-switch">
          <input
            id={fid}
            class="form-check-input"
            type="checkbox"
            role="switch"
            disabled={busy}
            aria-describedby={field.help ? `${fid}-help` : undefined}
            bind:checked={values[field.key]} />
          <label class="form-check-label" for={fid}>{field.label}</label>
          {#if field.help}
            <div class="form-text" id="{fid}-help">{field.help}</div>
          {/if}
        </div>
      {:else}
        <div>
          <label class="form-label" for={fid}>
            {field.label}
            {#if field.required}
              <span class="text-danger" aria-hidden="true">*</span>
            {/if}
          </label>
          {#if field.type === 'TEXTAREA'}
            <textarea
              id={fid}
              class={['form-control', code && 'is-invalid']}
              rows="4"
              maxlength={TEXTAREA_MAX}
              placeholder={field.placeholder || undefined}
              autocomplete="off"
              disabled={busy}
              aria-required={field.required ? 'true' : undefined}
              aria-invalid={code ? 'true' : undefined}
              aria-describedby={describedBy(field, fid, code)}
              bind:value={values[field.key]}></textarea>
          {:else if field.type === 'SELECT'}
            <select
              id={fid}
              class={['form-select', code && 'is-invalid']}
              disabled={busy}
              aria-required={field.required ? 'true' : undefined}
              aria-invalid={code ? 'true' : undefined}
              aria-describedby={describedBy(field, fid, code)}
              bind:value={values[field.key]}>
              {#if !field.required || values[field.key] === ''}
                <option value="">{field.placeholder || ''}</option>
              {/if}
              {#each field.options ?? [] as option (option.value ?? option)}
                <option value={String(option.value ?? option)}
                  >{option.label ?? option.value ?? option}</option>
              {/each}
            </select>
          {:else}
            <input
              id={fid}
              class={['form-control', code && 'is-invalid']}
              type={INPUT_TYPES[field.type] ?? 'text'}
              inputmode={field.type === 'NUMBER' ? 'numeric' : undefined}
              maxlength={TEXT_MAX}
              placeholder={field.placeholder || undefined}
              autocomplete={field.type === 'PASSWORD' ? 'off' : undefined}
              disabled={busy}
              aria-required={field.required ? 'true' : undefined}
              aria-invalid={code ? 'true' : undefined}
              aria-describedby={describedBy(field, fid, code)}
              bind:value={values[field.key]} />
          {/if}
          {#if code}
            <div class="invalid-feedback" id="{fid}-error">
              {$_(
                code === 'FIELD_REQUIRED'
                  ? 'theme.checkout.field-required'
                  : 'theme.checkout.field-invalid',
              )}
            </div>
          {/if}
          {#if field.help}
            <div class="form-text" id="{fid}-help">{field.help}</div>
          {/if}
        </div>
      {/if}
    {/if}
  {/each}

  {#if alertKey}
    <div class="alert alert-danger mb-0" role="alert">{$_(alertKey, { values: { seconds } })}</div>
  {/if}

  <div>
    <button type="submit" class="btn btn-primary" disabled={busy || waiting}>
      {#if busy}
        <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
      {/if}
      {$_('theme.order.payment-form-submit')}
    </button>
  </div>
</form>

<script>
  import { untrack } from 'svelte';
  import { _ } from '../../../i18n.js';
  import {
    TEXTAREA_MAX,
    TEXT_MAX,
    buildValues,
    controlId,
    initialValues,
    isVisible,
    readonlyValue,
    validateForm,
  } from '../../lib/paymentPanel.js';

  const INPUT_TYPES = { TEXT: 'text', URL: 'text', NUMBER: 'text', PASSWORD: 'password' };

  /**
   * Generic embedded form (14 §11.4 `EMBEDDED` with `fields`): one control per field; labels and help are the
   * plain strings the server resolved. Submit validates the visible fields, then `continuePayment(values)` (the
   * panel's request; it answers `{ ok, alertKey?, seconds? }`). `waiting` disables the button (rate limit).
   */
  let { fields = [], continuePayment, waiting = false, seconds = 0 } = $props();

  let values = $state(untrack(() => initialValues(fields)));
  let errors = $state({});
  let alertKey = $state('');
  let busy = $state(false);

  const noticeClass = (field) => (field.noticeLevel === 'WARNING' ? 'alert-warning' : 'alert-info');

  const describedBy = (field, fid, code) =>
    [field.help ? `${fid}-help` : '', code ? `${fid}-error` : ''].filter(Boolean).join(' ') ||
    undefined;

  function focusFirst(found) {
    const key = fields.find((field) => found[field.key])?.key;
    if (key !== undefined) document.getElementById(controlId(key))?.focus();
  }

  async function submit(event) {
    event.preventDefault();

    if (busy || waiting) return;

    errors = validateForm(fields, values);
    alertKey = '';

    if (Object.keys(errors).length > 0) {
      focusFirst(errors);
      return;
    }

    busy = true;

    const result = await continuePayment(buildValues(fields, values));

    busy = false;

    if (result && result.ok !== true) alertKey = result.alertKey ?? 'theme.errors.GENERIC';
  }
</script>
