{#if render}
  <div class="market-stats-widget card">
    <div class="market-stats-widget__header card-header">
      <i class="fa-solid fa-chart-simple me-2" aria-hidden="true"></i>{$_(
        'theme.widgets.stats.title',
      )}
    </div>
    <div class="market-stats-widget__body card-body">
      <div class="row row-cols-2 g-2 text-center">
        {#each rows as row (row.key)}
          <div class="col">
            <div class="fs-5 fw-semibold">{formatNumber(row.value)}</div>
            <div class="small text-body-secondary">
              {#if row.key === 'ordersToday'}{$_('theme.widgets.stats.orders-today')}
              {:else if row.key === 'ordersTotal'}{$_('theme.widgets.stats.orders-total')}
              {:else if row.key === 'customersTotal'}{$_('theme.widgets.stats.customers-total')}
              {:else}{$_('theme.widgets.stats.products-total')}{/if}
            </div>
          </div>
        {/each}
      </div>
    </div>
  </div>
{/if}

<script module>
  import { plugin } from '@panomc/sdk/controllers';

  // sidebar injection (doc 01 section 2): the build registers this view in the home and profile sidebars
  export const view = {
    sidebar: ['home', 'profile'],
    id: 'market-stats',
    priority: 40,
    widget: true,
  };

  /** The one `market/widgets` load of the four widgets (the controller shares the request); the payload is the `data` prop. */
  export const load = (event) =>
    plugin('market')
      .load('widgets', { event })
      .then((data) => ({ data: data ?? {} }));
</script>

<script>
  import { shouldRender, statsRows, unwrapWidgets, withSidebar } from './widgetsModel.js';

  const { _ } = plugin('market');

  /** data: the widgets payload (`stats`), plus `sidebars` / `sidebarId` in a host sidebar. */
  let { data = {}, sidebarId = '' } = $props();

  const payload = $derived(unwrapWidgets(data));
  const render = $derived(shouldRender(withSidebar(payload, sidebarId), 'stats'));
  const rows = $derived(statsRows(payload.stats));

  const formatNumber = (value) => {
    try {
      return new Intl.NumberFormat().format(value);
    } catch (e) {
      return String(value);
    }
  };
</script>
