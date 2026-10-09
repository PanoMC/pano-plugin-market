{#if render}
  <div class="market-top-supporters-widget card">
    <div class="market-top-supporters-widget__header card-header">
      <i class="fa-solid fa-ranking-star me-2" aria-hidden="true"></i>{$_(
        'theme.widgets.top-supporters.title',
      )}
    </div>
    <ul class="market-top-supporters-widget__list list-group list-group-flush">
      {#each entries as entry (entry.rank + ':' + entry.username)}
        <li
          class="market-top-supporters-widget__item list-group-item d-flex align-items-center gap-2">
          <span class="text-center flex-shrink-0 w-25 small">
            {#if entry.trophy}
              <i
                class={[iconClass(entry.trophy.icon), toneClass(entry.trophy.tone)]}
                aria-hidden="true"></i>
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

<script module>
  import { plugin } from '@panomc/sdk/controllers';

  // sidebar injection (doc 01 section 2): the build registers this view in the home and profile sidebars
  export const view = {
    sidebar: ['home', 'profile'],
    id: 'market-top-supporters',
    priority: 60,
    widget: true,
  };

  /** The one `market/widgets` load of the four widgets (the controller shares the request); the payload is the `data` prop. */
  export const load = (event) =>
    plugin('market')
      .load('widgets', { event })
      .then((data) => ({ data: data ?? {} }));
</script>

<script>
  import { PlayerHead } from '@panomc/sdk/components/theme';
  import { shouldRender, supporterView, unwrapWidgets, withSidebar } from './widgetsModel.js';
  import { iconClass, toneClass } from '../../lib/classes.js';

  const market = plugin('market');
  const { _ } = market;
  const { formatMoney } = market.require('format').actions;

  /** data: the widgets payload (`topSupporters`, `currency`?), plus `sidebars` / `sidebarId` in a host sidebar. */
  let { data = {}, currency = '', sidebarId = '' } = $props();

  const payload = $derived(unwrapWidgets(data));
  const render = $derived(shouldRender(withSidebar(payload, sidebarId), 'topSupporters'));
  const entries = $derived((payload.topSupporters ?? []).map(supporterView));
</script>
