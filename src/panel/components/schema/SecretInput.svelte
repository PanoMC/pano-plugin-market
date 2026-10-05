<div>
  <div class="position-relative">
    <div class="form-floating">
      {#if multiline}
        <textarea
          {id}
          class="form-control pe-5"
          class:is-invalid={invalid}
          style="height: 100px;"
          autocomplete="off"
          placeholder={shownPlaceholder}
          {disabled}
          value={text}
          onfocus={onFocus}
          onblur={onBlur}
          oninput={onInput}></textarea>
      {:else}
        <input
          {id}
          type={visible ? 'text' : 'password'}
          class="form-control pe-5"
          class:is-invalid={invalid}
          autocomplete="new-password"
          placeholder={shownPlaceholder}
          {disabled}
          value={text}
          onfocus={onFocus}
          onblur={onBlur}
          oninput={onInput} />
      {/if}
      <label for={id}>{label}</label>
    </div>
    {#if canReveal}
      <button
        type="button"
        class="btn btn-link position-absolute top-0 end-0 mt-2 me-1 text-body-secondary text-decoration-none"
        style="z-index: 5;"
        title={visible ? $_('schema.hide') : $_('schema.reveal')}
        aria-label={visible ? $_('schema.hide') : $_('schema.reveal')}
        onclick={onEyeClick}>
        <i class="fa-solid {visible ? 'fa-eye-slash' : 'fa-eye'}" aria-hidden="true"></i>
      </button>
    {/if}
  </div>

  {#if canRemove}
    <button
      type="button"
      class="btn btn-link btn-sm text-danger text-decoration-none px-0 mt-1"
      onclick={removeSecret}>
      <i class="fa-solid fa-trash me-1" aria-hidden="true"></i>{$_('schema.remove-secret')}
    </button>
  {/if}

  {#if promptOpen}
    <div class="border rounded p-2 mt-2">
      <label class="form-label mb-1" for="{id}-reveal-password">{$_('schema.password-prompt')}</label>
      <div class="d-flex gap-2">
        <input
          id="{id}-reveal-password"
          type="password"
          class="form-control form-control-sm"
          class:is-invalid={passwordInvalid}
          autocomplete="current-password"
          placeholder={$_('schema.password-placeholder')}
          bind:value={password}
          onkeydown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              confirmReveal();
            }
          }} />
        <button
          type="button"
          class="btn btn-sm btn-primary flex-shrink-0"
          disabled={loading || !password}
          onclick={confirmReveal}>
          {#if loading}
            <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
          {:else}
            {$_('schema.reveal-confirm')}
          {/if}
        </button>
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary flex-shrink-0"
          onclick={closePrompt}>
          {$_('common.cancel')}
        </button>
      </div>
      {#if passwordInvalid}
        <div class="invalid-feedback d-block">{$_('errors.INVALID_PASSWORD')}</div>
      {/if}
    </div>
  {/if}
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import { toastError } from '../../utils/toast.js';
  import { SECRET_MASK, secretOnBlur, secretOnFocus, secretOnInput } from '../../utils/schema-form.js';

  // Secret input of the schema form (13 §16.3). `value` is the mask (stored secret), a typed text,
  // '' (nothing stored) or null (the admin removed the stored secret). `revealPath` is the
  // password-gated reveal endpoint ('' = no reveal); `onrevealed(settings)` lets the form fill the
  // other secrets that still show the mask. `stored` = the server holds a value for this secret.
  let {
    value = $bindable(''),
    multiline = false,
    revealPath = '',
    fieldKey = '',
    disabled = false,
    invalid = false,
    removable = false,
    stored = false,
    id = 'secret',
    label = '',
    placeholder = '',
    onrevealed = null,
  } = $props();

  let visible = $state(false);
  let promptOpen = $state(false);
  let password = $state('');
  let passwordInvalid = $state(false);
  let loading = $state(false);

  // No sticky local state: a removed secret is `null` itself and "a value is stored" comes from the
  // parent (the loaded baseline), so a remount or a re-seeded form can never lose either fact.
  const removed = $derived(value === null);
  const masked = $derived(value === SECRET_MASK);
  const text = $derived(value === null || value === undefined ? '' : value);
  const shownPlaceholder = $derived(removed ? $_('schema.secret-will-be-removed') : placeholder || label);
  // A single-line secret can always be shown or hidden (a masked one asks for the password first);
  // a multiline one only offers the eye while it is masked and a reveal endpoint exists.
  const canReveal = $derived(!disabled && (multiline ? masked && !!revealPath : true));
  const canRemove = $derived(!disabled && removable && masked);

  function onFocus() {
    value = secretOnFocus(value);
  }

  function onBlur() {
    value = secretOnBlur(value, { hadMask: stored, removed });
    if (value === SECRET_MASK) visible = false;
  }

  function onInput(event) {
    const typed = secretOnInput(event.currentTarget.value);
    value = typed;
    if (event.currentTarget.value !== typed) event.currentTarget.value = typed;
  }

  function onEyeClick() {
    if (visible) {
      visible = false;
      return;
    }
    // Nothing stored behind the box (typed, revealed, removed or empty): just show what it holds.
    if (!masked || !revealPath) {
      visible = true;
      return;
    }
    password = '';
    passwordInvalid = false;
    promptOpen = true;
  }

  function removeSecret() {
    value = null;
    visible = false;
    closePrompt();
  }

  function closePrompt() {
    promptOpen = false;
    password = '';
    passwordInvalid = false;
  }

  async function confirmReveal() {
    if (!password || loading || !revealPath) return;
    loading = true;
    passwordInvalid = false;
    let result;
    try {
      result = await call(ApiUtil.post({ path: marketPath(revealPath), body: { password } }));
    } finally {
      loading = false;
    }
    if (!result.ok) {
      if (result.error === 'INVALID_PASSWORD') {
        passwordInvalid = true;
        return;
      }
      toastError($_, result);
      // throttled or failed for another reason: the prompt closes
      closePrompt();
      return;
    }
    const revealed = result.body.settings ?? {};
    onrevealed?.(revealed);
    // Fill my own field when the form did not (only a field still showing the mask).
    if (value === SECRET_MASK && revealed[fieldKey] !== undefined && revealed[fieldKey] !== null)
      value = revealed[fieldKey];
    visible = true;
    closePrompt();
  }
</script>
