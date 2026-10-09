<div>
  <input
    type="text"
    class="form-control form-control-sm mb-2"
    placeholder={$_('modals.discount-form.category-search')}
    aria-label={$_('modals.discount-form.category-search')}
    bind:value={search} />
  <div
    class="list-group border rounded overflow-y-auto"
    class:border-danger={error}
    style="max-height: 150px;">
    {#each filtered as category (category.id)}
      <label class="list-group-item d-flex align-items-center gap-3 py-2 list-group-item-action">
        <input
          class="form-check-input flex-shrink-0 mt-0"
          type="checkbox"
          value={category.id}
          checked={selected.includes(category.id)}
          onchange={() => toggle(category.id)} />
        <span>{category.name}</span>
      </label>
    {:else}
      <div class="text-center text-body-secondary py-3">
        {$_('modals.discount-form.no-results')}
      </div>
    {/each}
  </div>
  <ErrorText code={error} />
</div>

<script>
  import { onMount } from 'svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { _ } from '../../../i18n';
  import { call } from '../../utils/api.js';
  import { pageOf } from '../../utils/page.js';
  import ErrorText from './ErrorText.svelte';

  let { selected = $bindable([]), error = null } = $props();

  let categories = $state([]);
  let search = $state('');
  const filtered = $derived(
    categories.filter((c) => c.name.toLowerCase().includes(search.toLowerCase())),
  );

  function flatten(nodes, acc = []) {
    for (const node of nodes) {
      acc.push({ id: node.id, name: node.name });
      if (node.children?.length) flatten(node.children, acc);
    }
    return acc;
  }

  function toggle(id) {
    selected = selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id];
  }

  onMount(async () => {
    const result = await call(api.panel.get({ path: '/categories' }));
    categories = result.ok ? flatten(pageOf(result.body).items) : [];
  });
</script>
