{#if render}
  <div class="market-recent-buyers-widget card">
    <div class="market-recent-buyers-widget__header card-header">
      <i class="fa-solid fa-bag-shopping me-2" aria-hidden="true"></i>{$_(
        'theme.widgets.recent-buyers.title',
      )}
    </div>
    <ul class="market-recent-buyers-widget__list list-group list-group-flush">
      {#each entries as entry, index (index)}
        <li
          class="market-recent-buyers-widget__item list-group-item d-flex align-items-center gap-2">
          <PlayerHead username={entry.username} width={24} height={24} />
          <div class="flex-grow-1 text-truncate">
            <div class="text-truncate">{entry.username}</div>
            <div class="small text-body-secondary text-truncate">
              {entry.product}
              {#if entry.more > 0}
                <span class="market-recent-buyers-widget__badge badge text-bg-secondary ms-1"
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

<script module>
  import { plugin } from '@panomc/sdk/controllers';

  // sidebar injection (doc 01 section 2): the build registers this view in the home and profile sidebars
  export const view = {
    sidebar: ['home', 'profile'],
    id: 'market-recent-buyers',
    priority: 50,
    widget: true,
  };

  /** The one `market/widgets` load of the four widgets (the controller shares the request); the payload is the `data` prop. */
  export const load = (event) =>
    plugin('market')
      .load('widgets', { event })
      .then((data) => ({ data: data ?? {} }));
</script>

<script>
  import { PlayerHead, Date as PanoDate } from '@panomc/sdk/components/theme';
  import { buyerView, shouldRender, unwrapWidgets, withSidebar } from './widgetsModel.js';

  const market = plugin('market');
  const { _ } = market;
  const { formatMoney } = market.require('format').actions;

  /** data: the widgets payload (`recentBuyers`), plus `sidebars` / `sidebarId` in a host sidebar. */
  let { data = {}, sidebarId = '' } = $props();

  const payload = $derived(unwrapWidgets(data));
  const render = $derived(shouldRender(withSidebar(payload, sidebarId), 'recentBuyers'));
  const entries = $derived((payload.recentBuyers ?? []).map(buyerView));
</script>
