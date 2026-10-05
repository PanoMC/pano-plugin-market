<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.block.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          <div>
            <label class="form-label" for="block-type">{$_('modals.block.type')}</label>
            <select id="block-type" class="form-select" bind:value={form.type}>
              {#each BLOCK_TYPES as type (type)}
                <option value={type}>{$_(`enums.block-type.${type}`)}</option>
              {/each}
            </select>
          </div>

          <div>
            <input
              id="block-value"
              type="text"
              class="form-control"
              class:is-invalid={shown.value}
              autocomplete="off"
              maxlength={VALUE_MAX + 50}
              placeholder={$_(`modals.block.value-placeholder.${form.type}`)}
              aria-label={$_('modals.block.value')}
              bind:value={form.value} />
            {#if shown.value}
              <div class="invalid-feedback d-block">
                {$_(`modals.block.error.value-${shown.value}`)}
              </div>
            {/if}
          </div>

          <div>
            <input
              id="block-reason"
              type="text"
              class="form-control"
              class:is-invalid={shown.reason}
              autocomplete="off"
              maxlength={REASON_MAX + 50}
              placeholder={$_('modals.block.reason')}
              aria-label={$_('modals.block.reason')}
              bind:value={form.reason} />
            {#if shown.reason}
              <div class="invalid-feedback d-block">
                {$_('modals.block.error.reason-TOO_LONG')}
              </div>
            {/if}
          </div>

          <div>
            <div class="form-check form-switch">
              <input
                id="block-expires"
                class="form-check-input"
                type="checkbox"
                role="switch"
                bind:checked={form.expires} />
              <label class="form-check-label" for="block-expires">
                {$_('modals.block.expires')}
              </label>
            </div>
            {#if form.expires}
              <input
                id="block-expires-at"
                type="datetime-local"
                class="form-control mt-2"
                class:is-invalid={shown.expiresAt}
                aria-label={$_('modals.block.expires-at')}
                bind:value={form.expiresInput} />
              {#if shown.expiresAt}
                <div class="invalid-feedback d-block">
                  {$_(`modals.block.error.expires-${shown.expiresAt}`)}
                </div>
              {/if}
            {/if}
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {$_('modals.block.submit')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showSuccessToast } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import {
    BLOCK_TYPES,
    REASON_MAX,
    VALUE_MAX,
    blockFieldError,
    buildBlockBody,
    validateBlock,
  } from '../../utils/blocks.js';
  import { toEpoch } from '../../utils/format.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  let { onSaved = () => {} } = $props();

  const blank = () => ({
    type: 'PLAYER',
    value: '',
    reason: '',
    expires: false,
    expiresInput: '',
  });

  let modalElement = $state(null);
  let form = $state(blank());
  let touched = $state(false);
  let saving = $state(false);
  let serverError = $state(null);

  const model = $derived({
    type: form.type,
    value: form.value,
    reason: form.reason,
    expires: form.expires,
    expiresAt: toEpoch(form.expiresInput),
  });
  const checked = $derived(validateBlock(model));
  const errors = $derived(checked.ok ? {} : checked.errors);
  // A server-side mark ("already blocked") only holds for the type and value it answered for.
  const serverMark = $derived(
    serverError && serverError.type === form.type && serverError.value === form.value
      ? serverError
      : null,
  );
  const shown = $derived({
    value: serverMark ? serverMark.code : touched ? (errors.value ?? null) : null,
    reason: touched ? (errors.reason ?? null) : null,
    expiresAt: touched ? (errors.expiresAt ?? null) : null,
  });

  export function open() {
    form = blank();
    touched = false;
    saving = false;
    serverError = null;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    touched = true;
    if (!checked.ok) return;

    saving = true;
    let result;
    try {
      result = await call(ApiUtil.post({ path: marketPath('/blocks'), body: buildBlockBody(model) }));
    } finally {
      saving = false;
    }
    if (!result.ok) {
      const mark = blockFieldError(result.error);
      serverError = mark ? { ...mark, type: form.type, value: form.value } : null;
      toastError($_, result);
      return;
    }
    showSuccessToast($_('modals.block.toast-added'));
    hideModal(modalElement);
    onSaved(result.body);
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
