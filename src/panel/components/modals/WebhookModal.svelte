<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {isEdit ? $_('modals.webhook.heading-edit') : $_('modals.webhook.heading-create')}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body">
          <div class="vstack gap-3">
            <div>
              <div class="form-floating">
                <input
                  id="webhookNameInput"
                  type="text"
                  class="form-control"
                  class:is-invalid={shown.name}
                  autocomplete="off"
                  placeholder={$_('modals.webhook.name')}
                  bind:value={form.name} />
                <label for="webhookNameInput">{$_('modals.webhook.name')}</label>
              </div>
              {#if shown.name}
                <div class="invalid-feedback d-block">{$_(errorTextKey(shown.name))}</div>
              {/if}
            </div>

            <div>
              <div class="mb-1">{$_('modals.webhook.format')}</div>
              <div class="btn-group" role="group" aria-label={$_('modals.webhook.format')}>
                {#each WEBHOOK_FORMATS as format (format)}
                  <input
                    type="radio"
                    class="btn-check"
                    name="webhookFormat"
                    id="webhookFormat{format}"
                    autocomplete="off"
                    checked={form.format === format}
                    onchange={() => setFormat(format)} />
                  <label class="btn btn-outline-secondary" for="webhookFormat{format}">
                    {#if format === 'DISCORD'}
                      <i class="fa-brands fa-discord me-1" aria-hidden="true"></i>
                    {/if}
                    {$_(`enums.webhook-format.${format}`)}
                  </label>
                {/each}
              </div>
            </div>

            <div>
              <div class="form-floating">
                <input
                  id="webhookUrlInput"
                  type="text"
                  inputmode="url"
                  class="form-control"
                  class:is-invalid={shown.url}
                  autocomplete="off"
                  placeholder={$_('modals.webhook.url')}
                  bind:value={form.url} />
                <label for="webhookUrlInput">{$_('modals.webhook.url')}</label>
              </div>
              {#if shown.url}
                <div class="invalid-feedback d-block">
                  {shown.url === 'INVALID_DISCORD_URL'
                    ? $_('settings.webhooks.invalid-discord-url')
                    : $_(errorTextKey(shown.url))}
                </div>
              {/if}
            </div>

            <div>
              <div class="form-check form-switch mb-2">
                <input
                  id="webhookAllEvents"
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  bind:checked={form.allEvents} />
                <label class="form-check-label" for="webhookAllEvents">
                  {$_('modals.webhook.all-events')}
                </label>
              </div>
              {#if !form.allEvents}
                <div class="row row-cols-1 row-cols-md-2 g-1">
                  {#each eventList as name (name)}
                    <div class="col">
                      <div class="form-check">
                        <input
                          id="webhookEvent-{name}"
                          class="form-check-input"
                          type="checkbox"
                          checked={form.events.includes(name)}
                          onchange={(e) => toggleEvent(name, e.currentTarget.checked)} />
                        <label class="form-check-label" for="webhookEvent-{name}">
                          {$_(eventKey(name))}
                        </label>
                      </div>
                    </div>
                  {/each}
                </div>
              {/if}
              {#if shown.events}
                <div class="invalid-feedback d-block">{$_(errorTextKey(shown.events))}</div>
              {/if}
            </div>

            <div class="row g-3">
              <div class="col-md-6">
                <div class="form-floating">
                  <select
                    id="webhookSigning"
                    class="form-select"
                    disabled={discord}
                    value={form.signing}
                    onchange={(e) => (form = withSigning(form, e.currentTarget.value))}>
                    {#each WEBHOOK_SIGNINGS as signing (signing)}
                      <option value={signing}>{$_(`enums.webhook-signing.${signing}`)}</option>
                    {/each}
                  </select>
                  <label for="webhookSigning">{$_('modals.webhook.signing')}</label>
                </div>
              </div>
              <div class="col-md-6">
                <div class="form-floating">
                  <input
                    id="webhookMaxAttempts"
                    type="text"
                    inputmode="numeric"
                    class="form-control"
                    class:is-invalid={shown.maxAttempts}
                    autocomplete="off"
                    placeholder={$_('modals.webhook.max-attempts')}
                    bind:value={form.maxAttempts} />
                  <label for="webhookMaxAttempts">{$_('modals.webhook.max-attempts')}</label>
                </div>
                {#if shown.maxAttempts}
                  <div class="invalid-feedback d-block">{$_(errorTextKey(shown.maxAttempts))}</div>
                {/if}
              </div>
            </div>

            {#if form.signing === 'HMAC_SHA256' && !discord}
              <div>
                <SecretInput
                  id="webhookSecret"
                  bind:value={form.secret}
                  stored={hadSecret}
                  invalid={!!shown.secret}
                  label={$_('modals.webhook.secret')}
                  placeholder={isEdit ? $_('modals.webhook.secret') : $_('modals.webhook.secret-generate')} />
                {#if shown.secret}
                  <div class="invalid-feedback d-block">{$_(errorTextKey(shown.secret))}</div>
                {/if}
              </div>
            {/if}

            <div>
              <div class="mb-1">{$_('modals.webhook.headers')}</div>
              <KeyValueList
                bind:rows={form.headers}
                max={MAX_HEADERS}
                keyPattern={HEADER_NAME_PATTERN}
                keyPlaceholder={$_('modals.webhook.header-name')}
                valuePlaceholder={$_('modals.webhook.header-value')} />
              {#each headerMessages as code, index (index)}
                <div class="invalid-feedback d-block">{$_(errorTextKey(code))}</div>
              {/each}
            </div>

            {#if discord}
              <div>
                <div class="form-check form-switch mb-2">
                  <input
                    id="webhookCustomTemplate"
                    class="form-check-input"
                    type="checkbox"
                    role="switch"
                    bind:checked={form.customTemplate} />
                  <label class="form-check-label" for="webhookCustomTemplate">
                    {$_('modals.webhook.customize-template')}
                  </label>
                </div>
                {#if form.customTemplate}
                  <textarea
                    id="webhookTemplate"
                    class="form-control font-monospace"
                    class:is-invalid={shown.template}
                    rows="10"
                    spellcheck="false"
                    aria-label={$_('modals.webhook.template')}
                    bind:value={form.template}></textarea>
                  {#if shown.template}
                    <div class="invalid-feedback d-block">{$_(errorTextKey(shown.template))}</div>
                  {/if}
                  <button
                    type="button"
                    class="btn btn-link btn-sm px-0"
                    onclick={() => (form.template = defaults?.discordTemplate ?? '')}>
                    {$_('modals.webhook.reset-template')}
                  </button>
                {/if}
              </div>
            {/if}

            <div class="form-check form-switch m-0">
              <input
                id="webhookEnabled"
                class="form-check-input"
                type="checkbox"
                role="switch"
                bind:checked={form.enabled} />
              <label class="form-check-label" for="webhookEnabled">{$_('common.active')}</label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {isEdit ? $_('common.save') : $_('common.create')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showSuccessToast } from '../../../i18n';
  import KeyValueList from '../KeyValueList.svelte';
  import SecretInput from '../schema/SecretInput.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { toastError } from '../../utils/toast.js';
  import {
    HEADER_NAME_PATTERN,
    MAX_HEADERS,
    WEBHOOK_FORMATS,
    WEBHOOK_SIGNINGS,
    blankForm,
    buildBody,
    errorTextKey,
    eventKey,
    formFromEndpoint,
    serverFieldErrors,
    subscribableEvents,
    withFormat,
    withSigning,
  } from '../../utils/webhooks.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  // Create / edit form of one store webhook (13 §18.2). `onSaved()` refreshes the list, `onSecret(secret)`
  // opens the one-time secret modal after this modal is hidden.
  let { onSaved = () => {}, onSecret = () => {} } = $props();

  let modalElement = $state(null);
  let form = $state(blankForm());
  let endpoint = $state.raw(null);
  let defaults = $state.raw({});
  let eventNames = $state.raw([]);
  let submitted = $state(false);
  let saving = $state(false);
  let serverErrors = $state({});

  const isEdit = $derived(endpoint !== null);
  const discord = $derived(form.format === 'DISCORD');
  const eventList = $derived(subscribableEvents(eventNames));
  const hadSecret = $derived(isEdit && !!endpoint?.secret);
  const result = $derived(buildBody(form, { isEdit }));
  const shown = $derived({ ...(submitted ? (result.errors ?? {}) : {}), ...serverErrors });
  const headerMessages = $derived.by(() => {
    const info = shown.headers;
    if (!info) return [];
    const codes = [...new Set(Object.values(info.byIndex ?? {}))];
    return info.tooMany ? [...codes, 'TOO_MANY_HEADERS'] : codes;
  });

  /** `item` = the endpoint to edit or null to create; `context` = `{ defaults, eventNames }` of GET /webhooks. */
  export function open(item, context = {}) {
    endpoint = item;
    defaults = context.defaults ?? {};
    eventNames = context.eventNames ?? [];
    form = item ? formFromEndpoint(item, defaults) : blankForm();
    submitted = false;
    serverErrors = {};
    showModal(modalElement);
  }

  function setFormat(format) {
    form = withFormat(form, format, defaults);
    serverErrors = {};
  }

  function toggleEvent(name, checked) {
    form.events = checked
      ? [...form.events.filter((n) => n !== name), name]
      : form.events.filter((n) => n !== name);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    serverErrors = {};
    if (result.errors) return;

    saving = true;
    let response;
    try {
      response = isEdit
        ? await call(ApiUtil.put({ path: marketPath(`/webhooks/${endpoint.id}`), body: result.body }))
        : await call(ApiUtil.post({ path: marketPath('/webhooks'), body: result.body }));
    } finally {
      saving = false;
    }
    if (!response.ok) {
      serverErrors = serverFieldErrors(response.error, response.body);
      toastError($_, response);
      if (response.error === 'NOT_FOUND') {
        hideModal(modalElement);
        onSaved();
      }
      return;
    }
    showSuccessToast(isEdit ? $_('modals.webhook.toast-updated') : $_('modals.webhook.toast-created'));
    const secret = typeof response.body.secret === 'string' ? response.body.secret : '';
    if (secret && modalElement && window.bootstrap) {
      // The generated secret is shown once, in its own modal, after this one is gone.
      modalElement.addEventListener('hidden.bs.modal', () => onSecret(secret), { once: true });
      hideModal(modalElement);
    } else {
      hideModal(modalElement);
      if (secret) onSecret(secret);
    }
    onSaved();
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
