<div class="vstack gap-3">
  {#if extraError}
    <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
      <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.delivery.servers-error.title')}</b>
        <div>{$_('settings.delivery.servers-error.body')}</div>
      </div>
    </div>
  {/if}

  <div class="card">
    <div class="card-body">
      <SwitchRow
        id="setting-revokeOnRefund"
        label={$_('settings.delivery.revoke-on-refund')}
        hint={$_('settings.delivery.revoke-on-refund-hint')}
        error={message('revokeOnRefund')}
        bind:checked={draft.revokeOnRefund} />
      <SwitchRow
        id="setting-revokeOnChargeback"
        label={$_('settings.delivery.revoke-on-chargeback')}
        hint={$_('settings.delivery.revoke-on-chargeback-hint')}
        error={message('revokeOnChargeback')}
        bind:checked={draft.revokeOnChargeback} />
      <SwitchRow
        id="setting-autoBlockOnChargeback"
        label={$_('settings.delivery.auto-block-on-chargeback')}
        hint={$_('settings.delivery.auto-block-on-chargeback-hint')}
        error={message('autoBlockOnChargeback')}
        bind:checked={draft.autoBlockOnChargeback} />
      <SwitchRow
        id="setting-revokeCreditOrdersOnTopUpChargeback"
        label={$_('settings.delivery.revoke-credit-orders')}
        hint={$_('settings.delivery.revoke-credit-orders-hint')}
        error={message('revokeCreditOrdersOnTopUpChargeback')}
        bind:checked={draft.revokeCreditOrdersOnTopUpChargeback} />

      <SettingRow
        id="setting-deliveryMaxAttempts"
        label={$_('settings.delivery.max-attempts')}
        hint={$_('settings.delivery.max-attempts-hint')}
        error={message('deliveryMaxAttempts')}>
        <input
          id="setting-deliveryMaxAttempts"
          type="number"
          min="1"
          max="20"
          step="1"
          class="form-control"
          class:is-invalid={shown.deliveryMaxAttempts}
          bind:value={draft.deliveryMaxAttempts} />
      </SettingRow>

      <SettingRow
        id="setting-deliveryOnlineWaitDays"
        label={$_('settings.delivery.online-wait-days')}
        hint={$_('settings.delivery.online-wait-days-hint')}
        error={message('deliveryOnlineWaitDays')}>
        <div class="input-group">
          <input
            id="setting-deliveryOnlineWaitDays"
            type="number"
            min="0"
            max="365"
            step="1"
            class="form-control"
            class:is-invalid={shown.deliveryOnlineWaitDays}
            bind:value={draft.deliveryOnlineWaitDays} />
          <span class="input-group-text">{$_('settings.delivery.days')}</span>
        </div>
      </SettingRow>

      <SettingRow
        id="setting-deliveryAckTimeoutSeconds"
        label={$_('settings.delivery.ack-timeout')}
        hint={$_('settings.delivery.ack-timeout-hint')}
        error={message('deliveryAckTimeoutSeconds')}>
        <div class="input-group">
          <input
            id="setting-deliveryAckTimeoutSeconds"
            type="number"
            min="5"
            max="600"
            step="1"
            class="form-control"
            class:is-invalid={shown.deliveryAckTimeoutSeconds}
            bind:value={draft.deliveryAckTimeoutSeconds} />
          <span class="input-group-text">{$_('settings.delivery.seconds')}</span>
        </div>
      </SettingRow>

      <SettingRow
        id="setting-subscriptionGraceDays"
        label={$_('settings.delivery.subscription-grace-days')}
        hint={$_('settings.delivery.subscription-grace-days-hint')}
        error={message('subscriptionGraceDays')}>
        <div class="input-group">
          <input
            id="setting-subscriptionGraceDays"
            type="number"
            min="0"
            max="60"
            step="1"
            class="form-control"
            class:is-invalid={shown.subscriptionGraceDays}
            bind:value={draft.subscriptionGraceDays} />
          <span class="input-group-text">{$_('settings.delivery.days')}</span>
        </div>
      </SettingRow>

      <SettingRow
        id="setting-subscriptionReminderDays"
        label={$_('settings.delivery.subscription-reminder-days')}
        hint={$_('settings.delivery.subscription-reminder-days-hint')}
        error={message('subscriptionReminderDays')}>
        <div class="input-group">
          <input
            id="setting-subscriptionReminderDays"
            type="number"
            min="0"
            max="60"
            step="1"
            class="form-control"
            class:is-invalid={shown.subscriptionReminderDays}
            bind:value={draft.subscriptionReminderDays} />
          <span class="input-group-text">{$_('settings.delivery.days')}</span>
        </div>
      </SettingRow>

      <SettingRow
        id="setting-creatorEarningHoldDays"
        label={$_('settings.delivery.creator-hold-days')}
        hint={$_('settings.delivery.creator-hold-days-hint')}
        error={message('creatorEarningHoldDays')}>
        <div class="input-group">
          <input
            id="setting-creatorEarningHoldDays"
            type="number"
            min="0"
            max="90"
            step="1"
            class="form-control"
            class:is-invalid={shown.creatorEarningHoldDays}
            bind:value={draft.creatorEarningHoldDays} />
          <span class="input-group-text">{$_('settings.delivery.days')}</span>
        </div>
      </SettingRow>
    </div>
  </div>

  {#if storedInvalid}
    <div class="alert alert-warning d-flex align-items-start mb-0" role="alert">
      <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
      <div>
        <b>{$_('settings.delivery.chargeback-invalid.title')}</b>
        <div>{$_('settings.delivery.chargeback-invalid.body')}</div>
      </div>
    </div>
  {/if}

  <div class="card" id="setting-chargebackActions" tabindex="-1">
    <CardHeader>
      <div slot="left">
        {$_('settings.delivery.chargeback-actions', { values: { count: actions.length } })}
      </div>
      <div slot="right">
        <div class="dropdown">
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
            <button
              type="button"
              class="dropdown-item"
              disabled={full || !canAdd('COMMAND')}
              onclick={addBan}>
              <i class="fa-solid fa-user-slash me-2" aria-hidden="true"></i>
              {$_('settings.delivery.add-ban')}
            </button>
            {#each addable as type (type)}
              <button
                type="button"
                class="dropdown-item"
                disabled={full}
                onclick={() => addAction(type)}>
                <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
                {$_(`enums.action-type.${type}`)}
              </button>
            {/each}
          </div>
        </div>
      </div>
    </CardHeader>

    {#if shownActions.actions || shown.chargebackActions}
      <div class="card-body pb-0">
        <div class="invalid-feedback d-block">
          {shownActions.actions
            ? $_(actionErrorKey(shownActions.actions))
            : $_(fieldErrorKey(shown.chargebackActions))}
        </div>
      </div>
    {/if}

    {#if actions.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="card-body vstack gap-3">
        {#each actions as action, index (action)}
          <ActionEditor
            bind:action={actions[index]}
            servers={serverList ?? []}
            phases={['GRANT']}
            types={CHARGEBACK_ACTION_TYPES}
            variables={CHARGEBACK_VARIABLES}
            showPhase={false}
            errors={rowErrors(shownActions, index)}
            path="actions.{index}"
            disabled={actionLocked(action, user)}
            onRemove={() => removeAction(index)} />
        {/each}
      </div>
    {/if}

    <div class="card-footer d-flex justify-content-start">
      <button
        type="button"
        class="btn btn-secondary"
        onclick={onSave}
        disabled={saving || !isDirty}>
        {#if saving}
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {/if}
        {$_('common.save')}
      </button>
    </div>
  </div>
</div>

<script>
  import { untrack } from 'svelte';
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import ActionEditor from '../ActionEditor.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import {
    actionErrorKey,
    actionLocked,
    addableActionTypes,
    newAction,
  } from '../../utils/actions.js';
  import {
    SECTION_KEYS,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
  } from '../../utils/settings.js';
  import {
    CHARGEBACK_ACTION_TYPES,
    CHARGEBACK_VARIABLES,
    DELIVERY_PLAIN_KEYS,
    MAX_CHARGEBACK_ACTIONS,
    banPreset,
    parseChargebackActions,
    rowErrors,
    validateDelivery,
    withChargeback,
  } from '../../utils/settings-extra.js';

  // settings = GET /settings; extra = GET /servers ({ items[] }) for the action editors, null when
  // that request failed (the editors then accept any server id the form cannot check).
  let { settings: initial = {}, extra = null, extraError = null } = $props();

  const KEYS = SECTION_KEYS.delivery;
  const start = untrack(() => initial ?? {});
  const parsed = parseChargebackActions(start.chargebackActions);

  let settings = $state.raw(start);
  let draft = $state(seedValues(start, DELIVERY_PLAIN_KEYS));
  let actions = $state(parsed.actions);
  let storedInvalid = $state(parsed.invalid);
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  const user = $derived($page.data?.user);
  const serverList = $derived(Array.isArray(extra?.items) ? extra.items : null);
  const addable = $derived(
    addableActionTypes(user).filter((t) => CHARGEBACK_ACTION_TYPES.includes(t)),
  );
  const full = $derived(actions.length >= MAX_CHARGEBACK_ACTIONS);
  const canAdd = (type) => addable.includes(type);

  const merged = $derived(withChargeback(settings, draft, $state.snapshot(actions)));
  const draftKey = $derived(JSON.stringify($state.snapshot({ draft, actions })));
  const validation = $derived(validateDelivery(draft, $state.snapshot(actions), serverList));
  const clientErrors = $derived(validation.errors);
  const clientActionErrors = $derived(validation.actions);
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const shownActions = $derived(submitted ? clientActionErrors : {});
  const isDirty = $derived(isDirtyBody(buildSettingsBody(merged.baseline, merged.values, KEYS)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  function addAction(type) {
    const action = newAction(type, { phase: 'GRANT' });
    if (type === 'COMMAND' && serverList?.length === 1) action.targetServers = [serverList[0].id];
    actions.push(action);
  }

  function addBan() {
    actions.push(banPreset());
  }

  function removeAction(index) {
    actions = actions.filter((_row, i) => i !== index);
  }

  async function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    const all = { ...clientErrors };
    if (Object.keys(clientActionErrors).length > 0) all.chargebackActions ??= 'INVALID_VALUE';
    if (Object.keys(all).length > 0) {
      reportFailure({ status: 'invalid', errors: all }, KEYS);
      return;
    }
    saving = true;
    try {
      const result = await saveSection({
        baseline: merged.baseline,
        values: merged.values,
        keys: KEYS,
        errors: {},
        order: KEYS,
      });
      if (result.status === 'saved') {
        const next = (await fetchSettings()) ?? { ...settings, ...result.sent };
        const reread = parseChargebackActions(next.chargebackActions);
        settings = next;
        draft = seedValues(next, DELIVERY_PLAIN_KEYS);
        actions = reread.actions;
        storedInvalid = reread.invalid;
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
