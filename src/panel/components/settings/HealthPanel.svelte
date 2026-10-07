{#if !model}
  <LoadError error={extraError ?? 'NETWORK_ERROR'} onRetry={refresh} />
{:else}
  <div class="vstack gap-3">
    <div class="d-flex justify-content-end">
      <button type="button" class="btn btn-secondary" onclick={refresh} disabled={loading}>
        {#if loading}
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {:else}
          <i class="fa-solid fa-rotate me-2" aria-hidden="true"></i>
        {/if}
        {$_('settings.health.refresh')}
      </button>
    </div>

    {#if !model.schemaOk}
      <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
        <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
        <div>
          <b>{$_('alerts.schema-degraded.title')}</b>
          <div>{$_('alerts.schema-degraded.body')}</div>
          <ul class="mb-0">
            {#each model.missing as item, index (index)}
              <li>{item}</li>
            {/each}
            {#each model.unfixed as item, index (index)}
              <li>{item}</li>
            {/each}
          </ul>
        </div>
      </div>
    {/if}

    {#if model.ipTrustWarning}
      <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
        <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
        <div>
          <b>{$_('settings.health.ip-trust.title')}</b>
          <div>{$_('settings.health.ip-trust.body')}</div>
        </div>
      </div>
    {/if}

    <div class="row g-3">
      {#each model.queues as card (card.id)}
        <div class="col-6 col-md-4 col-xl-2">
          <div class="card text-bg-{card.variant} h-100">
            <div class="card-body">
              <p class="m-0">{$_(`settings.health.queue.${card.id}`)}</p>
              <span class="fs-2 lh-1">{card.value}</span>
            </div>
          </div>
        </div>
      {/each}
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">{$_('settings.health.status.title')}</div>
        <div slot="right">
          <button type="button" class="btn btn-sm btn-link" disabled={loading} onclick={recheck}>
            <i class="fa-solid fa-rotate me-2" aria-hidden="true"></i>
            {$_('settings.health.recheck')}
          </button>
        </div>
      </CardHeader>
      <div class="table-responsive">
        <table class="table table-hover mb-0">
          <tbody>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.schema')}</th>
              <td class="align-middle">
                <span class="badge text-bg-{model.schemaOk ? 'success' : 'danger'}">
                  {$_(model.schemaOk ? 'settings.health.ok' : 'settings.health.problem')}
                </span>
              </td>
            </tr>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.runtime')}</th>
              <td class="align-middle">{model.runtimeState}</td>
            </tr>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.mail')}</th>
              <td class="align-middle">
                <span class="badge text-bg-{mailClass}">{mailText}</span>
              </td>
            </tr>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.credits')}</th>
              <td class="align-middle">
                {#if model.credits}
                  <span class="badge text-bg-{model.credits.ok ? 'success' : 'danger'}">
                    {$_(model.credits.ok ? 'settings.health.ok' : 'settings.health.problem')}
                  </span>
                  {#if model.credits.checkedAt}
                    <span class="text-body-secondary ms-2">
                      <DateComponent time={model.credits.checkedAt} relativeFormat={true} />
                    </span>
                  {/if}
                  {#if model.credits.problems.length > 0}
                    <ul class="mb-0 mt-2">
                      {#each model.credits.problems as problem, index (index)}
                        <li>{problem}</li>
                      {/each}
                    </ul>
                  {/if}
                {:else}
                  <span class="text-body-secondary">{$_('settings.health.not-checked')}</span>
                {/if}
              </td>
            </tr>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.ip-trust')}</th>
              <td class="align-middle">
                <span class="badge text-bg-{model.ipTrustWarning ? 'warning' : 'success'}">
                  {$_(model.ipTrustWarning ? 'settings.health.problem' : 'settings.health.ok')}
                </span>
              </td>
            </tr>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.locked')}</th>
              <td class="align-middle">{model.lockedSubjects}</td>
            </tr>
            <tr>
              <th scope="row" class="align-middle">{$_('settings.health.status.rejected')}</th>
              <td class="align-middle">{model.rejectedEventsLastHour}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('settings.health.jobs.title', { values: { count: model.jobs.length } })}
        </div>
      </CardHeader>
      {#if model.jobs.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.jobs.name')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.jobs.last-run')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.jobs.lag')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.jobs.last-error')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each model.jobs as job, index (index)}
                <tr>
                  <td class="align-middle">{job.name}</td>
                  <td class="align-middle text-nowrap">
                    {#if job.lastRunAt}
                      <DateComponent time={job.lastRunAt} relativeFormat={true} />
                    {:else}
                      <span class="text-body-secondary">{$_('common.never')}</span>
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap" class:text-danger={job.lagDanger}>
                    {#if job.lagSeconds !== null}
                      {$_('settings.health.jobs.lag-seconds', {
                        values: { seconds: job.lagSeconds },
                      })}
                    {/if}
                  </td>
                  <td class="align-middle">{job.lastError}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('settings.health.providers.title', { values: { count: model.providers.length } })}
        </div>
      </CardHeader>
      {#if model.providers.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.providers.id')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.providers.state')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each model.providers as provider (provider.id)}
                <tr>
                  <td class="align-middle">{provider.id}</td>
                  <td class="align-middle"
                    ><StatusBadge kind="provider" value={provider.state} /></td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('settings.health.servers.title', { values: { count: model.servers.length } })}
        </div>
      </CardHeader>
      {#if model.servers.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.servers.id')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.servers.state')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.health.servers.waiting')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each model.servers as server (server.id)}
                <tr>
                  <td class="align-middle">{server.id}</td>
                  <td class="align-middle">
                    {#if server.marketState}
                      <span class="badge {stateClass(server.marketState)}">
                        {$_(`settings.minecraft.state.${server.marketState}`)}
                      </span>
                    {/if}
                  </td>
                  <td class="align-middle">{server.waiting}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  </div>
{/if}

<script>
  import { untrack } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { CardHeader, Date as DateComponent, NoContent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import LoadError from '../LoadError.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { RECHECK_CREDITS_PATH, healthModel, isHealthReport } from '../../utils/health.js';
  import { stateClass } from '../../utils/minecraft-settings.js';
  import { toastError } from '../../utils/toast.js';

  // extra = GET /health (read-only report), null when that request failed. "Refresh" and "Re-check"
  // re-GET it in place (a navigation would remount the page).
  let { extra = null, extraError = null } = $props();

  const start = untrack(() => extra);
  let report = $state.raw(isHealthReport(start) ? start : null);
  let loading = $state(false);

  const model = $derived(report ? healthModel(report) : null);
  const mailText = $derived(
    model?.mail && ['OK', 'DISABLED', 'HOST_TOO_OLD'].includes(model.mail)
      ? $_(`settings.health.mail.${model.mail}`)
      : (model?.mail ?? ''),
  );
  const mailClass = $derived(
    model?.mail === 'OK' ? 'success' : model?.mail === 'HOST_TOO_OLD' ? 'danger' : 'warning',
  );

  async function load(path) {
    if (loading) return;
    loading = true;
    try {
      const result = await call(ApiUtil.get({ path: marketPath(path) }));
      if (!result.ok || !isHealthReport(result.body)) {
        toastError($_, result.ok ? { error: 'NETWORK_ERROR', body: {} } : result);
        return;
      }
      report = result.body;
    } finally {
      loading = false;
    }
  }

  const refresh = () => load('/health');
  const recheck = () => load(RECHECK_CREDITS_PATH);
</script>
