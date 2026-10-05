<div class="card-body">
  <div class="row g-3">
    {#each charts as chart (chart.key)}
      <div class="col-md-6">
        <p class="mb-2">{$_(`pages.overview.chart.${chart.key}`)}</p>
        <div class="p-3 border rounded-3">
          <ChartCanvas
            type={chart.type}
            data={chart.data}
            options={chart.options}
            label={$_(`pages.overview.chart.${chart.key}`)} />
        </div>
      </div>
    {/each}
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import ChartCanvas from './ChartCanvas.svelte';
  import { themeColor, withAlpha } from './chart-setup.js';
  import { currencySeries } from './summary.js';

  // stats: GET /stats body; currency: the stats currency code (revenue values are already
  // converted into it by the server and are not converted again).
  let { stats, currency = null } = $props();

  const series = (value) => ({
    labels: (value?.labels ?? []).map(String),
    values: (value?.values ?? []).map((v) => Number(v) || 0),
  });

  // `YYYYWW` -> "Week WW"
  const weekLabel = (key) =>
    $_('pages.overview.chart.week-label', {
      values: { week: parseInt(String(key).slice(-2), 10) },
    });

  const money = (context) => fmt.money(context.parsed.y ?? context.parsed, currency);
  const PALETTE = ['primary', 'info', 'warning', 'secondary', 'success', 'danger', 'indigo'];
  const palette = (count) =>
    Array.from({ length: count }, (unused, i) => themeColor(PALETTE[i % PALETTE.length]));

  const line = (value, name, labelOf = (l) => l) => {
    const color = themeColor(name);
    const { labels, values } = series(value);
    return {
      type: 'line',
      data: {
        labels: labels.map(labelOf),
        datasets: [
          {
            data: values,
            borderColor: color,
            backgroundColor: withAlpha(color, 0.15),
            fill: true,
            tension: 0.4,
          },
        ],
      },
      options: {
        plugins: { legend: { display: false }, tooltip: { callbacks: { label: money } } },
      },
    };
  };

  const doughnut = (labels, values, label) => ({
    type: 'doughnut',
    data: {
      labels,
      datasets: [{ data: values, backgroundColor: palette(values.length), borderWidth: 0 }],
    },
    options: {
      cutout: '65%',
      plugins: {
        legend: { position: 'bottom' },
        tooltip: { callbacks: { label } },
      },
    },
  });

  const charts = $derived.by(() => {
    const c = stats?.charts ?? {};
    const top = series(c.topProducts);
    const methods = series(c.paymentMethods);
    const currencies = currencySeries(c);
    return [
      { key: 'weekly', ...line(c.weeklyRevenue, 'primary', weekLabel) },
      { key: 'monthly', ...line(c.monthlyRevenue, 'info') },
      {
        key: 'top-products',
        type: 'bar',
        data: {
          labels: top.labels,
          datasets: [{ data: top.values, backgroundColor: themeColor('primary'), borderRadius: 6 }],
        },
        options: {
          plugins: { legend: { display: false }, tooltip: { callbacks: { label: money } } },
          scales: { y: { beginAtZero: true } },
        },
      },
      {
        key: 'payment-methods',
        ...doughnut(methods.labels, methods.values, (ctx) => `${ctx.label}: ${ctx.parsed}`),
      },
      {
        key: 'currencies',
        ...doughnut(
          currencies.labels,
          currencies.values,
          (ctx) => `${ctx.label}: ${fmt.money(ctx.parsed, currency)}`,
        ),
      },
    ];
  });
</script>
