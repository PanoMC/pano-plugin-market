<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.export-orders.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <div
            class="vstack gap-1 border rounded p-2 overflow-auto"
            class:is-invalid={noColumns}
            class:border-danger={noColumns}
            style="max-height: 320px;">
            {#each EXPORT_COLUMNS as column (column.key)}
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="checkbox"
                  id="export-orders-{column.key}"
                  value={column.key}
                  disabled={column.pii && !piiAllowed}
                  bind:group={checked} />
                <label class="form-check-label" for="export-orders-{column.key}">
                  {$_(`modals.export-orders.columns.${column.key}`)}
                </label>
              </div>
            {/each}
          </div>

          <select
            class="form-select"
            aria-label={$_('modals.export-orders.delimiter')}
            bind:value={delimiter}>
            {#each DELIMITERS as value (value)}
              <option {value}
                >{$_(`modals.export-orders.delimiters.${DELIMITER_KEYS[value]}`)}</option>
            {/each}
          </select>
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit">{$_('common.download')}</button>
        </div>
      </form>

      <a bind:this={link} href="/" download target="market-download" hidden aria-hidden="true"
        >{$_('common.download')}</a>
      <iframe name="market-download" title={$_('common.download')} hidden aria-hidden="true"
      ></iframe>
    </div>
  </div>
</div>

<script>
  import { buildQueryParams } from '@panomc/sdk/utils/api';
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import {
    DEFAULT_EXPORT_COLUMNS,
    DELIMITERS,
    EXPORT_COLUMNS,
    canExportPii,
    exportUrl,
    sanitizeColumns,
  } from '../orders/filters.js';

  const DELIMITER_KEYS = { ',': 'comma', ';': 'semicolon', tab: 'tab' };

  // filters: the URL filters of the list; they are applied to the export implicitly.
  let { filters = {} } = $props();

  let modalElement = $state(null);
  let link = $state(null);
  let checked = $state([...DEFAULT_EXPORT_COLUMNS]);
  let delimiter = $state(',');
  let submitted = $state(false);

  const user = $derived($page.data?.user);
  const piiAllowed = $derived(canExportPii(user));
  const columns = $derived(sanitizeColumns(checked, user));
  const noColumns = $derived(submitted && columns.length === 0);

  export function open() {
    checked = [...DEFAULT_EXPORT_COLUMNS];
    delimiter = ',';
    submitted = false;
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
    }
  }

  function submit(event) {
    event.preventDefault();
    submitted = true;
    if (columns.length === 0 || !link) return;

    // The hidden iframe is the link target, so a 403 / 5xx never replaces the panel page.
    link.href = exportUrl(base, filters, columns, delimiter, buildQueryParams);
    link.click();

    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    }
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
