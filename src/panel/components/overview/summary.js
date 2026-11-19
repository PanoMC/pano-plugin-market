// Stat cards of the overview page (13 §4.1 item 3), built from GET /stats `summary`. Pure.
const num = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);

const sparkOf = (block) => (Array.isArray(block?.spark) ? block.spark.map(num) : []);

/** A summary block is `{count, revenue, trend, spark}`, or a bare amount. */
function money(block) {
  if (block !== null && typeof block === 'object') {
    return {
      count: num(block.count),
      amount: num(block.revenue ?? block.amount),
      trend: num(block.trend),
      spark: sparkOf(block),
    };
  }
  return { count: 0, amount: num(block), trend: 0, spark: [] };
}

/**
 * Four cards in display order. `kind: 'money'` cards show `amount` formatted in the stats currency,
 * `kind: 'count'` shows `count`. `variant` is the Bootstrap `text-bg-*` colour of the card.
 */
export function summaryCards(summary) {
  const subscriptions = summary?.activeSubscriptions;
  const refundsAmount = money(summary?.refunds).amount;
  return [
    { key: 'weekly', variant: 'secondary', kind: 'money', ...money(summary?.weekly) },
    { key: 'monthly', variant: 'info', kind: 'money', ...money(summary?.monthly) },
    { key: 'total', variant: 'primary', kind: 'money', ...money(summary?.total) },
    {
      key: 'subscriptions',
      variant: 'success',
      kind: 'count',
      count:
        subscriptions !== null && typeof subscriptions === 'object'
          ? num(subscriptions.count)
          : num(subscriptions),
      amount: 0,
      refundsAmount,
      trend: 0,
      spark:
        subscriptions !== null && typeof subscriptions === 'object' ? sparkOf(subscriptions) : [],
    },
  ];
}

/**
 * `charts.currencies` -> `{ labels, values }` for the doughnut. The server sends it like every other chart
 * (`StatsService`: `{ labels: [currency codes], values: [revenue in the stats currency] }`).
 */
export function currencySeries(charts) {
  const chart = charts?.currencies;
  const labels = Array.isArray(chart?.labels) ? chart.labels : [];
  const values = Array.isArray(chart?.values) ? chart.values : [];
  return { labels: labels.map(String), values: labels.map((_, i) => num(values[i])) };
}
