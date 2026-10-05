<div class="card text-bg-{card.variant} overflow-hidden h-100" bind:this={cardElement}>
  <div class="card-body p-0 d-flex flex-column">
    <div class="px-3 pt-3 pb-2">
      <p class="text-truncate m-0">{title}</p>
      <div class="d-flex align-items-baseline gap-2">
        <span class="fs-2 lh-1 text-truncate">{value}</span>
        {#if secondary}
          <span class="text-truncate small">{secondary}</span>
        {/if}
      </div>
    </div>
    <div class="mt-auto" style="height: 64px;">
      {#if card.spark.length > 1}
        <canvas bind:this={canvas} aria-hidden="true"></canvas>
      {/if}
    </div>
  </div>
</div>

<script>
  import { Chart, withAlpha } from './chart-setup.js';

  // card: one item of summary.js summaryCards(); title / value / secondary are display strings.
  let { card, title, value, secondary = '' } = $props();

  let cardElement = $state(null);
  let canvas = $state(null);

  // Minimal sparkline (design/cards-colored.md): no axes, legend or grid, tooltip on hover only;
  // the line takes the card's own text colour, the fill is the same colour at alpha 0.25.
  $effect(() => {
    if (!canvas || !cardElement) return;
    const color = getComputedStyle(cardElement).color;
    const spark = card.spark.slice();
    const chart = new Chart(canvas, {
      type: 'line',
      data: {
        labels: spark.map((_, index) => index + 1),
        datasets: [
          {
            data: spark,
            borderColor: color,
            borderWidth: 2,
            backgroundColor: withAlpha(color, 0.25),
            fill: true,
            pointRadius: 0,
            pointHoverRadius: 3,
            tension: 0.4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        layout: { padding: 0 },
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: { enabled: true, displayColors: false, callbacks: { title: () => '' } },
        },
        scales: { x: { display: false }, y: { display: false } },
      },
    });
    return () => chart.destroy();
  });
</script>
