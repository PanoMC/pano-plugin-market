{#if totalPage > 1}
  <nav aria-label={$_('theme.store.pager-label')}>
    <ul class="pagination justify-content-center flex-wrap mb-0">
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
              aria-label={$_('theme.store.page-n', { page: item.page })}
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
  import { _ } from '../../../i18n.js';
  import { pagerItems } from '../../lib/storeFilter.js';

  /** page: current page (1-based); totalPage; onpage(n). */
  let { page = 1, totalPage = 1, onpage } = $props();

  const items = $derived(pagerItems(page, totalPage));
</script>
