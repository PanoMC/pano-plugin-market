<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('discounts.payouts.count', { values: { count: visible.length } })}
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={query}
        autofocus
        placeholderKey="plugins.pano-plugin-market.search.creator-report"
        onchange={(value) => (query = value ?? '')} />
    </div>
    <div slot="right" class="dropdown">
      <button
        type="button"
        class="btn btn-sm btn-link"
        data-bs-toggle="dropdown"
        aria-expanded="false"
        title={$_('common.actions')}
        aria-label={$_('common.actions')}>
        <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
      </button>
      <div class="dropdown-menu dropdown-menu-end">
        <button type="button" class="dropdown-item" onclick={openRange}>
          <i class="fa-solid fa-calendar me-2" aria-hidden="true"></i>
          {$_('discounts.payouts.date-range')}
        </button>
        <button type="button" class="dropdown-item" onclick={exportCsv}>
          <i class="fa-solid fa-file-csv me-2" aria-hidden="true"></i>
          {$_('discounts.payouts.export-csv')}
        </button>
      </div>
    </div>
  </CardHeader>

  {#if visible.length === 0}
    <NoContent icon="" />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col" style="width: 50px;"></th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('discounts.payouts.table.creator')}
            </th>
            <th class="align-middle text-nowrap" scope="col">{$_('discounts.payouts.table.code')}</th>
            <th class="align-middle text-nowrap" scope="col">{$_('discounts.payouts.table.uses')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('discounts.payouts.table.revenue')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('discounts.payouts.table.earned')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('discounts.payouts.table.paid-out')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('discounts.payouts.table.available')}
            </th>
          </tr>
        </thead>
        <tbody>
          {#each visible as row (row.id)}
            <tr>
              <th scope="row" class="align-middle">
                <div class="dropdown position-static">
                  <button
                    type="button"
                    class="btn btn-link"
                    data-bs-toggle="dropdown"
                    title={$_('common.actions')}
                    aria-label={$_('common.actions')}>
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <a class="dropdown-item" href="{base}/market/discounts/creator/{row.id}">
                      <i class="fas fa-chart-line me-2"></i>
                      {$_('discounts.payouts.view-earnings')}
                    </a>
                    {#if canPayOut(row, mayPay)}
                      <button type="button" class="dropdown-item" onclick={() => onPayout(row)}>
                        <i class="fas fa-money-bill-transfer me-2"></i>
                        {$_('discounts.payouts.pay-out')}
                      </button>
                    {/if}
                  </div>
                </div>
              </th>
              <td class="align-middle"><PlayerCell username={row.creator} /></td>
              <td class="align-middle font-monospace">{row.code}</td>
              <td class="align-middle text-nowrap">{row.uses}</td>
              <td class="align-middle text-nowrap">{fmt.money(row.revenue, currency)}</td>
              <td class="align-middle text-nowrap">{fmt.money(row.earned, currency)}</td>
              <td class="align-middle text-nowrap">{fmt.money(row.paidOut, currency)}</td>
              <td class="align-middle text-nowrap" class:text-danger={row.available < 0}>
                {fmt.money(row.available, currency)}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</div>

<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={rangeModal}>
  <div class="modal-dialog modal-dialog-centered modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('discounts.payouts.date-range')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body">
        {#key rangeKey}
          <DateRange bind:from={rangeFrom} bind:to={rangeTo} onApply={applyRange} />
        {/key}
      </div>
    </div>
  </div>
</div>

<script>
  import { CardHeader, NoContent, SearchInput } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { can } from '../../utils/permissions.js';
  import { CREATOR_REPORT_COLUMNS, creatorReportRows, creatorsFilename, downloadCsv, toCsv } from '../../utils/csv.js';
  import { canPayOut, filterCreators } from '../../utils/discounts.js';
  import { gotoList } from '../../utils/list.js';
  import { fmt } from '../../utils/locale.js';
  import DateRange from '../DateRange.svelte';
  import PlayerCell from '../PlayerCell.svelte';
  import { hideModal, showModal } from '../order-detail/send.js';

  // The report is not paginated; search filters it in the browser (no request).
  let { creators = [], currency = '', filters = {}, user = null, onPayout = () => {} } = $props();

  let query = $state('');
  let rangeModal = $state(null);
  let rangeFrom = $state(null);
  let rangeTo = $state(null);
  let rangeKey = $state(0);

  const mayPay = $derived(can(user, 'PAY'));
  const visible = $derived(filterCreators(creators, query));

  function openRange() {
    rangeFrom = Number(filters?.from) || null;
    rangeTo = Number(filters?.to) || null;
    rangeKey += 1;
    showModal(rangeModal);
  }

  function applyRange() {
    hideModal(rangeModal);
    setTimeout(
      () =>
        gotoList('/market/discounts', {
          section: 'payouts',
          from: rangeFrom ?? null,
          to: rangeTo ?? null,
        }),
      350,
    );
  }

  // The export covers the whole report of the selected range, not only the rows the search shows.
  function exportCsv() {
    const text = toCsv(creatorReportRows(creators, currency), CREATOR_REPORT_COLUMNS);
    downloadCsv(creatorsFilename(), text);
  }

  $effect(() => {
    const el = rangeModal;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
