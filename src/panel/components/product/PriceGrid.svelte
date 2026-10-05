<div class="table-responsive">
  <table class="table table-hover align-middle mb-0">
    <thead>
      <tr>
        <th class="align-middle text-nowrap" scope="col"
          >{$_('pages.create-product.grid.currency')}</th>
        <th class="align-middle text-nowrap" scope="col"
          >{$_('pages.create-product.grid.price')}</th>
        <th class="align-middle text-nowrap" scope="col"
          >{$_('pages.create-product.grid.compare-at')}</th>
      </tr>
    </thead>
    <tbody>
      {#each rows as row (row.currency)}
        {@const exponent = currencyExponent(ctx, row.currency)}
        {@const priceError = errors[`${path}.${row.currency}.price`]}
        {@const compareError = errors[`${path}.${row.currency}.compareAtPrice`]}
        <tr>
          <th class="align-middle text-nowrap" scope="row">{row.currency}</th>
          <td>
            <div data-field="{path}.{row.currency}.price">
              <MoneyInput
                bind:value={row.price}
                {exponent}
                currency={symbolOf(row.currency)}
                invalid={!!priceError}
                placeholder={$_('pages.create-product.grid.price')} />
              {#if priceError}
                <div class="invalid-feedback d-block">{$_(fieldErrorKey(priceError))}</div>
              {/if}
            </div>
          </td>
          <td>
            <div data-field="{path}.{row.currency}.compareAtPrice">
              <MoneyInput
                bind:value={row.compareAtPrice}
                {exponent}
                currency={symbolOf(row.currency)}
                invalid={!!compareError}
                placeholder={$_('pages.create-product.grid.compare-at')} />
              {#if compareError}
                <div class="invalid-feedback d-block">{$_(fieldErrorKey(compareError))}</div>
              {/if}
            </div>
          </td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>

<script>
  import { _ } from '../../../i18n';
  import MoneyInput from '../MoneyInput.svelte';
  import { currencyExponent, fieldErrorKey } from './model.js';

  // rows: one `{currency, price, compareAtPrice}` per additional currency (model.ensurePriceRows);
  // path: error path prefix, `prices` or `variants.<i>.prices`, followed by `.<CUR>.<field>`.
  let { rows = $bindable([]), ctx, errors = {}, path = 'prices' } = $props();

  function symbolOf(code) {
    return (ctx?.currencies ?? []).find((c) => c.code === code)?.symbol || code;
  }
</script>
