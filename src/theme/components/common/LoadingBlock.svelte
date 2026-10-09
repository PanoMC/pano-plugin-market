<div class="market-loading-block placeholder-glow" aria-busy="true">
  <span class="visually-hidden">{$_('theme.common.loading')}</span>
  <div aria-hidden="true">
    {#each Array.from({ length: rows }, (_row, index) => index) as index (index)}
      <p class="mb-2">
        <span class={['placeholder', widths[index % widths.length]]}></span>
      </p>
    {/each}
  </div>
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const { _ } = plugin('market');

  let { rows = 3 } = $props();

  const widths = ['col-12', 'col-9', 'col-6'];
</script>
