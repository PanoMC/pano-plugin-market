<div class="market-comparison-table card">
  <div class="market-comparison-table__header card-header fw-semibold">{comparison.name}</div>
  <div class="table-responsive">
    <table class="market-comparison-table__table table table-hover align-middle mb-0">
      <thead>
        <tr>
          <th scope="col" class="text-nowrap">{$_('theme.store.comparison-feature')}</th>
          {#each columns as col (col.id)}
            <th scope="col" class="text-center text-nowrap">
              {#if col.known}
                <div class="fw-semibold">{col.name}</div>
                <div class="small text-body-secondary fw-normal">
                  {col.currency
                    ? formatMoney(col.price, col.currency, { removeCents: settings.removeCents })
                    : formatPrice(col.price, settings)}
                </div>
              {/if}
            </th>
          {/each}
        </tr>
      </thead>
      <tbody>
        {#each comparison.features || [] as feature (feature.id)}
          <tr>
            <th scope="row" class="fw-normal">{feature.name}</th>
            {#each columns as col (col.id)}
              {@const key = `${feature.id}-${col.id}`}
              <!-- Missing and empty-string cells both mean "yes", matching the panel editor. -->
              {@const val = (comparison.cellValues || {})[key] || 'yes'}
              <td class="text-center">
                {#if val === 'yes'}
                  <i
                    class="fa-solid fa-check text-success"
                    role="img"
                    aria-label={$_('theme.store.comparison-yes')}></i>
                {:else if val === 'no'}
                  <i
                    class="fa-solid fa-xmark text-danger"
                    role="img"
                    aria-label={$_('theme.store.comparison-no')}></i>
                {:else}
                  <span>{val}</span>
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
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const _ = market._;
  const { formatMoney, formatPrice } = market.require('format').actions;

  let { comparison, productMap = {}, settings = {} } = $props();

  // The catalogue is paged: the cards come from `comparisonProducts`. A product missing from the map keeps
  // its column with an empty header (fallback); null slots are dropped.
  let columns = $derived(
    (comparison.productIds || [])
      .filter((id) => id != null)
      .map((id) => {
        const product = productMap[id];

        return product
          ? {
              id,
              known: true,
              name: product.name,
              price: product.price,
              currency: product.currency,
            }
          : { id, known: false };
      }),
  );
</script>
