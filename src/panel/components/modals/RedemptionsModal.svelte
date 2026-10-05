<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_('modals.redemptions.title', { values: { code } })}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body p-0">
        {#if loading}
          <div class="text-center text-body-secondary py-5">
            <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
            {$_('common.loading')}
          </div>
        {:else if error}
          <LoadError {error} onRetry={() => load(currentPage)} />
        {:else if redemptions.length === 0}
          <NoContent icon="" />
        {:else}
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.redemptions.table.order')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.redemptions.table.player')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.redemptions.table.amount')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.redemptions.table.state')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.redemptions.table.date')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {#each redemptions as row (row.orderId)}
                  <tr>
                    <td class="align-middle text-nowrap">
                      {#if can(user, 'OV')}
                        <a href="{base}/market/orders/detail/{row.orderId}">#{row.orderId}</a>
                      {:else}
                        #{row.orderId}
                      {/if}
                    </td>
                    <td class="align-middle">
                      {#if row.playerUsername}
                        <PlayerCell username={row.playerUsername} />
                      {:else}
                        <span class="text-body-secondary">{$_('modals.redemptions.guest')}</span>
                      {/if}
                    </td>
                    <td class="align-middle text-nowrap">
                      {fmt.money(row.amount, row.currency ?? ctx?.currency)}
                    </td>
                    <td class="align-middle text-nowrap">
                      <span class="badge {redemptionBadge(row.state)}">
                        {$_(`enums.redemption.${row.state}`)}
                      </span>
                    </td>
                    <td class="align-middle text-nowrap">{dateText(row.createdAt)}</td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        {/if}
      </div>
      {#if !loading && !error && totalPage > 1}
        <div class="modal-footer justify-content-start">
          <Pagination
            page={currentPage}
            {totalPage}
            on:firstPageClick={() => load(1)}
            on:lastPageClick={() => load(totalPage)}
            on:pageLinkClick={(event) => load(event.detail.page)} />
        </div>
      {/if}
    </div>
  </div>
</div>

<script>
  import { NoContent, Pagination } from '@panomc/sdk/components/panel';
  import { base, page } from '@panomc/sdk/svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import { redemptionBadge, redemptionsPath } from '../../utils/discounts.js';
  import { currentLocale, fmt } from '../../utils/locale.js';
  import { can } from '../../utils/permissions.js';
  import LoadError from '../LoadError.svelte';
  import PlayerCell from '../PlayerCell.svelte';
  import { showModal } from '../order-detail/send.js';

  // Internal paging: the page URL never changes. open({ kind, id, code }) kind = coupons | gifts | creator-codes.
  let { ctx = null } = $props();

  let modalElement = $state(null);
  let target = $state(null);
  let redemptions = $state([]);
  let totalPage = $state(1);
  let currentPage = $state(1);
  let loading = $state(false);
  let error = $state(null);
  let tag = 0;

  const user = $derived($page.data?.user);
  const code = $derived(target?.code ?? '');

  const dateText = (epoch) => (epoch ? new Date(epoch).toLocaleString(currentLocale()) : '—');

  async function load(pageNum) {
    if (!target) return;
    const mine = ++tag;
    loading = true;
    error = null;
    const path = redemptionsPath(target.kind, target.id, pageNum);
    const result = path ? await call(ApiUtil.get({ path: marketPath(path) })) : { ok: false, error: 'BAD_REQUEST' };
    if (mine !== tag) return;
    loading = false;
    if (!result.ok) {
      // a stale page falls back to the first one
      if (result.error === 'PAGE_NOT_FOUND' && pageNum > 1) return load(1);
      error = result.error;
      redemptions = [];
      return;
    }
    redemptions = result.body.redemptions ?? [];
    totalPage = result.body.totalPage ?? 1;
    currentPage = pageNum;
  }

  export function open(options) {
    target = options;
    redemptions = [];
    totalPage = 1;
    currentPage = 1;
    showModal(modalElement);
    return load(1);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
