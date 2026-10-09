<MarketLayout area="catalog" sections={sectionsFor('catalog', user)} active="goals">
  {#snippet right()}
    <button type="button" class="btn btn-secondary" onclick={() => goalModal?.open(null)}>
      <i class="fa-solid fa-plus" aria-hidden="true"></i>
      <span class="d-lg-inline d-none ms-2">{$_('pages.goals.create-goal')}</span>
    </button>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">{$_('pages.goals.count', { values: { count: goals.length } })}</div>
      </CardHeader>

      {#if goals.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="text-nowrap" scope="col" style="width: 50px;"></th>
                <th class="text-nowrap" scope="col">{$_('pages.goals.table.name')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.goals.table.metric')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.goals.table.progress')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.goals.table.period')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.goals.table.ends')}</th>
                <th class="text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="text-nowrap" scope="col">{$_('pages.goals.table.on-store')}</th>
              </tr>
            </thead>
            <tbody>
              {#each goals as goal (goal.id)}
                {@const percent = progressPercent(goal)}
                <tr>
                  <th scope="row">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
                      </button>
                      <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                        <button type="button" class="dropdown-item" onclick={() => goalModal?.open(goal)}>
                          <i class="fa-solid fa-pen me-2" aria-hidden="true"></i>
                          {$_('common.edit')}
                        </button>
                        <button
                          type="button"
                          class="dropdown-item text-danger"
                          onclick={() => deleteGoal(goal)}>
                          <i class="fa-solid fa-trash me-2" aria-hidden="true"></i>
                          {$_('common.delete')}
                        </button>
                      </div>
                    </div>
                  </th>
                  <td>
                    <button
                      type="button"
                      class="btn btn-link p-0 text-start text-decoration-none"
                      onclick={() => goalModal?.open(goal)}>
                      {goal.name}
                    </button>
                    {#if goal.description}
                      <div class="small text-body-secondary text-truncate" style="max-width: 300px;">
                        {goal.description}
                      </div>
                    {/if}
                  </td>
                  <td class="text-nowrap">{$_(`enums.goal-metric.${goal.metric}`)}</td>
                  <td style="min-width: 180px;">
                    <div class="small">{progressText(goal)}</div>
                    <div
                      class="progress"
                      role="progressbar"
                      aria-label={$_('pages.goals.table.progress')}
                      aria-valuenow={percent}
                      aria-valuemin="0"
                      aria-valuemax="100">
                      <div class="progress-bar" style="width: {percent}%">{percent}%</div>
                    </div>
                  </td>
                  <td class="text-nowrap">{$_(`enums.goal-period.${goal.period}`)}</td>
                  <td class="text-nowrap">
                    {goal.endsAt ? new Date(Number(goal.endsAt)).toLocaleString(currentLocale()) : '—'}
                  </td>
                  <td>
                    {#if goal.status === 'ACTIVE'}
                      <span class="badge text-bg-success">{$_('common.active')}</span>
                    {:else}
                      <span class="badge text-bg-secondary">{$_('common.inactive')}</span>
                    {/if}
                  </td>
                  <td>{goal.showOnStore ? $_('common.yes') : $_('common.no')}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  {/if}
</MarketLayout>

<GoalModal bind:this={goalModal} {ctx} onSaved={() => afterModalHidden(() => invalidateAll())} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import { loadList } from '../utils/list.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/goals',
      params: [],
      nodes: ['CAT'],
      title: 'pages.goals.title',
    });
  }
</script>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { page, invalidateAll } from '@panomc/sdk/svelte';
  import { _, showErrorToast, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import GoalModal from '../components/modals/GoalModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call, errorKey } from '../utils/api.js';
  import { fmt, currentLocale } from '../utils/locale.js';
  import { progressPercent } from '../utils/goals.js';

  let { data } = $props();

  let goalModal = $state(null);
  let confirmModal = $state(null);

  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const goals = $derived(data.items ?? []);

  // A modal is hidden before the page is re-loaded (13 §1.4); Bootstrap's fade takes 300 ms.
  function afterModalHidden(run) {
    setTimeout(run, 350);
  }

  // Money for REVENUE, plain counts otherwise.
  function progressText(goal) {
    if (goal.metric === 'REVENUE') {
      const currency = goal.currency ?? ctx?.currency;
      return `${fmt.money(goal.progress ?? 0, currency)} / ${fmt.money(goal.target, currency)}`;
    }
    return `${goal.progress ?? 0} / ${goal.target}`;
  }

  function deleteGoal(goal) {
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      title: $_('pages.goals.delete-title'),
      description: $_('pages.goals.confirm-delete', { values: { name: goal.name } }),
      confirmLabel: $_('common.delete'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(api.panel.delete({ path: `/goals/${goal.id}` }));
        if (!result.ok) {
          showErrorToast($_(errorKey(result.error)));
          if (result.error === 'NOT_FOUND') afterModalHidden(() => invalidateAll());
          return false;
        }
        showSuccessToast($_('pages.goals.toast-delete-success'));
        afterModalHidden(() => invalidateAll());
      },
    });
  }
</script>
