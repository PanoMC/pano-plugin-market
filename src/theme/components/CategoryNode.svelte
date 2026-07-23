<li>
  <button
    type="button"
    class="list-group-item list-group-item-action d-flex align-items-center gap-2"
    class:active={selected === node.id}
    style="padding-left: {0.75 + depth * 1.25}rem;"
    on:click={() => dispatch('select', { id: node.id })}>
    <i
      class="fa-solid {node.icon || 'fa-folder'} flex-shrink-0"
      style={node.color ? `color: ${node.color};` : ''}></i>
    <span class="flex-grow-1 text-truncate text-start">{node.name}</span>
    <span class="badge rounded-pill text-bg-secondary">{node.productsCount}</span>
  </button>

  {#if node.children && node.children.length}
    <ul class="list-group list-group-flush">
      {#each node.children as child (child.id)}
        <svelte:self node={child} {selected} depth={depth + 1} on:select />
      {/each}
    </ul>
  {/if}
</li>

<script>
  import { createEventDispatcher } from 'svelte';

  export let node;
  export let selected = null;
  export let depth = 0;

  const dispatch = createEventDispatcher();
</script>
