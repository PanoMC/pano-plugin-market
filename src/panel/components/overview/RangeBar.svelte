<CardFilters>
  <FilterSelect
    label={$_('pages.overview.range.custom')}
    current={range}
    options={[...RANGES, 'custom'].map((item) => ({ key: item, label: $_(`pages.overview.range.${item}`) }))}
    onSelect={(item) => (item.key === 'custom' ? onCustom() : onSelect(item.key))} />
</CardFilters>

<script>
  import { CardFilters } from '@panomc/sdk/components/panel';
  import FilterSelect from '../FilterSelect.svelte';
  import { _ } from '../../../i18n';
  import { RANGES } from './range.js';

  // range: '7d' | '30d' | '90d' | 'month' | 'custom'
  let { range, onSelect, onCustom } = $props();
</script>
