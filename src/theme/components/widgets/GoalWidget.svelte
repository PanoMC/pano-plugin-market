{#if render}
  <div class="vstack gap-3">
    {#each goals as goal (goal.id)}
      <div class="card">
        <div class="card-body">
          <h2 class="h6 card-title mb-1">
            <i class="fa-solid fa-bullseye me-2 text-body-secondary" aria-hidden="true"></i
            >{goal.name}
          </h2>
          {#if goal.description}
            <p class="small text-body-secondary mb-2">{goal.description}</p>
          {/if}
          <div
            class="progress"
            role="progressbar"
            aria-label={goal.name}
            aria-valuenow={goal.valueNow}
            aria-valuemin="0"
            aria-valuemax="100">
            <div class={['progress-bar', goal.complete && 'bg-success']} style={goal.width}></div>
          </div>
          <div class="d-flex justify-content-between small mt-1">
            <span>
              {$_('theme.widgets.goal.progress', {
                values: {
                  progress: goal.revenue
                    ? formatMoney(goal.progress, goal.currency)
                    : formatNumber(goal.progress),
                  target: goal.revenue
                    ? formatMoney(goal.target, goal.currency)
                    : formatNumber(goal.target),
                },
              })}
            </span>
            <span class="text-body-secondary">{Math.floor(goal.percent)}%</span>
          </div>
          {#if goal.end}
            <div class="small text-body-secondary mt-1">
              <i class="fa-regular fa-clock me-1" aria-hidden="true"></i>
              {#if goal.end.kind === 'COUNTDOWN'}
                {$_('theme.widgets.goal.ends-in', { values: { time: goal.end.text } })}
              {:else}
                {$_('theme.widgets.goal.ends-on', { values: { date: formatDate(goal.end.ms) } })}
              {/if}
            </div>
          {/if}
        </div>
      </div>
    {/each}
  </div>
{/if}

<script>
  import { _ } from '../../../i18n.js';
  import { formatDate, formatMoney } from '../../utils/format.js';
  import { now } from '../../stores/clock.js';
  import { goalView, shouldRender } from './widgetsModel.js';

  /** data: the widgets payload (`goals`, ...), plus `sidebars` / `sidebarId` when it sits in a host sidebar. */
  let { data = {} } = $props();

  const render = $derived(shouldRender(data, 'goals'));
  // time-dependent text only once the clock runs, so the server render and the first client render match
  const goals = $derived((data?.goals ?? []).map((goal) => goalView(goal, $now)));

  const formatNumber = (value) => String(value);
</script>
