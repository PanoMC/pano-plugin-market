<button
  type="button"
  class={[
    'list-group-item',
    'list-group-item-action',
    'd-flex',
    'align-items-center',
    'gap-2',
    indent,
    { active },
  ]}
  aria-current={active ? 'true' : undefined}
  onclick={() => onselect?.(row.id)}>
  <i
    class={['fa-solid', row.icon || 'fa-folder', 'flex-shrink-0']}
    style={color ? `color: ${color};` : undefined}
    aria-hidden="true"></i>
  <span class="flex-grow-1 text-truncate text-start">{row.name}</span>
  <span class="badge text-bg-secondary rounded-pill">{row.productsCount}</span>
</button>

<script>
  import { indentClass } from '../../lib/storeFilter.js';

  /** row: { id (null = all products), name, icon, color, productsCount, depth } */
  let { row, active = false, onselect } = $props();

  const indent = $derived(indentClass(row.depth));
  // the admin-chosen category colour is the only inline style; hex values only
  const color = $derived(/^#[0-9a-fA-F]{3,8}$/.test(row.color || '') ? row.color : null);
</script>
