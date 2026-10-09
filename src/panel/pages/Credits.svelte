{#snippet statCard(variant, title, value)}
  <div class="col-6 col-xl">
    <div class="card text-bg-{variant} h-100">
      <div class="card-body">
        <p class="text-truncate m-0">{title}</p>
        <span class="fs-2 lh-1 text-truncate d-block">{value}</span>
      </div>
    </div>
  </div>
{/snippet}

<MarketLayout area="customers" sections={sectionsFor('customers', user)} active="credits">
  {#snippet right()}
    <button type="button" class="btn btn-secondary" onclick={() => openAdjust('grant', null)}>
      <i class="fa-solid fa-plus" aria-hidden="true"></i>
      <span class="d-lg-inline d-none ms-2">{$_('pages.credits.grant-credits')}</span>
    </button>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    {#if ctx && !ctx.creditsEnabled}
      <div class="alert alert-info d-flex align-items-start mb-0" role="alert">
        <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
        <div>
          <b>{$_('pages.credits.disabled-title')}</b>
          <div>{$_('pages.credits.disabled-body')}</div>
          {#if can(user, 'SET')}
            <a class="alert-link" href="{base}/market/settings?section=credits">
              {$_('pages.credits.disabled-link')}
            </a>
          {/if}
        </div>
      </div>
    {/if}

    {#if section === 'accounts'}
      <div class="row g-3">
        {@render statCard('primary', $_('pages.credits.totals.issued'), creditsText(totals.issued))}
        {@render statCard('secondary', $_('pages.credits.totals.spent'), creditsText(totals.spent))}
        {@render statCard('warning', $_('pages.credits.totals.held'), creditsText(totals.held))}
        {@render statCard(
          'info',
          $_('pages.credits.totals.outstanding'),
          creditsText(totals.outstanding),
        )}
      </div>
    {:else}
      <div class="row g-3">
        {@render statCard('primary', $_('pages.credits.totals.issued'), creditsText(totals.issued))}
        {@render statCard('secondary', $_('pages.credits.totals.spent'), creditsText(totals.spent))}
        {@render statCard('warning', $_('pages.credits.totals.held'), creditsText(totals.held))}
        {@render statCard(
          'danger',
          $_('pages.credits.totals.revoked'),
          creditsText(totals.revoked),
        )}
        {@render statCard(
          'dark',
          $_('pages.credits.totals.external'),
          creditsText(totals.external),
        )}
        {@render statCard(
          'info',
          $_('pages.credits.totals.outstanding'),
          creditsText(totals.outstanding),
        )}
      </div>
    {/if}

    {#if section === 'transactions'}
      <div class="card">
        <div class="card-body vstack gap-3">
          <div class="d-flex flex-wrap gap-2 align-items-center">
            <div class="dropdown">
              <button
                type="button"
                class="btn btn-outline-secondary dropdown-toggle"
                data-bs-toggle="dropdown"
                data-bs-auto-close="outside"
                aria-expanded="false">
                {$_('pages.credits.filter.types', { values: { count: types.length } })}
              </button>
              <div class="dropdown-menu p-2">
                {#each CREDIT_TX_TYPES as type (type)}
                  <div class="form-check mx-2">
                    <input
                      class="form-check-input"
                      type="checkbox"
                      id="credit-type-{type}"
                      checked={types.includes(type)}
                      onchange={() => (typesDraft = toggleType(types, type))} />
                    <label class="form-check-label" for="credit-type-{type}">
                      {$_(`enums.credit-tx.${type}`)}
                    </label>
                  </div>
                {/each}
              </div>
            </div>
            <input
              type="text"
              inputmode="numeric"
              class="form-control w-auto"
              style="max-width: 140px;"
              placeholder={$_('pages.credits.filter.user-id')}
              aria-label={$_('pages.credits.filter.user-id')}
              value={userIdText}
              oninput={(event) => (userIdDraft = event.currentTarget.value)} />
            <input
              type="text"
              inputmode="numeric"
              class="form-control w-auto"
              style="max-width: 140px;"
              placeholder={$_('pages.credits.filter.order-id')}
              aria-label={$_('pages.credits.filter.order-id')}
              value={orderIdText}
              oninput={(event) => (orderIdDraft = event.currentTarget.value)} />
            <DateRange
              bind:from={() => from, (value) => (fromDraft = value)}
              bind:to={() => to, (value) => (toDraft = value)}
              onApply={applyFilters} />
          </div>
          <div class="d-flex gap-2">
            <button type="button" class="btn btn-secondary" onclick={applyFilters}>
              {$_('common.apply')}
            </button>
            <button type="button" class="btn btn-outline-secondary" onclick={clearFilters}>
              {$_('common.clear-filters')}
            </button>
          </div>
        </div>
      </div>
    {/if}

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_(
            section === 'transactions'
              ? 'pages.credits.transaction-count'
              : 'pages.credits.account-count',
            { values: { count: section === 'transactions' ? transactionCount : accountCount } },
          )}
        </div>
        <div slot="middle" style="width: 250px;">
          {#if section === 'accounts'}
            <SearchInput
              initialValue={search}
              searching={$navigating !== null}
              placeholderKey="plugins.pano-plugin-market.search.credit-accounts"
              onchange={onSearchChange}
              autofocus />
          {/if}
        </div>
        <CardFilters slot="right">
          <CardFiltersItem href="/market/credits" active={section === 'accounts'}>
            {$_('pages.credits.tab.accounts')}
          </CardFiltersItem>
          <CardFiltersItem
            href="/market/credits?section=transactions"
            active={section === 'transactions'}>
            {$_('pages.credits.tab.transactions')}
          </CardFiltersItem>
        </CardFilters>
      </CardHeader>

      {#if section === 'accounts'}
        {#if accounts.length === 0}
          <NoContent icon="" />
        {:else}
          <div class="table-responsive">
            <table class="table table-hover align-middle">
              <thead>
                <tr>
                  <th class="align-middle text-nowrap" scope="col" style="width: 50px;"></th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('pages.credits.table.player')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('pages.credits.table.balance')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('pages.credits.table.value')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {#each accounts as account (account.userId)}
                  <tr>
                    <th class="align-middle" scope="row">
                      <div class="dropdown position-static">
                        <button
                          type="button"
                          class="btn btn-link"
                          data-bs-toggle="dropdown"
                          title={$_('common.actions')}
                          aria-label={$_('common.actions')}>
                          <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                        </button>
                        <div
                          class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                          <a
                            class="dropdown-item"
                            href="{base}/market/credits/account/{account.userId}?player={encodeURIComponent(
                              account.username,
                            )}">
                            <i class="fa-solid fa-list me-2" aria-hidden="true"></i>
                            {$_('pages.credits.view-ledger')}
                          </a>
                          <button
                            type="button"
                            class="dropdown-item"
                            onclick={() => openAdjust('grant', account)}>
                            <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
                            {$_('pages.credits.grant')}
                          </button>
                          <button
                            type="button"
                            class="dropdown-item link-danger"
                            onclick={() => openAdjust('revoke', account)}>
                            <i class="fa-solid fa-minus me-2" aria-hidden="true"></i>
                            {$_('pages.credits.revoke')}
                          </button>
                        </div>
                      </div>
                    </th>
                    <td><PlayerCell username={account.username} /></td>
                    <td class="text-nowrap">{creditsText(account.balance)}</td>
                    <td class="text-nowrap">
                      {ctx
                        ? fmt.money(creditsValue(account.balance, ctx.creditValue), ctx.currency)
                        : '—'}
                    </td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        {/if}
      {:else if transactions.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.date')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.type')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.credits.table.player')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.credits.table.amount')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.credits.table.shortfall')}
                </th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.order')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.credits.table.by')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.note')}</th>
              </tr>
            </thead>
            <tbody>
              {#each transactions as tx (tx.id)}
                <tr>
                  <td class="text-nowrap">{dateText(tx.createdAt)}</td>
                  <td>
                    <span class="badge {creditBadgeClass(tx)}">
                      {$_(`enums.credit-tx.${tx.type}`)}
                    </span>
                  </td>
                  <td><PlayerCell username={tx.username} /></td>
                  <td class="text-nowrap">{signedAmount(tx, (v) => creditsText(v))}</td>
                  <td class="text-nowrap">
                    {tx.shortfall > 0 ? creditsText(tx.shortfall) : ''}
                  </td>
                  <td class="text-nowrap">
                    {#if tx.orderId}
                      {#if can(user, 'OV')}
                        <a href="{base}/market/orders/detail/{tx.orderId}">#{tx.orderId}</a>
                      {:else}
                        #{tx.orderId}
                      {/if}
                    {/if}
                  </td>
                  <td>{tx.actorUsername ?? $_('common.system')}</td>
                  <td style="min-width: 160px;">{tx.note ?? ''}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}

      {#if totalPage > 1}
        <div class="card-footer">
          <Pagination
            page={currentPage}
            {totalPage}
            on:firstPageClick={() => onPageClick(1)}
            on:lastPageClick={() => onPageClick(totalPage)}
            on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
        </div>
      {/if}
    </div>
  {/if}
</MarketLayout>

<CreditAdjustModal bind:this={adjustModal} {ctx} onSaved={onAdjusted} />

<script module>
  import { api } from '@panomc/sdk/plugin-api';
  import { failureOf } from '../utils/api.js';
  import { loadList } from '../utils/list.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const section =
      event.url.searchParams.get('section') === 'transactions' ? 'transactions' : 'accounts';
    const result =
      section === 'transactions'
        ? await loadList(event, {
            path: '/credits/transactions',
            params: ['type', 'userId', 'orderId', 'from', 'to'],
            nodes: ['PAY'],
            title: 'pages.credits.title',
          })
        : await loadList(event, {
            path: '/credits/accounts',
            params: ['search'],
            nodes: ['PAY'],
            title: 'pages.credits.title',
          });
    result.data.section = section;
    // The totals come with the accounts list; the transactions tab asks for one row only.
    if (section === 'transactions' && !result.data.error) {
      const totals = await api.panel.get({
        path: '/credits/accounts' + '?pageSize=1',
        request: event,
      });
      result.data.totals = failureOf(totals) === null ? (totals.totals ?? {}) : {};
    }
    return result;
  }
</script>

<script>
  import {
    CardFilters,
    CardFiltersItem,
    CardHeader,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import { base, invalidateAll, navigating, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import DateRange from '../components/DateRange.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PlayerCell from '../components/PlayerCell.svelte';
  import CreditAdjustModal from '../components/modals/CreditAdjustModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import {
    CREDIT_TX_TYPES,
    creditBadgeClass,
    creditsValue,
    normalizeTypes,
    signedAmount,
    toggleType,
    transactionParams,
  } from '../utils/credits.js';
  import { gotoList } from '../utils/list.js';
  import { pageOf } from '../utils/page.js';
  import { fmt, currentLocale } from '../utils/locale.js';
  import { can } from '../utils/permissions.js';

  let { data } = $props();

  let adjustModal = $state(null);

  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const section = $derived(data.section === 'transactions' ? 'transactions' : 'accounts');
  const list = $derived(pageOf(data));
  const accounts = $derived(section === 'accounts' ? list.items : []);
  const transactions = $derived(section === 'transactions' ? list.items : []);
  const accountCount = $derived(section === 'accounts' ? list.totalItems : 0);
  const transactionCount = $derived(section === 'transactions' ? list.totalItems : 0);
  const totals = $derived(data.totals ?? {});
  const totalPage = $derived(list.totalPages);
  const currentPage = $derived(list.number);
  const search = $derived($page.url.searchParams.get('search') || '');

  // Filters of the transactions tab live in the URL; the inputs start from it.
  // A draft is null until the admin edits the input; until then the URL value shows.
  let typesDraft = $state(null);
  let userIdDraft = $state(null);
  let orderIdDraft = $state(null);
  let fromDraft = $state(undefined);
  let toDraft = $state(undefined);
  const types = $derived(typesDraft ?? normalizeTypes(data.filters?.type));
  const userIdText = $derived(userIdDraft ?? data.filters?.userId ?? '');
  const orderIdText = $derived(orderIdDraft ?? data.filters?.orderId ?? '');
  const from = $derived(fromDraft === undefined ? Number(data.filters?.from) || null : fromDraft);
  const to = $derived(toDraft === undefined ? Number(data.filters?.to) || null : toDraft);

  const creditsText = (amount) => fmt.credits(amount ?? 0, ctx?.creditName ?? '');

  function dateText(epoch) {
    if (!epoch) return '—';
    return new Date(epoch).toLocaleString(currentLocale());
  }

  function openAdjust(mode, account) {
    adjustModal?.open({ mode, account });
  }

  // A modal is hidden before the page is re-loaded (13 §1.4); Bootstrap's fade takes 300 ms.
  function onAdjusted() {
    setTimeout(() => invalidateAll(), 350);
  }

  function onSearchChange(value) {
    return gotoList('/market/credits', { search: value || null });
  }

  function applyFilters() {
    return gotoList(
      '/market/credits',
      transactionParams({ types, userId: userIdText, orderId: orderIdText, from, to }),
    );
  }

  function clearFilters() {
    typesDraft = [];
    userIdDraft = '';
    orderIdDraft = '';
    fromDraft = null;
    toDraft = null;
    return gotoList('/market/credits', { section: 'transactions' });
  }

  function onPageClick(pageNum) {
    const params =
      section === 'transactions'
        ? transactionParams({
            types: normalizeTypes(data.filters?.type),
            userId: data.filters?.userId,
            orderId: data.filters?.orderId,
            from: data.filters?.from,
            to: data.filters?.to,
          })
        : { search: search || null };
    return gotoList('/market/credits', { ...params, page: pageNum > 1 ? pageNum : null });
  }
</script>
