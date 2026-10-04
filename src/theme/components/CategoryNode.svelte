<li>
  <button
    type="button"
    class="list-group-item list-group-item-action d-flex align-items-center gap-2"
    class:active={selected === node.id}
    style="padding-left: {0.75 + depth * 1.25}rem;"
    onclick={() => onselect?.({ id: node.id })}>
    <i
      class="fa-solid {node.icon || 'fa-folder'} flex-shrink-0"
      style={node.color ? `color: ${node.color};` : ''}></i>
    <span class="flex-grow-1 text-truncate text-start">{node.name}</span>
    <span class="badge rounded-pill text-bg-secondary">{node.productsCount}</span>
  </button>

  {#if node.children && node.children.length}
    <ul class="list-group list-group-flush">
      {#each node.children as child (child.id)}
        <CategoryNode node={child} {selected} depth={depth + 1} {onselect} />
      {/each}
    </ul>
  {/if}
</li>

<script>
  // Self-import: the recursive tree renders the component through its own name.
  import CategoryNode from './CategoryNode.svelte';

  let { node, selected = null, depth = 0, onselect } = $props();
</script>
