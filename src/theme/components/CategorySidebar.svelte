<div class="card shadow-sm border-0">
  <div class="card-header bg-transparent fw-semibold">
    <i class="fa-solid fa-layer-group me-2"></i>{$_('theme.store.categories')}
  </div>
  <ul class="list-group list-group-flush">
    <li>
      <button
        type="button"
        class="list-group-item list-group-item-action d-flex align-items-center gap-2"
        class:active={selected == null}
        onclick={() => onselect?.({ id: null })}>
        <i class="fa-solid fa-store flex-shrink-0"></i>
        <span class="flex-grow-1 text-truncate text-start">{$_('theme.store.all-products')}</span>
        <span class="badge rounded-pill text-bg-secondary">{totalCount}</span>
      </button>
    </li>
    {#each categories as node (node.id)}
      <CategoryNode {node} {selected} {onselect} />
    {/each}
  </ul>
</div>

<script>
  import { _ } from '../../i18n';
  import CategoryNode from './CategoryNode.svelte';

  let { categories = [], selected = null, totalCount = 0, onselect } = $props();
</script>
