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
        <button
          type="button"
          class="btn btn-sm btn-primary"
          disabled={product.actions.length >= MAX_ACTIONS}
          onclick={openModal}>
          <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
          {$_('pages.create-product.add-action-title')}
        </button>
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
      <NoContent />
    {/if}
  </div>

  {#each groups as group (group.phase)}
    <div class="vstack gap-3">
      <h6 class="text-body-secondary text-uppercase small mb-0">
        {group.phase === 'OTHER'
          ? $_('pages.create-product.actions.other-phase')
          : $_(`enums.phase.${group.phase}`)}
      </h6>
      <div class="accordion">
        {#each group.items as item (item.index)}
          <ActionEditor
            accordion
            bind:action={product.actions[item.index]}
            disabled={actionLocked(item.action, user)}
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
        <div class="list-group">
          {#each addableTypes as type (type)}
            <button
              type="button"
              class="list-group-item list-group-item-action fw-normal d-flex align-items-center gap-3 py-3"
              onclick={() => add(type)}>
              <!-- emphasis on a subtle tile: the theme pairs these two, so the icon reads in light and dark -->
              <span
                class="d-inline-flex align-items-center justify-content-center rounded bg-primary-subtle text-primary-emphasis flex-shrink-0"
                style="width: 2.5rem; height: 2.5rem;">
                <i class="fa-solid {TYPE_ICON[type]} fs-5" aria-hidden="true"></i>
              </span>
              <span>
                <span class="d-block text-body text-capitalize"
                  >{$_(`pages.create-product.action-type.${type}`)}</span>
                <span class="d-block text-body-secondary"
                  >{$_(`pages.create-product.action-type-desc.${type}`)}</span>
              </span>
            </button>
          {/each}
        </div>
      </div>
    </div>
  </div>
</div>

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import {
    MAX_ACTIONS,
    actionErrorKey,
    actionLocked,
    addableActionTypes,
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

  // Rows the caller could not save are disabled and the Add Action modal offers only the types they
  // could save (11 §14.4); cosmetic, the server's ActionGuard decides.
  const user = $derived($page.data?.user);
  const addableTypes = $derived(addableActionTypes(user));

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
