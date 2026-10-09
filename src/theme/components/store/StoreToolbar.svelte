<div class="market-store-toolbar row g-2">
  <div class="col-12 col-md-6">
    <label class="visually-hidden" for="marketSearch">{$_('theme.store.search-label')}</label>
    <div class="input-group">
      <span class="input-group-text" aria-hidden="true">
        <i class="fa-solid fa-magnifying-glass"></i>
      </span>
      <input
        id="marketSearch"
        type="search"
        class="market-store-toolbar__input form-control"
        maxlength={SEARCH_MAX}
        placeholder={$_('theme.store.search-placeholder')}
        value={search}
        oninput={(event) => onsearch?.(event.currentTarget.value)} />
    </div>
  </div>

  <div class={showCurrency ? 'col-6 col-md-3' : 'col-12 col-md-6'}>
    <label class="visually-hidden" for="marketSort">{$_('theme.store.sort-label')}</label>
    <select
      id="marketSort"
      class="market-store-toolbar__select form-select"
      value={sort}
      onchange={(event) => onsort?.(event.currentTarget.value)}>
      {#each SORTS as value (value)}
        <option {value}>{$_(`theme.store.sort.${value}`)}</option>
      {/each}
    </select>
  </div>

  {#if showCurrency}
    <div class="col-6 col-md-3">
      <CurrencySelect currencies={settings.currencies} selected={currency} onchange={oncurrency} />
    </div>
  {/if}
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { SEARCH_MAX, SORTS } from '../../lib/storeFilter.js';
  import CurrencySelect from './CurrencySelect.svelte';

  const market = plugin('market');
  const _ = market._;
  const currencies = market.require('currency');

  /** search / sort: current values; currency: code in use; onsearch(text), onsort(value), oncurrency(code). */
  let {
    settings = {},
    search = '',
    sort = 'priority',
    currency = '',
    onsearch,
    onsort,
    oncurrency,
  } = $props();

  const showCurrency = $derived(currencies.actions.isMulti(settings));
</script>
