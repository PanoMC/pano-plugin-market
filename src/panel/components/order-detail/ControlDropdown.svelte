{#if items.length > 0}
  <div class="dropdown position-static">
    <button
      type="button"
      class="btn btn-link"
      data-bs-toggle="dropdown"
      aria-expanded="false"
      title={$_('common.actions')}
      aria-label={$_('common.actions')}>
      <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
    </button>
    <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
      {#each items as item (item.key)}
        {#if item.href}
          <a
            class="dropdown-item"
            href={item.href}
            target={item.blank ? '_blank' : undefined}
            rel={item.blank ? 'noopener' : undefined}
            download={item.download ? '' : undefined}>
            <i class="fa-solid {item.icon} me-2" aria-hidden="true"></i>
            {item.label}
          </a>
        {:else}
          <button
            type="button"
            class="dropdown-item"
            class:link-danger={item.danger}
            onclick={item.onclick}>
            <i class="fa-solid {item.icon} me-2" aria-hidden="true"></i>
            {item.label}
          </button>
        {/if}
      {/each}
    </div>
  </div>
{/if}

<script>
  import { _ } from '../../../i18n';

  // items: [{ key, label, icon, danger, onclick | href }], labels already translated.
  let { items = [] } = $props();
</script>
