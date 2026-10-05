<MarketLayout area="customers" sections={sectionsFor('customers', user)} active="credits">
  {#snippet left()}
    <div class="d-flex align-items-center gap-3 flex-wrap">
      <a class="btn btn-link px-0" href="{base}/market/credits">
        <i class="fa-solid fa-arrow-left me-2" aria-hidden="true"></i>
        {$_('common.back')}
      </a>
      {#if !error || error !== 'NOT_FOUND'}
        {#if username}
          <PlayerCell {username} />
        {:else}
          <span>{$_('pages.credit-account.player-id', { values: { id: data.userId } })}</span>
        {/if}
        <span class="badge text-bg-info fs-6">{creditsText(view.balance)}</span>
      {/if}
    </div>
  {/snippet}
  {#snippet right()}
    {#if !error}
      <button type="button" class="btn btn-secondary" onclick={() => openAdjust('grant')}>
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.credits.grant')}</span>
      </button>
      <button type="button" class="btn btn-outline-danger" onclick={() => openAdjust('revoke')}>
        <i class="fa-solid fa-minus" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.credits.revoke')}</span>
      </button>
    {/if}
  {/snippet}

  {#if error}
    <LoadError {error} onRetry={() => refresh()} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.credit-account.entry-count', { values: { count: entryCount } })}
        </div>
      </CardHeader>

      {#if entries.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.type')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.amount')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.credit-account.table.balance-after')}
                </th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.order')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.credits.table.by')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.note')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.credits.table.date')}</th>
              </tr>
            </thead>
            <tbody>
              {#each entries as entry (entry.id)}
                <tr>
                  <td>
                    <span class="badge {creditBadgeClass(entry)}">
                      {$_(`enums.credit-tx.${entry.type}`)}
                    </span>
                  </td>
                  <td class="text-nowrap">{signedAmount(entry, (v) => creditsText(v))}</td>
                  <td class="text-nowrap">{creditsText(entry.balanceAfter)}</td>
                  <td class="text-nowrap">
                    {#if entry.orderId}
                      {#if can(user, 'OV')}
                        <a href="{base}/market/orders/detail/{entry.orderId}">#{entry.orderId}</a>
                      {:else}
                        #{entry.orderId}
                      {/if}
                    {/if}
                  </td>
                  <td>{entry.actorUsername ?? $_('common.system')}</td>
                  <td style="min-width: 160px;">{entry.note ?? ''}</td>
                  <td class="text-nowrap">{dateText(entry.createdAt)}</td>
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

<CreditAdjustModal bind:this={adjustModal} {ctx} onSaved={() => refresh()} />

<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { marketPath } from '../utils/api.js';
  import { loadContext } from '../utils/context.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const { pageTitle } = await event.parent();
    pageTitle?.set?.('plugins.pano-plugin-market.pages.credit-account.title');

    const userId = event.params.userId;
    const requested = parseInt(event.url.searchParams.get('page')) || 1;
    const fetchPage = (p) =>
      ApiUtil.get({
        path:
          marketPath(`/credits/accounts/${encodeURIComponent(userId)}`) +
          buildQueryParams({ page: p === 1 ? null : p }),
        request: event,
      });

    let [body, ctx] = await Promise.all([fetchPage(requested), loadContext(event)]);
    let effectivePage = requested;
    if (body?.error === 'PAGE_NOT_FOUND' && requested > 1) {
      effectivePage = 1;
      body = await fetchPage(1);
    }
    if (!body || typeof body !== 'object' || body.error)
      return {
        data: {
          userId,
          entries: [],
          entryCount: 0,
          totalPage: 1,
          page: 1,
          error: (body && typeof body === 'object' && body.error) || 'NETWORK_ERROR',
          ctx,
        },
      };
    return { data: { ...body, userId, page: effectivePage, ctx } };
  }
</script>

<script>
  import { CardHeader, NoContent, Pagination } from '@panomc/sdk/components/panel';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PlayerCell from '../components/PlayerCell.svelte';
  import CreditAdjustModal from '../components/modals/CreditAdjustModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call } from '../utils/api.js';
  import { creditBadgeClass, signedAmount } from '../utils/credits.js';
  import { fmt, currentLocale } from '../utils/locale.js';
  import { can } from '../utils/permissions.js';

  let { data } = $props();

  // Detail pages never invalidateAll() after a mutation: the loaded object lives in $state and a
  // local refresh() re-GETs it (13 section 1.4).
  let loaded = $state.raw(null);
  let refreshing = $state(false);
  let refreshError = $state(null);
  let adjustModal = $state(null);

  const view = $derived(loaded ?? data);
  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const error = $derived(refreshError ?? data.error ?? null);
  const entries = $derived(view.entries ?? []);
  const entryCount = $derived(view.entryCount ?? entries.length);
  const totalPage = $derived(view.totalPage ?? 1);
  const currentPage = $derived(view.page ?? 1);
  // The API names no player; a list link carries ?player=<name> as a display hint (cosmetic only,
  // every request goes by the user id of the path).
  const username = $derived(view.username ?? $page.url.searchParams.get('player') ?? null);
  const account = $derived({
    userId: Number(data.userId),
    username: username ?? '',
    balance: view.balance ?? 0,
  });

  const creditsText = (amount) => fmt.credits(amount ?? 0, ctx?.creditName ?? '');

  function dateText(epoch) {
    return epoch ? new Date(epoch).toLocaleString(currentLocale()) : '\u2014';
  }

  function openAdjust(mode) {
    adjustModal?.open({
      mode,
      account: { ...account, username: username ?? `#${account.userId}` },
    });
  }

  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    const result = await call(
      ApiUtil.get({
        path:
          marketPath(`/credits/accounts/${encodeURIComponent(data.userId)}`) +
          buildQueryParams({ page: currentPage > 1 ? currentPage : null }),
      }),
    );
    refreshing = false;
    if (!result.ok) {
      refreshError = result.error;
      return;
    }
    refreshError = null;
    loaded = { ...result.body, userId: data.userId, page: currentPage };
  }

  function onPageClick(pageNum) {
    const hint = $page.url.searchParams.get('player');
    const query = buildQueryParams({
      page: pageNum > 1 ? pageNum : null,
      player: hint || null,
    });
    return goto(`${base}/market/credits/account/${data.userId}${query}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }
</script>
