<div class="card">
  <div class="card-body">
    <SwitchRow
      id="setting-moduleRecentBuyers"
      label={$_('settings.modules.recent-buyers')}
      hint={$_('settings.modules.recent-buyers-hint')}
      error={message('moduleRecentBuyers')}
      bind:checked={draft.moduleRecentBuyers} />
    <SettingRow
      id="setting-moduleRecentBuyersCount"
      label={$_('settings.modules.recent-buyers-count')}
      error={message('moduleRecentBuyersCount')}>
      <input
        id="setting-moduleRecentBuyersCount"
        type="number"
        min="1"
        max="50"
        step="1"
        class="form-control"
        class:is-invalid={shown.moduleRecentBuyersCount}
        disabled={!draft.moduleRecentBuyers}
        bind:value={draft.moduleRecentBuyersCount} />
    </SettingRow>
    <SwitchRow
      id="setting-moduleRecentBuyersShowAmount"
      label={$_('settings.modules.recent-buyers-show-amount')}
      error={message('moduleRecentBuyersShowAmount')}
      disabled={!draft.moduleRecentBuyers}
      bind:checked={draft.moduleRecentBuyersShowAmount} />

    <SwitchRow
      id="setting-moduleTopSupporters"
      label={$_('settings.modules.top-supporters')}
      hint={$_('settings.modules.top-supporters-hint')}
      error={message('moduleTopSupporters')}
      bind:checked={draft.moduleTopSupporters} />
    <SettingRow
      id="setting-moduleTopSupportersPeriod"
      label={$_('settings.modules.top-supporters-period')}
      error={message('moduleTopSupportersPeriod')}>
      <select
        id="setting-moduleTopSupportersPeriod"
        class="form-select"
        class:is-invalid={shown.moduleTopSupportersPeriod}
        disabled={!draft.moduleTopSupporters}
        bind:value={draft.moduleTopSupportersPeriod}>
        {#each TOP_SUPPORTER_PERIODS as period (period)}
          <option value={period}>{$_(`settings.modules.period.${period}`)}</option>
        {/each}
      </select>
    </SettingRow>
    <SettingRow
      id="setting-moduleTopSupportersCount"
      label={$_('settings.modules.top-supporters-count')}
      error={message('moduleTopSupportersCount')}>
      <input
        id="setting-moduleTopSupportersCount"
        type="number"
        min="1"
        max="50"
        step="1"
        class="form-control"
        class:is-invalid={shown.moduleTopSupportersCount}
        disabled={!draft.moduleTopSupporters}
        bind:value={draft.moduleTopSupportersCount} />
    </SettingRow>

    <SwitchRow
      id="setting-moduleGoal"
      label={$_('settings.modules.goal')}
      hint={$_('settings.modules.goal-hint')}
      error={message('moduleGoal')}
      bind:checked={draft.moduleGoal} />
    {#if canGoals}
      <div class="row mb-3">
        <div class="col-md-6 offset-md-6">
          <a href="{base}/market/goals">{$_('settings.modules.open-goals')}</a>
        </div>
      </div>
    {/if}

    <SwitchRow
      id="setting-moduleSaleBadges"
      label={$_('settings.modules.sale-badges')}
      hint={$_('settings.modules.sale-badges-hint')}
      error={message('moduleSaleBadges')}
      bind:checked={draft.moduleSaleBadges} />
    <SwitchRow
      id="setting-moduleSaleCountdown"
      label={$_('settings.modules.sale-countdown')}
      hint={$_('settings.modules.sale-countdown-hint')}
      error={message('moduleSaleCountdown')}
      bind:checked={draft.moduleSaleCountdown} />
    <SwitchRow
      id="setting-moduleStats"
      label={$_('settings.modules.stats')}
      hint={$_('settings.modules.stats-hint')}
      error={message('moduleStats')}
      bind:checked={draft.moduleStats} />

    <SettingRow
      id="setting-moduleSidebars"
      label={$_('settings.modules.sidebars')}
      hint={$_('settings.modules.sidebars-hint')}
      error={message('moduleSidebars')}>
      <div class="vstack gap-1" role="group" aria-label={$_('settings.modules.sidebars')}>
        {#each SIDEBARS as sidebar (sidebar)}
          <div class="form-check m-0">
            <input
              id="setting-moduleSidebars-{sidebar}"
              class="form-check-input"
              type="checkbox"
              checked={draft.moduleSidebars.includes(sidebar)}
              onchange={(event) => setSidebar(sidebar, event.currentTarget.checked)} />
            <label class="form-check-label" for="setting-moduleSidebars-{sidebar}">
              {$_(`settings.modules.sidebar.${sidebar}`)}
            </label>
          </div>
        {/each}
      </div>
    </SettingRow>
  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary" onclick={onSave} disabled={saving || !isDirty}>
      {#if saving}
        <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
      {/if}
      {$_('common.save')}
    </button>
  </div>
</div>

<script>
  import { untrack } from 'svelte';
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import { can } from '../../utils/permissions.js';
  import {
    SECTION_KEYS,
    SIDEBARS,
    TOP_SUPPORTER_PERIODS,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
  } from '../../utils/settings.js';
  import {
    activeModuleKeys,
    toggleListValue,
    validateModules,
  } from '../../utils/settings-extra.js';

  let { settings: initial = {} } = $props();

  const KEYS = SECTION_KEYS.modules;
  const start = untrack(() => initial ?? {});

  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  const user = $derived($page.data?.user);
  const canGoals = $derived(can(user, 'CAT'));
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateModules(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const keys = $derived(activeModuleKeys(draft));
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, keys)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  function setSidebar(sidebar, on) {
    draft.moduleSidebars = toggleListValue(draft.moduleSidebars, sidebar, on, SIDEBARS);
  }

  async function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    if (Object.keys(clientErrors).length > 0) {
      reportFailure({ status: 'invalid', errors: clientErrors }, KEYS);
      return;
    }
    saving = true;
    try {
      const result = await saveSection({
        baseline: settings,
        values: draft,
        keys,
        errors: clientErrors,
        order: KEYS,
      });
      if (result.status === 'saved') {
        const next = (await fetchSettings()) ?? { ...settings, ...result.sent };
        settings = next;
        draft = seedValues(next, KEYS);
        submitted = false;
        serverMark = null;
      } else if (result.status === 'failed' && Object.keys(result.errors).length > 0) {
        serverMark = { errors: result.errors, draftKey };
      }
    } finally {
      saving = false;
    }
  }
</script>
