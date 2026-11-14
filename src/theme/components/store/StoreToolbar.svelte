<div class="market-store-toolbar row g-2 align-items-center">
  <div class="market-store-toolbar__credits col d-flex align-items-center">
    {#if creditSummary?.creditsEnabled}
      <a
        class="market-store-toolbar__credit badge rounded-pill text-bg-info text-decoration-none"
        href="{base}/profile/credits"
        aria-label={`${$_('theme.store.credits')}: ${creditBalanceText}`}>
        <i class="fa-solid fa-coins me-1" aria-hidden="true"></i>{$_('theme.store.credits')}:
        {creditBalanceText}
      </a>
    {/if}
  </div>
  <div class="market-store-toolbar__search col-auto">
    <SearchInput
      id="marketSearch"
      initialValue={search}
      placeholder={$_('theme.common.search-placeholder')}
      ariaLabel={$_('theme.store.search-label')}
      searchingLabel={$_('theme.common.loading')}
      maxLength={SEARCH_MAX}
      debounceMs={SEARCH_DEBOUNCE_MS}
      {searching}
      onchange={onsearch} />
  </div>

  <div
    class="market-store-toolbar__controls col d-flex align-items-center justify-content-end gap-2">
    <FilterDropdown
      options={sortOptions}
      current={sort}
      label={$_('theme.store.sort-label')}
      onSelect={(option) => onsort?.(option.key)} />
    {#if showCurrency}
      <CurrencySelect currencies={settings.currencies} selected={currency} onchange={oncurrency} />
    {/if}
  </div>
</div>

<script module>
  export const view = { contract: 2 };
</script>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { base } from '@panomc/sdk/svelte';
  import { SEARCH_DEBOUNCE_MS, SEARCH_MAX, SORTS } from '../../lib/storeFilter.js';
  import CurrencySelect from './CurrencySelect.svelte';
  import FilterDropdown from './FilterDropdown.svelte';
  import SearchInput from './SearchInput.svelte';

  const market = plugin('market');
  const _ = market._;
  const currencies = market.require('currency');
  const { formatCredits } = market.require('format').actions;

  /** search / sort: current values; currency: code in use; onsearch(text), onsort(value), oncurrency(code). */
  let {
    settings = {},
    search = '',
    sort = 'priority',
    currency = '',
    creditSummary = null,
    searching = false,
    onsearch,
    onsort,
    oncurrency,
  } = $props();

  const showCurrency = $derived(currencies.actions.isMulti(settings));
  const creditBalanceText = $derived(
    creditSummary?.creditsEnabled ? formatCredits(creditSummary.creditBalance) : '',
  );
  const sortOptions = $derived(
    SORTS.map((value) => ({ key: value, label: $_(`theme.store.sort.${value}`) })),
  );
</script>
