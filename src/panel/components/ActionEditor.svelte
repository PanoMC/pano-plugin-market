<div class="card" data-field={path}>
  <div class="card-header d-flex align-items-center gap-2">
    <i class="fa-solid {TYPE_ICON[action.type] ?? 'fa-bolt'}" aria-hidden="true"></i>
    <b class="flex-grow-1">{$_(`enums.action-type.${action.type}`)}</b>
    {#if action.id}
      <span class="badge text-bg-secondary font-monospace">{action.id}</span>
    {/if}
    <!-- removing an action needs no extra right (11 §14.4): the button stays on a locked row -->
    <button
      type="button"
      class="btn btn-sm btn-link link-danger"
      aria-label={$_('components.action-editor.remove')}
      use:tooltip={[$_('components.action-editor.remove')]}
      onclick={() => onRemove()}>
      <i class="fa-solid fa-trash" aria-hidden="true"></i>
    </button>
  </div>

  <fieldset class="card-body" {disabled}>
    {#if disabled}
      <div class="text-body-secondary small mb-3">{$_('components.action-editor.locked')}</div>
    {/if}

    {#if showPhase && phaseOptions.length > 0}
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-phase">
          {$_('components.action-editor.phase')}
        </label>
        <div class="col-sm-9">
          <select
            id="{uid}-phase"
            class="form-select"
            class:is-invalid={errors.phase}
            data-field="{path}.phase"
            bind:value={action.phase}>
            {#each phaseOptions as phase (phase)}
              <option value={phase}>{$_(`enums.phase.${phase}`)}</option>
            {/each}
          </select>
          {@render feedback('phase')}
        </div>
      </div>
    {/if}

    {#if action.type === 'CREDIT'}
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-credit">
          {$_('components.action-editor.credit')}
        </label>
        <div class="col-sm-9">
          <input
            id="{uid}-credit"
            type="number"
            min="0"
            step="0.01"
            class="form-control"
            class:is-invalid={errors.value}
            data-field="{path}.value"
            placeholder={$_('components.action-editor.credit-placeholder')}
            bind:value={action.value} />
          {@render feedback('value')}
        </div>
      </div>
    {:else if action.type === 'PERMISSION'}
      <div class="row mb-3">
        <div class="col-sm-3 col-form-label">{$_('components.action-editor.nodes')}</div>
        <div class="col-sm-9" data-field="{path}.value">
          <TagInput
            bind:values={action.value}
            validate={(node) => NODE_PATTERN.test(node)}
            max={MAX_NODES}
            placeholder={$_('components.action-editor.nodes-placeholder')} />
          {@render feedback('value')}
          {#each nodeRowErrors as index (index)}
            <div class="invalid-feedback d-block">
              {action.value[index]}: {$_(actionErrorKey(errors[`value.${index}`]))}
            </div>
          {/each}
        </div>
      </div>
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-via">
          {$_('components.action-editor.via')}
        </label>
        <div class="col-sm-9">
          <select
            id="{uid}-via"
            class="form-select"
            data-field="{path}.via"
            value={action.via ?? 'PANO'}
            onchange={(e) => setVia(e.currentTarget.value)}>
            {#each PERMISSION_VIAS as via (via)}
              <option value={via}>{$_(`components.action-editor.via-${via}`)}</option>
            {/each}
          </select>
          <div class="form-text">
            {$_(`components.action-editor.via-${action.via ?? 'PANO'}-help`)}
          </div>
        </div>
      </div>
    {:else if action.type === 'COMMAND'}
      <div class="row mb-3">
        <div class="col-sm-3 col-form-label">{$_('components.action-editor.commands')}</div>
        <div class="col-sm-9 vstack gap-2" data-field="{path}.value">
          {#each action.value as _row, j (j)}
            <div class="input-group">
              <input
                type="text"
                class="form-control font-monospace"
                class:is-invalid={errors[`value.${j}`]}
                autocomplete="off"
                maxlength={MAX_COMMAND_LENGTH + 1}
                data-field="{path}.value.{j}"
                placeholder={$_('components.action-editor.command-placeholder')}
                aria-label={$_('components.action-editor.command-placeholder')}
                bind:this={inputs[j]}
                bind:value={action.value[j]}
                onblur={(e) => onCommandBlur(j, e.currentTarget)} />
              <button
                type="button"
                class="btn btn-outline-secondary"
                disabled={j === 0}
                aria-label={$_('common.move-up')}
                use:tooltip={[$_('common.move-up')]}
                onclick={() => moveCommand(j, -1)}>
                <i class="fa-solid fa-arrow-up" aria-hidden="true"></i>
              </button>
              <button
                type="button"
                class="btn btn-outline-secondary"
                disabled={j === action.value.length - 1}
                aria-label={$_('common.move-down')}
                use:tooltip={[$_('common.move-down')]}
                onclick={() => moveCommand(j, 1)}>
                <i class="fa-solid fa-arrow-down" aria-hidden="true"></i>
              </button>
              <button
                type="button"
                class="btn btn-outline-secondary"
                disabled={action.value.length <= 1}
                aria-label={$_('common.remove')}
                use:tooltip={[$_('common.remove')]}
                onclick={() => removeCommand(j)}>
                <i class="fa-solid fa-xmark" aria-hidden="true"></i>
              </button>
            </div>
            {#if errors[`value.${j}`]}
              <div class="invalid-feedback d-block mt-0">
                {$_(actionErrorKey(errors[`value.${j}`]))}
              </div>
            {/if}
          {/each}
          {@render feedback('value')}
          <div class="d-flex align-items-center gap-3">
            <button
              type="button"
              class="btn btn-link btn-sm px-0"
              disabled={action.value.length >= MAX_COMMANDS}
              onclick={addCommand}>
              <i class="fa-solid fa-plus me-1" aria-hidden="true"></i>
              {$_('components.action-editor.add-command')}
            </button>
            <div class="dropdown">
              <button
                type="button"
                class="btn btn-link btn-sm px-0"
                data-bs-toggle="dropdown"
                aria-expanded="false">
                {$_('components.action-editor.variables')}
              </button>
              <div
                class="dropdown-menu animate__animated animate__fadeIn overflow-y-auto"
                style="max-height: 16rem;">
                {#each variables as name (name)}
                  <button
                    type="button"
                    class="dropdown-item font-monospace"
                    onclick={() => insert(name)}>
                    {`{${name}}`}
                  </button>
                {/each}
              </div>
            </div>
          </div>
          {#each unknownVars as name (name)}
            <div class="text-warning small">
              {$_('components.action-editor.unknown-variable', { values: { name: `{${name}}` } })}
            </div>
          {/each}
        </div>
      </div>
    {:else if action.type === 'WEBHOOK'}
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-url">
          {$_('components.action-editor.url')}
        </label>
        <div class="col-sm-9">
          <input
            id="{uid}-url"
            type="url"
            class="form-control"
            class:is-invalid={errors['value.url']}
            autocomplete="off"
            data-field="{path}.value.url"
            placeholder={$_('components.action-editor.url-placeholder')}
            bind:value={action.value.url} />
          {@render feedback('value.url')}
        </div>
      </div>
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-format">
          {$_('components.action-editor.format')}
        </label>
        <div class="col-sm-9">
          <select
            id="{uid}-format"
            class="form-select"
            data-field="{path}.value.format"
            value={action.value.format}
            onchange={(e) => setFormat(e.currentTarget.value)}>
            {#each WEBHOOK_FORMATS as format (format)}
              <option value={format}>{$_(`components.action-editor.format-${format}`)}</option>
            {/each}
          </select>
        </div>
      </div>
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-signing">
          {$_('components.action-editor.signing')}
        </label>
        <div class="col-sm-9">
          <select
            id="{uid}-signing"
            class="form-select"
            data-field="{path}.value.signing"
            disabled={action.value.format === 'DISCORD'}
            bind:value={action.value.signing}>
            {#each WEBHOOK_SIGNINGS as signing (signing)}
              <option value={signing}>{$_(`components.action-editor.signing-${signing}`)}</option>
            {/each}
          </select>
        </div>
      </div>
      {#if action.value.signing === 'HMAC_SHA256' && action.value.format !== 'DISCORD'}
        <div class="row mb-3 align-items-center">
          <label class="col-sm-3 col-form-label" for="{uid}-secret">
            {$_('components.action-editor.secret')}
          </label>
          <div class="col-sm-9">
            <div class="input-group">
              <input
                id="{uid}-secret"
                type={secretClear ? 'text' : 'password'}
                class="form-control font-monospace"
                autocomplete="new-password"
                data-field="{path}.value.secret"
                placeholder={$_('components.action-editor.secret-placeholder')}
                bind:value={action.value.secret}
                onfocus={onSecretFocus}
                onblur={onSecretBlur} />
              <button type="button" class="btn btn-outline-secondary" onclick={generate}>
                {$_('components.action-editor.generate')}
              </button>
            </div>
            {#if secretClear}
              <div class="form-text">{$_('components.action-editor.secret-generated')}</div>
            {/if}
          </div>
        </div>
      {/if}
    {/if}

    {#if hasDelay(action.type)}
      <div class="row mb-3 align-items-center">
        <label class="col-sm-3 col-form-label" for="{uid}-delay">
          {$_('components.action-editor.delay')}
        </label>
        <div class="col-sm-9">
          <div class="input-group">
            <input
              id="{uid}-delay"
              type="number"
              min="0"
              max={MAX_DELAY_SECONDS}
              step="1"
              class="form-control"
              class:is-invalid={errors.delay}
              data-field="{path}.delay"
              bind:value={action.delay} />
            <span class="input-group-text">{$_('components.action-editor.seconds')}</span>
          </div>
          {@render feedback('delay')}
        </div>
      </div>
    {/if}

    {#if serverControls}
      <div class="row mb-3">
        <div class="col-sm-3 col-form-label">{$_('components.action-editor.server-mode')}</div>
        <div class="col-sm-9">
          {#if servers.length === 0}
            <div class="text-body-secondary">{$_('components.action-editor.no-servers')}</div>
            {@render feedback('serverMode')}
          {:else}
            <div
              class="btn-group flex-wrap"
              class:is-invalid={errors.serverMode}
              role="radiogroup"
              data-field="{path}.serverMode"
              aria-label={$_('components.action-editor.server-mode')}>
              {#each SERVER_MODES as mode (mode)}
                <input
                  type="radio"
                  class="btn-check"
                  name="{uid}-mode"
                  id="{uid}-mode-{mode}"
                  autocomplete="off"
                  value={mode}
                  bind:group={action.serverMode} />
                <label
                  class="btn btn-outline-secondary"
                  class:border-danger={errors.serverMode && action.serverMode === mode}
                  for="{uid}-mode-{mode}">
                  {$_(`components.action-editor.mode-${mode}`)}
                </label>
              {/each}
            </div>
            {#if errors.serverMode}
              <div class="invalid-feedback d-block">
                {$_(actionErrorKey(errors.serverMode))}
                {#if errors.serverMode === 'SERVER_CHOICES_REQUIRED'}
                  <button
                    type="button"
                    class="btn btn-link btn-sm p-0 ms-1"
                    onclick={scrollToChoices}>
                    {$_('components.action-editor.go-to-choices')}
                  </button>
                {/if}
              </div>
            {/if}
            {#if action.serverMode === 'FIXED'}
              <div class="mt-2" data-field="{path}.targetServers">
                <ServerPicker
                  {servers}
                  bind:selected={action.targetServers}
                  invalid={!!errors.targetServers}
                  name="{uid}-targets" />
                {@render feedback('targetServers')}
                {#if action.type === 'PERMISSION' && (action.targetServers ?? []).length === 0}
                  <div class="form-text">
                    {$_('components.action-editor.all-permission-servers')}
                  </div>
                {/if}
              </div>
            {/if}
          {/if}
        </div>
      </div>
    {/if}

    {#if action.type === 'COMMAND'}
      <div class="row mb-3 align-items-center">
        <div class="col-sm-3 col-form-label">{$_('components.action-editor.requires-online')}</div>
        <div class="col-sm-9">
          <div class="form-check form-switch m-0">
            <input
              class="form-check-input"
              type="checkbox"
              role="switch"
              id="{uid}-online"
              aria-label={$_('components.action-editor.requires-online')}
              bind:checked={action.requiresOnline} />
          </div>
        </div>
      </div>
    {/if}

    {#if hasPerUnit(action.type)}
      <div class="row align-items-center">
        <div class="col-sm-3 col-form-label">{$_('components.action-editor.per-unit')}</div>
        <div class="col-sm-9">
          <div class="form-check form-switch m-0">
            <input
              class="form-check-input"
              class:is-invalid={errors.perUnit}
              type="checkbox"
              role="switch"
              id="{uid}-per-unit"
              data-field="{path}.perUnit"
              aria-label={$_('components.action-editor.per-unit')}
              use:tooltip={[$_('components.action-editor.per-unit-help')]}
              bind:checked={action.perUnit} />
          </div>
          {@render feedback('perUnit')}
        </div>
      </div>
    {/if}
  </fieldset>
</div>

{#snippet feedback(key)}
  {#if errors[key]}
    <div class="invalid-feedback d-block">{$_(actionErrorKey(errors[key]))}</div>
  {/if}
{/snippet}

<script>
  import { tick, untrack } from 'svelte';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ } from '../../i18n';
  import {
    MAX_COMMANDS,
    MAX_COMMAND_LENGTH,
    MAX_DELAY_SECONDS,
    MAX_NODES,
    NODE_PATTERN,
    PERMISSION_VIAS,
    SECRET_MASK,
    SERVER_MODES,
    WEBHOOK_FORMATS,
    WEBHOOK_SIGNINGS,
    actionErrorKey,
    generateSecret,
    hasDelay,
    hasPerUnit,
    insertVariable,
    isServerAction,
    phasesForType,
  } from '../utils/actions.js';
  import { moveRow } from './product/fields.js';
  import ServerPicker from './ServerPicker.svelte';
  import TagInput from './TagInput.svelte';

  // action: the wire shape of 01 §2.2 (`id` only on a loaded action). servers: GET /servers rows.
  // serverChoices: the product-level buyer-choice ids. phases / types: what the host form allows.
  // variables: names offered by the helper. errors: keys relative to this action ('value', 'value.1',
  // 'value.url', 'delay', ...). path: the dotted path of this action for data-field. unknownVars:
  // non-blocking `{field.x}` warnings. showPhase = false hides the phase select (payouts).
  let {
    action = $bindable(),
    servers = [],
    serverChoices = [],
    phases = ['GRANT', 'REVOKE'],
    types = ['CREDIT', 'PERMISSION', 'COMMAND', 'WEBHOOK'],
    variables = [],
    errors = {},
    path = 'actions.0',
    unknownVars = [],
    showPhase = true,
    disabled = false,
    onRemove = () => {},
  } = $props();

  const uid = $props.id();
  const TYPE_ICON = {
    CREDIT: 'fa-coins',
    PERMISSION: 'fa-key',
    COMMAND: 'fa-terminal',
    WEBHOOK: 'fa-link',
  };

  let inputs = $state([]);
  let lastFocus = $state(null);
  let secretClear = $state(false);
  // a loaded webhook secret arrives masked; an untouched blur puts the mask back (13 §16.3 protocol)
  let hadMask = $state(
    untrack(() => action.type === 'WEBHOOK' && action.value?.secret === SECRET_MASK),
  );

  // The current phase stays listed when it became unavailable, so the select still shows it (and the
  // row is marked invalid) instead of silently jumping to another phase.
  const phaseOptions = $derived.by(() => {
    const list = phasesForType(action.type, phases);
    return list.includes(action.phase) || !action.phase ? list : [action.phase, ...list];
  });
  const serverControls = $derived(isServerAction(action) && types.includes(action.type));
  const nodeRowErrors = $derived(
    Object.keys(errors)
      .filter((key) => /^value\.\d+$/.test(key))
      .map((key) => Number(key.slice(6))),
  );

  function setVia(via) {
    action.via = via;
    if (via === 'SERVER') {
      action.serverMode ??= 'FIXED';
      action.targetServers ??= [];
    }
  }

  function setFormat(format) {
    action.value.format = format;
    if (format === 'DISCORD') action.value.signing = 'NONE';
  }

  function onSecretFocus() {
    if (action.value.secret === SECRET_MASK) action.value.secret = '';
  }

  function onSecretBlur() {
    if (action.value.secret === '' && hadMask) action.value.secret = SECRET_MASK;
  }

  function generate() {
    action.value.secret = generateSecret((bytes) => crypto.getRandomValues(bytes));
    secretClear = true;
  }

  function addCommand() {
    if (action.value.length < MAX_COMMANDS) action.value.push('');
  }

  function removeCommand(index) {
    if (action.value.length > 1) action.value = action.value.filter((_row, i) => i !== index);
  }

  function moveCommand(index, delta) {
    action.value = moveRow(action.value, index, delta);
  }

  function onCommandBlur(index, element) {
    lastFocus = { index, start: element.selectionStart, end: element.selectionEnd };
    const text = String(action.value[index] ?? '');
    const stripped = text.replace(/^\s*\//, '');
    if (stripped !== text) action.value[index] = stripped;
  }

  async function insert(name) {
    const index = lastFocus?.index ?? action.value.length - 1;
    if (index < 0) return;
    const { text, caret } = insertVariable(
      action.value[index],
      lastFocus?.start,
      lastFocus?.end,
      name,
    );
    action.value[index] = text;
    lastFocus = { index, start: caret, end: caret };
    await tick();
    const element = inputs[index];
    if (element) {
      element.focus();
      element.setSelectionRange(caret, caret);
    }
  }

  function scrollToChoices() {
    document
      .querySelector('[data-field="serverChoices"]')
      ?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
  }
</script>
