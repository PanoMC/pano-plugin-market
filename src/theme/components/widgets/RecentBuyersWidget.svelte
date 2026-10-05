{#if render}
  <div class="card">
    <div class="card-header">
      <i class="fa-solid fa-bag-shopping me-2" aria-hidden="true"></i>{$_(
        'theme.widgets.recent-buyers.title',
      )}
    </div>
    <ul class="list-group list-group-flush">
      {#each entries as entry, index (index)}
        <li class="list-group-item d-flex align-items-center gap-2">
          <PlayerHead username={entry.username} width={24} height={24} />
          <div class="flex-grow-1 text-truncate">
            <div class="text-truncate">{entry.username}</div>
            <div class="small text-body-secondary text-truncate">
              {entry.product}
              {#if entry.more > 0}
                <span class="badge text-bg-secondary ms-1"
                  >{$_('theme.widgets.recent-buyers.more', {
                    values: { count: entry.more },
                  })}</span>
              {/if}
            </div>
          </div>
          <div class="text-end small text-nowrap">
            {#if entry.amount !== null}
              <div>{formatMoney(entry.amount, entry.currency)}</div>
            {/if}
            {#if entry.createdAt > 0}
              <div class="text-body-secondary">
                <PanoDate time={entry.createdAt} relativeFormat={true} />
              </div>
            {/if}
          </div>
        </li>
      {/each}
    </ul>
  </div>
{/if}

<script>
  import { PlayerHead, Date as PanoDate } from '@panomc/sdk/components/theme';
  import { _ } from '../../../i18n.js';
  import { formatMoney } from '../../utils/format.js';
  import { buyerView, shouldRender } from './widgetsModel.js';

  /** data: the widgets payload (`recentBuyers`), plus `sidebars` / `sidebarId` in a host sidebar. */
  let { data = {} } = $props();

  const render = $derived(shouldRender(data, 'recentBuyers'));
  const entries = $derived((data?.recentBuyers ?? []).map(buyerView));
</script>
