{#if render}
  <div class="card">
    <div class="card-header">
      <i class="fa-solid fa-chart-simple me-2" aria-hidden="true"></i>{$_(
        'theme.widgets.stats.title',
      )}
    </div>
    <div class="card-body">
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

<script>
  import { _ } from '../../../i18n.js';
  import { shouldRender, statsRows } from './widgetsModel.js';

  /** data: the widgets payload (`stats`), plus `sidebars` / `sidebarId` in a host sidebar. */
  let { data = {} } = $props();

  const render = $derived(shouldRender(data, 'stats'));
  const rows = $derived(statsRows(data?.stats));

  const formatNumber = (value) => {
    try {
      return new Intl.NumberFormat().format(value);
    } catch (e) {
      return String(value);
    }
  };
</script>
