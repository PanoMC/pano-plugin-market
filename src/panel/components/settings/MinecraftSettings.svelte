<ServerOverrideModal bind:this={overrideModal} onSaved={refreshServers} />

<div class="vstack gap-3">
  <div class="alert alert-info d-flex align-items-start mb-0" role="alert">
    <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('settings.minecraft.info.title')}</b>
      <div>{$_('settings.minecraft.info.body')}</div>
      {#if downloadUrl}
        <a class="alert-link" href={downloadUrl} target="_blank" rel="noopener">
          {$_('settings.minecraft.info.download')}
        </a>
      {/if}
    </div>
  </div>

  {#if vaultProviderWarning(draft)}
    <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
      <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.minecraft.vault-provider-warning.title')}</b>
        <div>{$_('settings.minecraft.vault-provider-warning.body')}</div>
      </div>
    </div>
  {/if}

  <div class="card">
    <div class="card-body">
      {#each MC_FEATURES as feature (feature.key)}
        <SwitchRow
          id="setting-{feature.key}"
          label={$_(`settings.minecraft.feature.${feature.id}`)}
          hint={$_(`settings.minecraft.feature-hint.${feature.id}`)}
          error={message(feature.key)}
          bind:checked={draft[feature.key]} />

        {#if feature.key === 'mcAdminCommands'}
          <SettingRow
            id="setting-mcDisabledAdminCommands"
            label={$_('settings.minecraft.admin-commands-list')}
            hint={$_('settings.minecraft.admin-commands-list-hint')}
            error={message('mcDisabledAdminCommands')}>
            <div
              class="vstack gap-1"
              role="group"
              aria-label={$_('settings.minecraft.admin-commands-list')}>
              {#each ADMIN_COMMANDS as command (command)}
                <div class="form-check m-0">
                  <input
                    id="setting-mcDisabledAdminCommands-{command}"
                    class="form-check-input"
                    type="checkbox"
                    disabled={!draft.mcAdminCommands}
                    checked={!draft.mcDisabledAdminCommands.includes(command)}
                    onchange={(event) => setAdminCommand(command, event.currentTarget.checked)} />
                  <label class="form-check-label" for="setting-mcDisabledAdminCommands-{command}">
                    {$_(`settings.minecraft.admin-command.${command}`)}
                  </label>
                </div>
              {/each}
            </div>
          </SettingRow>
        {/if}

        {#if feature.key === 'mcBroadcast'}
          <SettingRow
            id="setting-mcBroadcastTemplate"
            label={$_('settings.minecraft.broadcast-template')}
            hint={$_('settings.minecraft.broadcast-template-hint', {
              values: { variables: variableList },
            })}
            error={message('mcBroadcastTemplate')}>
            <textarea
              id="setting-mcBroadcastTemplate"
              class="form-control font-monospace"
              class:is-invalid={shown.mcBroadcastTemplate}
              rows="2"
              maxlength="256"
              disabled={!draft.mcBroadcast}
              placeholder={$_('settings.minecraft.broadcast-template')}
              bind:value={draft.mcBroadcastTemplate}></textarea>
            <div class="form-text">{$_('settings.minecraft.broadcast-preview')}</div>
            <div
              class="rounded p-2 mt-1 text-bg-dark font-monospace"
              data-field="broadcast-preview">
              {#each preview as segment, index (index)}
                <span
                  style:color={segment.color}
                  class:fw-bold={segment.bold}
                  class:fst-italic={segment.italic}>{segment.text}</span>
              {/each}
            </div>
          </SettingRow>
        {/if}
      {/each}

      <SettingRow
        id="setting-mcVaultMode"
        label={$_('settings.minecraft.vault-mode')}
        hint={$_('settings.minecraft.vault-mode-hint')}
        error={message('mcVaultMode')}>
        <div
          class="vstack gap-1"
          role="radiogroup"
          aria-label={$_('settings.minecraft.vault-mode')}>
          {#each VAULT_MODES as mode (mode)}
            <div class="form-check m-0">
              <input
                id="setting-mcVaultMode{mode === 'OFF' ? '' : `-${mode}`}"
                class="form-check-input"
                type="radio"
                name="mcVaultMode"
                value={mode}
                bind:group={draft.mcVaultMode} />
              <label
                class="form-check-label"
                for="setting-mcVaultMode{mode === 'OFF' ? '' : `-${mode}`}">
                {$_(`settings.minecraft.vault-option.${mode}`)}
              </label>
              <div class="form-text mt-0">{$_(`settings.minecraft.vault-hint.${mode}`)}</div>
            </div>
          {/each}
        </div>
      </SettingRow>

      {#if draft.mcVaultMode === 'CONVERT'}
        <SettingRow
          id="setting-mcVaultRate"
          label={$_('settings.minecraft.vault-rate')}
          hint={$_('settings.minecraft.vault-rate-hint')}
          error={message('mcVaultRate')}>
          <input
            id="setting-mcVaultRate"
            type="number"
            min="0.000001"
            step="any"
            class="form-control"
            class:is-invalid={shown.mcVaultRate}
            bind:value={draft.mcVaultRate} />
        </SettingRow>

        <SettingRow
          id="setting-mcVaultDirection"
          label={$_('settings.minecraft.vault-direction')}
          error={message('mcVaultDirection')}>
          <select
            id="setting-mcVaultDirection"
            class="form-select"
            class:is-invalid={shown.mcVaultDirection}
            bind:value={draft.mcVaultDirection}>
            {#each VAULT_DIRECTIONS as direction (direction)}
              <option value={direction}>{$_(`settings.minecraft.direction.${direction}`)}</option>
            {/each}
          </select>
        </SettingRow>
      {/if}
    </div>

    <div class="card-footer d-flex justify-content-start">
      <button
        type="button"
        class="btn btn-secondary"
        onclick={onSave}
        disabled={saving || !isDirty}>
        {#if saving}
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {/if}
        {$_('common.save')}
      </button>
    </div>
  </div>

  {#if extraError && !servers}
    <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
      <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.minecraft.servers-error.title')}</b>
        <div>{$_('settings.minecraft.servers-error.body')}</div>
      </div>
    </div>
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('settings.minecraft.servers.title', { values: { count: rows.length } })}
        </div>
      </CardHeader>

      {#if rows.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.name')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.type')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.state')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.version')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.integrations')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.waiting')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.minecraft.servers.override')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each rows as row (row.id)}
                <tr>
                  <th scope="row" class="align-middle text-center">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        aria-expanded="false"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <span class="fas fa-ellipsis-v"></span>
                      </button>
                      <div class="dropdown-menu dropdown-menu-start">
                        <button
                          type="button"
                          class="dropdown-item"
                          onclick={() => openOverride(row)}>
                          <i class="fas fa-sliders me-2" aria-hidden="true"></i>
                          {$_('settings.minecraft.servers.override-settings')}
                        </button>
                        {#if row.overrides > 0}
                          <button
                            type="button"
                            class="dropdown-item"
                            disabled={clearing === row.id}
                            onclick={() => clearOverride(row)}>
                            <i class="fas fa-rotate-left me-2" aria-hidden="true"></i>
                            {$_('settings.minecraft.servers.clear-override')}
                          </button>
                        {/if}
                        {#if row.downloadUrl}
                          <a
                            class="dropdown-item"
                            href={row.downloadUrl}
                            target="_blank"
                            rel="noopener">
                            <i class="fas fa-download me-2" aria-hidden="true"></i>
                            {$_('settings.minecraft.servers.download')}
                          </a>
                        {/if}
                      </div>
                    </div>
                  </th>
                  <td class="align-middle">{row.name}</td>
                  <td class="align-middle">{row.type}</td>
                  <td class="align-middle">
                    {#if row.state}
                      <span class="badge {row.stateClass}">
                        {$_(`settings.minecraft.state.${row.state}`)}
                      </span>
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">
                    {#if row.version || row.required}
                      {$_('settings.minecraft.servers.version-pair', {
                        values: { have: row.version || '?', want: row.required || '?' },
                      })}
                    {/if}
                  </td>
                  <td class="align-middle">
                    {#each row.integrations as integration (integration)}
                      <span class="badge text-bg-secondary me-1">{integration}</span>
                    {/each}
                  </td>
                  <td class="align-middle">{row.waiting}</td>
                  <td class="align-middle">
                    {#if row.overrides > 0}
                      <span class="badge text-bg-info">
                        {$_('settings.minecraft.servers.override-badge', {
                          values: { count: row.overrides },
                        })}
                      </span>
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  {/if}
</div>

<script>
  import { untrack } from 'svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { _, showSuccessToast } from '../../../i18n';
  import ServerOverrideModal from '../modals/ServerOverrideModal.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import { call } from '../../utils/api.js';
  import {
    ADMIN_COMMANDS,
    SECTION_KEYS,
    VAULT_DIRECTIONS,
    VAULT_MODES,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
  } from '../../utils/settings.js';
  import { toggleListValue } from '../../utils/settings-extra.js';
  import {
    BROADCAST_VARIABLES,
    MC_FEATURES,
    activeMinecraftKeys,
    clearOverrideRequest,
    previewSegments,
    previewValues,
    serverRows,
    validateMinecraft,
    vaultProviderWarning,
  } from '../../utils/minecraft-settings.js';
  import { toastError } from '../../utils/toast.js';

  // settings = GET /settings, extra = GET /servers ({ items[] }), null when that request failed.
  let { settings: initial = {}, extra = null, extraError = null } = $props();

  const KEYS = SECTION_KEYS.minecraft;
  const start = untrack(() => initial ?? {});
  const variableList = BROADCAST_VARIABLES.map((name) => `{${name}}`).join(' ');

  let overrideModal = $state(null);
  let settings = $state.raw(start);
  const startServers = untrack(() => extra?.items);
  let servers = $state.raw(Array.isArray(startServers) ? startServers : null);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let clearing = $state(null);
  let serverMark = $state.raw(null);

  const rows = $derived(serverRows(servers));
  const downloadUrl = $derived(rows.find((row) => row.downloadUrl)?.downloadUrl ?? null);
  const preview = $derived(
    previewSegments(draft.mcBroadcastTemplate, previewValues(settings.storeName)),
  );
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateMinecraft(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const keys = $derived(activeMinecraftKeys(draft));
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, keys)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  // Checked = the sub-command is on; the stored list holds the switched-off ones.
  function setAdminCommand(command, enabled) {
    draft.mcDisabledAdminCommands = toggleListValue(
      draft.mcDisabledAdminCommands,
      command,
      !enabled,
      ADMIN_COMMANDS,
    );
  }

  async function refreshServers() {
    const result = await call(api.panel.get({ path: '/servers' }));
    if (result.ok && Array.isArray(result.body?.items)) servers = result.body.items;
  }

  function openOverride(row) {
    const server = (servers ?? []).find((s) => s.id === row.id);
    // The override form shows what "Default" means: the saved panel defaults, not unsaved edits.
    if (server) overrideModal?.open(server, seedValues(settings, KEYS));
  }

  async function clearOverride(row) {
    if (clearing !== null) return;
    clearing = row.id;
    try {
      const result = await call(
        api.panel.put({
          path: `/servers/${encodeURIComponent(row.id)}/settings`,
          body: clearOverrideRequest(),
        }),
      );
      if (!result.ok) {
        toastError($_, result);
        return;
      }
      showSuccessToast($_('settings.minecraft.servers.toast-cleared'));
      await refreshServers();
    } finally {
      clearing = null;
    }
  }

  async function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    if (Object.keys(clientErrors).length > 0) {
      reportFailure({ status: 'invalid', errors: clientErrors }, KEYS);
      return;
    }
    saving = true;
    try {
      const result = await saveSection({
        baseline: settings,
        values: draft,
        keys,
        errors: clientErrors,
        order: KEYS,
      });
      if (result.status === 'saved') {
        const next = (await fetchSettings()) ?? { ...settings, ...result.sent };
        settings = next;
        draft = seedValues(next, KEYS);
        submitted = false;
        serverMark = null;
      } else if (result.status === 'failed' && Object.keys(result.errors).length > 0) {
        serverMark = { errors: result.errors, draftKey };
      }
    } finally {
      saving = false;
    }
  }
</script>
