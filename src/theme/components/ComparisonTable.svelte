<div class="card shadow-sm border-0">
  <div class="card-header bg-transparent fw-semibold">{comparison.name}</div>
  <div class="table-responsive">
    <table class="table table-hover align-middle mb-0">
      <thead>
        <tr>
          <th scope="col" style="min-width: 200px;">{$_('theme.store.comparison-feature')}</th>
          {#each columns as col (col.id)}
            <th scope="col" class="text-center" style="min-width: 140px;">
              <div class="fw-semibold text-truncate">{col.name}</div>
              <div class="small text-body-secondary">{formatPrice(col.price, settings.currencySymbol)}</div>
            </th>
          {/each}
        </tr>
      </thead>
      <tbody>
        {#each comparison.features || [] as feature (feature.id)}
          <tr>
            <th scope="row" class="fw-normal">{feature.name}</th>
            {#each columns as col (col.id)}
              <td class="text-center">
                {@const key = `${feature.id}-${col.id}`}
                {@const val = key in (comparison.cellValues || {}) ? comparison.cellValues[key] : 'yes'}
                {#if val === 'yes'}
                  <i class="fa-solid fa-check text-success" aria-label={$_('theme.store.comparison-yes')}></i>
                {:else if val === 'no'}
                  <i class="fa-solid fa-xmark text-danger" aria-label={$_('theme.store.comparison-no')}></i>
                {:else}
                  <span>{val || '—'}</span>
                {/if}
              </td>
            {/each}
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
</div>

<script>
  import { _ } from '../../i18n';
  import { formatPrice } from '../utils/format';

  export let comparison;
  export let productMap = {};
  export let settings = {};

  // Resolve the comparison's product id slots against the visible product list,
  // dropping null slots and ids that are hidden/removed (not present in the map).
  $: columns = (comparison.productIds || [])
    .filter((id) => id != null && productMap[id])
    .map((id) => productMap[id]);
</script>
