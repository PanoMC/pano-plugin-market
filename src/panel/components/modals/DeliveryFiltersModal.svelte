<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.delivery-filters.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <select
            class="form-select"
            aria-label={$_('modals.delivery-filters.server')}
            bind:value={values.serverId}>
            <option value="">{$_('modals.delivery-filters.server')}</option>
            {#each servers as server (server.id)}
              <option value={String(server.id)}>{server.name}</option>
            {/each}
          </select>

          <select
            class="form-select"
            aria-label={$_('modals.delivery-filters.action-type')}
            bind:value={values.actionType}>
            <option value="">{$_('modals.delivery-filters.action-type')}</option>
            {#each ACTION_TYPES as value (value)}
              <option {value}>{$_(`enums.action-type.${value}`)}</option>
            {/each}
          </select>
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit">{$_('common.apply')}</button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import { ACTION_TYPES, normalizeFilters } from '../../utils/deliveries.js';

  // filters: the URL filters of the list; onApply({ serverId, actionType }) (strings, '' = off);
  // the page navigates.
  let { filters = {}, onApply = () => {} } = $props();

  let modalElement = $state(null);
  let values = $state({ serverId: '', actionType: '' });
  let servers = $state([]);

  async function loadServers() {
    const result = await call(ApiUtil.get({ path: marketPath('/servers') }));
    servers = result.ok && Array.isArray(result.body.servers) ? result.body.servers : [];
  }

  export function open() {
    const f = normalizeFilters(filters);
    values = { serverId: f.serverId, actionType: f.actionType };
    loadServers();
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
    }
  }

  function submit(event) {
    event.preventDefault();
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    }
    onApply({ ...values });
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
