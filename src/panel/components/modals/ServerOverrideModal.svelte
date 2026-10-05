<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_('modals.server-override.title', { values: { name: server?.name ?? '' } })}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          {#each MC_FEATURES as feature (feature.key)}
            <div class="row align-items-center">
              <label class="col-md-6 col-form-label" for="override-{feature.key}">
                {$_(`settings.minecraft.feature.${feature.id}`)}
              </label>
              <div class="col-md-6">
                <select
                  id="override-{feature.key}"
                  class="form-select"
                  bind:value={form[feature.key]}>
                  <option value="default">
                    {$_('modals.server-override.state.default', {
                      values: { value: panelValue(feature.key) },
                    })}
                  </option>
                  <option value="on">{$_('modals.server-override.state.on')}</option>
                  <option value="off">{$_('modals.server-override.state.off')}</option>
                </select>
              </div>
            </div>
          {/each}

          <div class="row align-items-center">
            <label class="col-md-6 col-form-label" for="override-mcDisabledAdminCommands-mode">
              {$_('settings.minecraft.admin-commands-list')}
            </label>
            <div class="col-md-6">
              <select
                id="override-mcDisabledAdminCommands-mode"
                class="form-select"
                value={form.adminCommandsCustom ? 'custom' : 'default'}
                onchange={(event) =>
                  (form.adminCommandsCustom = event.currentTarget.value === 'custom')}>
                <option value="default">
                  {$_('modals.server-override.state.default', {
                    values: { value: $_('modals.server-override.panel-list') },
                  })}
                </option>
                <option value="custom">{$_('modals.server-override.custom')}</option>
              </select>
            </div>
          </div>
          {#if form.adminCommandsCustom}
            <div class="vstack gap-1" role="group">
              {#each ADMIN_COMMANDS as command (command)}
                <div class="form-check m-0">
                  <input
                    id="override-admin-{command}"
                    class="form-check-input"
                    type="checkbox"
                    checked={!form.mcDisabledAdminCommands.includes(command)}
                    onchange={(event) => setAdminCommand(command, event.currentTarget.checked)} />
                  <label class="form-check-label" for="override-admin-{command}">
                    {$_(`settings.minecraft.admin-command.${command}`)}
                  </label>
                </div>
              {/each}
            </div>
          {/if}

          <div>
            <textarea
              id="override-mcBroadcastTemplate"
              class="form-control font-monospace"
              class:is-invalid={shown.mcBroadcastTemplate}
              rows="2"
              maxlength="256"
              placeholder={$_('modals.server-override.template')}
              aria-label={$_('modals.server-override.template')}
              bind:value={form.mcBroadcastTemplate}></textarea>
            {#if shown.mcBroadcastTemplate}
              <div class="invalid-feedback d-block">
                {$_(fieldErrorKey(shown.mcBroadcastTemplate))}
              </div>
            {/if}
          </div>

          <div class="row align-items-center">
            <label class="col-md-6 col-form-label" for="override-mcVaultMode">
              {$_('settings.minecraft.vault-mode')}
            </label>
            <div class="col-md-6">
              <select id="override-mcVaultMode" class="form-select" bind:value={form.mcVaultMode}>
                <option value="default">
                  {$_('modals.server-override.state.default', {
                    values: {
                      value: $_(
                        `settings.minecraft.vault-option.${defaults?.mcVaultMode ?? 'OFF'}`,
                      ),
                    },
                  })}
                </option>
                {#each VAULT_MODES as mode (mode)}
                  <option value={mode}>{$_(`settings.minecraft.vault-option.${mode}`)}</option>
                {/each}
              </select>
            </div>
          </div>

          {#if convert}
            <div>
              <input
                id="override-mcVaultRate"
                type="text"
                inputmode="decimal"
                autocomplete="off"
                class="form-control"
                class:is-invalid={shown.mcVaultRate}
                placeholder={$_('modals.server-override.vault-rate')}
                aria-label={$_('modals.server-override.vault-rate')}
                bind:value={form.mcVaultRate} />
              {#if shown.mcVaultRate}
                <div class="invalid-feedback d-block">{$_(fieldErrorKey(shown.mcVaultRate))}</div>
              {/if}
            </div>
            <select
              id="override-mcVaultDirection"
              class="form-select"
              aria-label={$_('settings.minecraft.vault-direction')}
              bind:value={form.mcVaultDirection}>
              <option value="default">
                {$_('modals.server-override.state.default', {
                  values: {
                    value: $_(
                      `settings.minecraft.direction.${defaults?.mcVaultDirection ?? 'BOTH'}`,
                    ),
                  },
                })}
              </option>
              {#each VAULT_DIRECTIONS as direction (direction)}
                <option value={direction}>{$_(`settings.minecraft.direction.${direction}`)}</option>
              {/each}
            </select>
          {/if}
        </div>
        <div class="modal-footer">
          <button type="submit" class="btn btn-primary w-100" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
            {/if}
            {$_('common.save')}
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
    ADMIN_COMMANDS,
    VAULT_DIRECTIONS,
    VAULT_MODES,
    fieldErrorKey,
  } from '../../utils/settings.js';
  import { toggleListValue } from '../../utils/settings-extra.js';
  import {
    MC_FEATURES,
    effectiveVaultMode,
    overrideForm,
    overrideRequest,
    validateOverride,
  } from '../../utils/minecraft-settings.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  // "Override Settings…" of one server (13 §17 minecraft): the in-game controls in three states
  // (Default / On / Off). Everything on Default clears the override (`settings: null`).
  let { onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let server = $state.raw(null);
  let defaults = $state.raw(null);
  let form = $state(overrideForm(null));
  let submitted = $state(false);
  let saving = $state(false);

  const convert = $derived(effectiveVaultMode(form, defaults) === 'CONVERT');
  const errors = $derived(validateOverride(form, defaults));
  const shown = $derived(submitted ? errors : {});

  // What "Default" currently means for a switch: the saved panel value.
  const panelValue = (key) =>
    $_(
      defaults?.[key] === false
        ? 'modals.server-override.state.off'
        : 'modals.server-override.state.on',
    );

  /** open(serverRow of GET /servers, saved panel defaults) */
  export function open(row, panelDefaults) {
    server = row;
    defaults = panelDefaults;
    form = overrideForm(row.settings);
    submitted = false;
    saving = false;
    showModal(modalElement);
  }

  function setAdminCommand(command, enabled) {
    form.mcDisabledAdminCommands = toggleListValue(
      form.mcDisabledAdminCommands,
      command,
      !enabled,
      ADMIN_COMMANDS,
    );
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !server) return;
    submitted = true;
    if (Object.keys(errors).length > 0) return;

    saving = true;
    let result;
    try {
      result = await call(
        ApiUtil.put({
          path: marketPath(`/servers/${encodeURIComponent(server.id)}/settings`),
          body: overrideRequest(form, defaults),
        }),
      );
    } finally {
      saving = false;
    }
    if (!result.ok) {
      toastError($_, result);
      return;
    }
    showSuccessToast($_('modals.server-override.toast-saved'));
    hideModal(modalElement);
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
