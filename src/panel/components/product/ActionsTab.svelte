<div class="vstack gap-3 animate__animated animate__fadeIn">
  <div class="card" data-field="serverChoices">
    <CardHeader>
      <div slot="left">{$_('pages.create-product.actions.server-choices')}</div>
    </CardHeader>
    <div class="card-body">
      <ServerPicker
        {servers}
        bind:selected={product.serverChoices}
        invalid={!!errors.serverChoices}
        name="product-server-choices" />
      {#if errors.serverChoices}
        <div class="invalid-feedback d-block">{$_(actionErrorKey(errors.serverChoices))}</div>
      {/if}
    </div>
  </div>

  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.create-product.actions.title', { values: { count: product.actions.length } })}
      </div>
      <div slot="right">
        <div class="dropdown">
          <button
            type="button"
            class="btn btn-sm btn-link"
            data-bs-toggle="dropdown"
            aria-expanded="false"
            title={$_('common.actions')}
            aria-label={$_('common.actions')}>
            <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
          </button>
          <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
            <button
              type="button"
              class="dropdown-item"
              disabled={product.actions.length >= MAX_ACTIONS}
              onclick={openModal}>
              <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
              {$_('pages.create-product.add-action-title')}
            </button>
          </div>
        </div>
      </div>
    </CardHeader>

    {#if errors.actions}
      <div class="card-body pb-0">
        <div class="invalid-feedback d-block" data-field="actions">
          {$_(actionErrorKey(errors.actions))}
        </div>
      </div>
    {/if}

    {#if product.actions.length === 0}
      <NoContent icon="" />
    {/if}
  </div>

  {#each groups as group (group.phase)}
    <div class="vstack gap-3">
      <h6 class="text-body-secondary text-uppercase small mb-0">
        {group.phase === 'OTHER'
          ? $_('pages.create-product.actions.other-phase')
          : $_(`enums.phase.${group.phase}`)}
      </h6>
      {#each group.items as item (item.index)}
        <ActionEditor
          bind:action={product.actions[item.index]}
          {servers}
          serverChoices={product.serverChoices}
          {phases}
          {variables}
          errors={errorsOf(item.index)}
          path="actions.{item.index}"
          unknownVars={unknownVariables(item.action, product)}
          onRemove={() => remove(item.index)} />
      {/each}
    </div>
  {/each}
</div>

<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('pages.create-product.add-action-title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body">
        <div class="row g-2">
          {#each ACTION_TYPES as type (type)}
            <div class="col-6">
              <button
                type="button"
                class="btn btn-outline-secondary w-100 h-100 text-start p-3"
                onclick={() => add(type)}>
                <i class="fa-solid {TYPE_ICON[type]} mb-2 d-block fs-4" aria-hidden="true"></i>
                <b class="d-block">{$_(`pages.create-product.action-type.${type}`)}</b>
                <span class="small text-body-secondary">
                  {$_(`pages.create-product.action-type-desc.${type}`)}
                </span>
              </button>
            </div>
          {/each}
        </div>
      </div>
    </div>
  </div>
</div>

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import {
    ACTION_TYPES,
    MAX_ACTIONS,
    actionErrorKey,
    allowedPhases,
    groupByPhase,
    newAction,
    unknownVariables,
    variableNames,
  } from '../../utils/actions.js';
  import { ACTION_VARIABLES } from '../action-variables.js';
  import ActionEditor from '../ActionEditor.svelte';
  import ServerPicker from '../ServerPicker.svelte';

  // Props contract of every tab: product (bindable), errors (dotted path -> code), ctx, servers.
  let { product = $bindable(), errors = {}, servers = [] } = $props();

  const TYPE_ICON = {
    CREDIT: 'fa-coins',
    PERMISSION: 'fa-key',
    COMMAND: 'fa-terminal',
    WEBHOOK: 'fa-link',
  };

  let modalElement = $state(null);

  const phases = $derived(allowedPhases(product.billingMode));
  const groups = $derived(groupByPhase(product.actions));
  const variables = $derived(variableNames(product, ACTION_VARIABLES));

  // Errors of one action with the `actions.<i>.` prefix removed (ActionEditor keys are relative).
  function errorsOf(index) {
    const prefix = `actions.${index}.`;
    const out = {};
    for (const [path, code] of Object.entries(errors)) {
      if (path.startsWith(prefix)) out[path.slice(prefix.length)] = code;
    }
    return out;
  }

  function openModal() {
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
  }

  function add(type) {
    const action = newAction(type);
    // a site with exactly one server needs no choice: preselect it for a command
    if (type === 'COMMAND' && servers.length === 1) action.targetServers = [servers[0].id];
    product.actions.push(action);
    if (modalElement && window.bootstrap)
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
  }

  function remove(index) {
    product.actions = product.actions.filter((_row, i) => i !== index);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
