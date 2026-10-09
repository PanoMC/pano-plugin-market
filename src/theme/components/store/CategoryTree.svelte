<button
  type="button"
  class="market-category-tree market-category-tree__action btn btn-outline-secondary w-100 d-lg-none mb-2"
  data-bs-toggle="collapse"
  data-bs-target="#marketCategories"
  aria-controls="marketCategories"
  aria-expanded="false"
  aria-label={$_('theme.store.toggle-categories')}>
  <i class="fa-solid fa-layer-group me-2" aria-hidden="true"></i>{$_('theme.store.categories')}
</button>

<div class="market-category-tree collapse d-lg-block" id="marketCategories">
  <div class="market-category-tree__list list-group">
    <CategoryNode row={allRow} active={selected == null} {onselect} />
    {#each rows as row (row.id)}
      <CategoryNode {row} active={selected === row.id} {onselect} />
    {/each}
  </div>
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { flattenCategories } from '../../lib/storeFilter.js';
  import CategoryNode from './CategoryNode.svelte';

  const market = plugin('market');
  const _ = market._;

  /** categories: the tree of the store response; selected: category id or null; onselect(id | null). */
  let { categories = [], selected = null, totalCount = 0, onselect } = $props();

  const rows = $derived(flattenCategories(categories));
  const allRow = $derived({
    id: null,
    name: $_('theme.store.all-products'),
    icon: 'fa-store',
    color: null,
    productsCount: totalCount,
    depth: 0,
  });
</script>
