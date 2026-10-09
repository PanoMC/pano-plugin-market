<button
  type="button"
  class={[
    'market-category-node',
    'market-category-node__item',
    'list-group-item',
    'list-group-item-action',
    'd-flex',
    'align-items-center',
    'gap-2',
    indentClass(row.depth),
    { active },
  ]}
  aria-current={active ? 'true' : undefined}
  onclick={() => onselect?.(row.id)}>
  <i
    class={['fa-solid', iconClass(row.icon, 'fa-folder'), 'flex-shrink-0']}
    style={color ? `color: ${color};` : undefined}
    aria-hidden="true"></i>
  <span class="flex-grow-1 text-truncate text-start">{row.name}</span>
  <span class="market-category-node__badge badge text-bg-secondary rounded-pill"
    >{row.productsCount}</span>
</button>

<script>
  import { iconClass } from '../../lib/classes.js';
  import { indentClass } from '../../lib/storeFilter.js';

  /** row: { id (null = all products), name, icon, color, productsCount, depth } */
  let { row, active = false, onselect } = $props();

  // the admin-chosen category colour is the only inline style; hex values only
  const color = $derived(/^#[0-9a-fA-F]{3,8}$/.test(row.color || '') ? row.color : null);
</script>
