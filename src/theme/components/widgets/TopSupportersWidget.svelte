{#if render}
  <div class="card">
    <div class="card-header">
      <i class="fa-solid fa-ranking-star me-2" aria-hidden="true"></i>{$_(
        'theme.widgets.top-supporters.title',
      )}
    </div>
    <ul class="list-group list-group-flush">
      {#each entries as entry (entry.rank + ':' + entry.username)}
        <li class="list-group-item d-flex align-items-center gap-2">
          <span class="text-center flex-shrink-0 w-25 small">
            {#if entry.trophy}
              <i class={[entry.trophy.icon, entry.trophy.tone]} aria-hidden="true"></i>
              <span class="visually-hidden"
                >{$_('theme.widgets.rank', { values: { rank: entry.rank } })}</span>
            {:else}
              <span class="text-body-secondary">{entry.rank}</span>
            {/if}
          </span>
          <PlayerHead username={entry.username} width={24} height={24} />
          <span class="flex-grow-1 text-truncate">{entry.username}</span>
          {#if entry.total !== null}
            <span class="small text-body-secondary text-nowrap"
              >{formatMoney(entry.total, data?.currency || currency)}</span>
          {/if}
        </li>
      {/each}
    </ul>
  </div>
{/if}

<script>
  import { PlayerHead } from '@panomc/sdk/components/theme';
  import { _ } from '../../../i18n.js';
  import { formatMoney } from '../../utils/format.js';
  import { shouldRender, supporterView } from './widgetsModel.js';

  /** data: the widgets payload (`topSupporters`, `currency`?), plus `sidebars` / `sidebarId` in a host sidebar. */
  let { data = {}, currency = '' } = $props();

  const render = $derived(shouldRender(data, 'topSupporters'));
  const entries = $derived((data?.topSupporters ?? []).map(supporterView));
</script>
