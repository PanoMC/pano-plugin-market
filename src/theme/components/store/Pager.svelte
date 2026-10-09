{#if totalPages > 1}
  <nav class="market-pager" aria-label={$_('theme.store.pager-label')}>
    <ul class="market-pager__pager pagination justify-content-center flex-wrap mb-0">
      {#each items as item, index (index)}
        {#if item.type === 'gap'}
          <li class="page-item disabled">
            <span class="page-link" aria-hidden="true">&hellip;</span>
          </li>
        {:else if item.type === 'page'}
          <li class={['page-item', { active: item.current }]}>
            <button
              type="button"
              class="page-link"
              aria-current={item.current ? 'page' : undefined}
              aria-label={$_('theme.store.page-n', { values: { page: item.page } })}
              onclick={() => !item.current && onpage?.(item.page)}>
              {item.page}
            </button>
          </li>
        {:else}
          {@const previous = item.type === 'prev'}
          <li class={['page-item', { disabled: item.disabled }]}>
            <button
              type="button"
              class="page-link"
              disabled={item.disabled}
              aria-label={$_(previous ? 'theme.store.page-prev' : 'theme.store.page-next')}
              onclick={() => onpage?.(item.page)}>
              <i
                class={['fa-solid', previous ? 'fa-chevron-left' : 'fa-chevron-right']}
                aria-hidden="true"></i>
            </button>
          </li>
        {/if}
      {/each}
    </ul>
  </nav>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { pagerItems } from '../../lib/storeFilter.js';

  const market = plugin('market');
  const _ = market._;

  /** page: current page (1-based); totalPages; onpage(n). */
  let { page = 1, totalPages = 1, onpage } = $props();

  const items = $derived(pagerItems(page, totalPages));
</script>
