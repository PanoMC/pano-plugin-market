<div class="position-relative" style="height: {height}px;" role="img" aria-label={label}>
  <canvas bind:this={canvas}></canvas>
</div>

<script>
  import { Chart } from './chart-setup.js';

  // `data` / `options` are plain Chart.js objects; the chart is rebuilt when they change.
  let { type, data, options = {}, label = '', height = 260 } = $props();

  let canvas = $state(null);

  $effect(() => {
    if (!canvas) return;
    const chart = new Chart(canvas, {
      type,
      data,
      options: { responsive: true, maintainAspectRatio: false, ...options },
    });
    return () => chart.destroy();
  });
</script>
