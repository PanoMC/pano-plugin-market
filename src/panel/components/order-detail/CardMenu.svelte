{#if items.length > 0}
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
      {#each items as item (item.key)}
        <button
          type="button"
          class="dropdown-item"
          class:link-danger={item.danger}
          onclick={item.onclick}>
          <i class="fa-solid {item.icon} me-2" aria-hidden="true"></i>
          {item.label}
        </button>
      {/each}
    </div>
  </div>
{/if}

<script>
  import { _ } from '../../../i18n';

  // The header action menu of a table card (design/cards-table.md): items = [{ key, label, icon, danger, onclick }].
  let { items = [] } = $props();
</script>
