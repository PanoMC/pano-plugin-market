{#if rows.length}
  <dl class="market-order-totals row mb-0 gy-1" aria-live="polite">
    {#each rows as row (row.id)}
      <dt class={['col-7', 'fw-normal', row.strong && 'fw-bold']}>
        {$_(row.labelKey)}
        {#if row.extra}
          <span class="small text-body-secondary">
            ({formatCredits(row.extra.credits, row.extra.name)})
          </span>
        {/if}
      </dt>
      <dd class={['col-5', 'text-end', 'mb-0', row.strong && 'fw-bold']}>
        {row.negative ? MINUS : ''}{formatMoney(row.amount, order.currency, { removeCents })}
      </dd>
    {/each}
  </dl>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { totalsRows } from '../../lib/orderState.js';

  const market = plugin('market');
  const { _ } = market;
  const { formatCredits, formatMoney } = market.require('format').actions;

  const MINUS = '−';

  /** The totals of an OrderView; every amount is the server's number (nothing is added up here). */
  let { order, pricesIncludeVat = false, removeCents = false } = $props();

  const rows = $derived(totalsRows(order, { pricesIncludeVat }));
</script>
