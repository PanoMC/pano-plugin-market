<CardFilters>
  {#each RANGES as item (item)}
    <CardFiltersItem button active={range === item} onclick={() => onSelect(item)}>
      {$_(`pages.overview.range.${item}`)}
    </CardFiltersItem>
  {/each}
  <CardFiltersItem button active={range === 'custom'} onclick={onCustom}>
    {$_('pages.overview.range.custom')}
  </CardFiltersItem>
</CardFilters>

<script>
  import { CardFilters, CardFiltersItem } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import { RANGES } from './range.js';

  // range: '7d' | '30d' | '90d' | 'month' | 'custom'
  let { range, onSelect, onCustom } = $props();
</script>
