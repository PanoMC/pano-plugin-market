<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-xl modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_('modals.payment-events.title')}
          {#if payment}<span class="text-body-secondary">#{payment.id}</span>{/if}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body p-0">
        {#if loading}
          <div class="text-center py-5">
            <span class="spinner-border" role="status" aria-hidden="true"></span>
          </div>
        {:else if error}
          <div class="p-3">
            <LoadError {error} onRetry={() => load(currentPage)} />
          </div>
        {:else if events.length === 0}
          <NoContent icon="" />
        {:else}
          <div class="table-responsive">
            <table class="table table-hover">
              <thead>
                <tr>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.date')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.direction')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.channel')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.event')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.verified')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.response')}
                  </th>
                  <th class="align-middle text-nowrap" scope="col">
                    {$_('modals.payment-events.table.ip')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {#each events as event (event.id)}
                  <tr>
                    <td class="align-middle text-nowrap">
                      <DateComponent time={event.createdAt} />
                    </td>
                    <td class="align-middle">{event.direction ?? '—'}</td>
                    <td class="align-middle">{event.channel ?? '—'}</td>
                    <td class="align-middle">
                      <div>{event.eventKey ?? '—'}</div>
                      {#if event.eventTypes?.length}
                        <div class="text-body-secondary">
                          {Array.isArray(event.eventTypes)
                            ? event.eventTypes.join(', ')
                            : event.eventTypes}
                        </div>
                      {/if}
                      {#if event.error}
                        <div class="text-danger">{event.error}</div>
                      {/if}
                    </td>
                    <td class="align-middle"><StatusBadge kind="event" value={event.status} /></td>
                    <td class="align-middle">
                      <i
                        class="fa-solid {event.verified
                          ? 'fa-circle-check text-success'
                          : 'fa-circle-xmark text-danger'}"
                        role="img"
                        aria-label={event.verified ? $_('common.yes') : $_('common.no')}></i>
                    </td>
                    <td class="align-middle">{event.responseStatus ?? '—'}</td>
                    <td class="align-middle text-nowrap">{event.remoteIp ?? '—'}</td>
                  </tr>
                  {#if event.body !== undefined || event.headers !== undefined}
                    <tr>
                      <td class="align-middle" colspan="8">
                        {#if event.headers !== undefined && event.headers !== null}
                          <div class="text-body-secondary">
                            {$_('modals.payment-events.headers')}
                          </div>
                          <pre class="mb-2 overflow-auto">{asText(event.headers)}</pre>
                        {/if}
                        {#if event.body !== undefined && event.body !== null}
                          <div class="text-body-secondary">
                            {$_('modals.payment-events.body')}
                          </div>
                          <pre class="mb-0 overflow-auto">{asText(event.body)}</pre>
                        {/if}
                      </td>
                    </tr>
                  {/if}
                {/each}
              </tbody>
            </table>
          </div>
        {/if}
      </div>
      {#if totalPage > 1}
        <div class="modal-footer justify-content-start">
          <Pagination
            page={currentPage}
            {totalPage}
            on:firstPageClick={() => load(1)}
            on:lastPageClick={() => load(totalPage)}
            on:pageLinkClick={(e) => load(e.detail.page)} />
        </div>
      {/if}
    </div>
  </div>
</div>

<script>
  import { Date as DateComponent, NoContent, Pagination } from '@panomc/sdk/components/panel';
  import { buildQueryParams } from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';
  import LoadError from '../LoadError.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { pageOf } from '../../utils/page.js';
  import { asText } from './model.js';
  import { paymentEventsPath } from './requests.js';
  import { fetchPath, showModal } from './send.js';

  let modalElement = $state(null);
  let payment = $state(null);
  let events = $state([]);
  let loading = $state(false);
  let error = $state('');
  let currentPage = $state(1);
  let totalPage = $state(1);
  // A late answer of an earlier page / payment must not overwrite the newer one.
  let run = 0;

  async function load(page) {
    if (!payment) return;
    const mine = ++run;
    loading = true;
    error = '';
    const result = await fetchPath(
      paymentEventsPath(payment.id) + buildQueryParams({ page: page > 1 ? page : null }),
    );
    if (mine !== run) return;
    loading = false;
    if (!result.ok) {
      error = result.error;
      return;
    }
    const loaded = pageOf(result.body);
    events = loaded.items;
    currentPage = page;
    totalPage = loaded.totalPages;
  }

  /** payment: a row of detail.payments. */
  export function open(next) {
    payment = next;
    events = [];
    currentPage = 1;
    totalPage = 1;
    showModal(modalElement);
    load(1);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      run++;
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
