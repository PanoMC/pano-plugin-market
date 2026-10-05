<div class="vstack gap-1" class:is-invalid={invalid} data-field={field} role="group">
  {#if servers.length === 0 && unknownIds.length === 0}
    <div class="text-body-secondary">{$_('components.action-editor.no-servers')}</div>
  {/if}

  {#each servers as server (server.id)}
    <div class="d-flex align-items-center gap-2">
      <div class="form-check m-0 flex-grow-1">
        <input
          class="form-check-input"
          type={multiple ? 'checkbox' : 'radio'}
          name={groupName}
          id="{groupName}-{server.id}"
          checked={selected.includes(server.id)}
          disabled={disabledIds.includes(server.id)}
          onchange={(e) => toggle(server.id, e.currentTarget.checked)} />
        <label class="form-check-label" for="{groupName}-{server.id}">
          {server.name}
        </label>
      </div>
      {#if server.type}
        <span class="badge text-bg-secondary">{server.type}</span>
      {/if}
      <span class="badge text-bg-{server.connected ? 'success' : 'secondary'}">
        {$_(
          server.connected
            ? 'components.server-picker.connected'
            : 'components.server-picker.disconnected',
        )}
      </span>
      {#if server.marketState === 'COMPONENT_MISSING'}
        <i
          class="fa-solid fa-triangle-exclamation text-warning"
          role="img"
          aria-label={$_('components.server-picker.no-plugin')}
          use:tooltip={[$_('components.server-picker.no-plugin')]}></i>
      {:else if server.marketState === 'VERSION_MISMATCH'}
        <i
          class="fa-solid fa-triangle-exclamation text-warning"
          role="img"
          aria-label={$_('components.server-picker.version-mismatch-label')}
          use:tooltip={[
            $_('components.server-picker.version-mismatch', {
              values: {
                have: server.mcComponentVersion ?? '?',
                want: server.requiredVersion ?? '?',
              },
            }),
          ]}></i>
      {:else if server.marketState === 'OFFLINE'}
        <span class="text-body-secondary small">{$_('components.server-picker.offline')}</span>
      {/if}
    </div>
  {/each}

  {#each unknownIds as id (id)}
    <div class="d-flex align-items-center gap-2">
      <span class="text-body-secondary flex-grow-1">
        {$_('components.server-picker.unknown', { values: { id } })}
      </span>
      <button
        type="button"
        class="btn btn-sm btn-link link-danger"
        onclick={() => toggle(id, false)}>
        {$_('common.remove')}
      </button>
    </div>
  {/each}
</div>

<script>
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ } from '../../i18n';

  // servers: GET /servers rows ({id, name, type, connected, marketState, mcComponentVersion,
  // requiredVersion}). selected: the chosen ids (at most one when `multiple` is false). Ids that are no
  // longer a server are listed as "Unknown server #id" with a remove button (13 §3.4).
  let {
    servers = [],
    selected = $bindable([]),
    multiple = true,
    disabledIds = [],
    invalid = false,
    field = undefined,
    name = 'server-picker',
  } = $props();

  // one radio group / id prefix per instance (stable between server render and hydration)
  const uid = $props.id();
  const groupName = $derived(`${name}-${uid}`);

  const unknownIds = $derived(selected.filter((id) => !servers.some((s) => s.id === id)));

  function toggle(id, on) {
    if (!multiple) selected = on ? [id] : [];
    else if (on) selected = selected.includes(id) ? selected : [...selected, id];
    else selected = selected.filter((value) => value !== id);
  }
</script>
