<MailTestModal bind:this={testModal} {defaultRecipient} />

{#if alertKind === 'HOST_TOO_OLD'}
  <div class="alert alert-danger d-flex align-items-start mb-3" role="alert">
    <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('settings.mail.host-too-old.title')}</b>
      <div>{$_('alerts.mail-host-too-old.body')}</div>
    </div>
  </div>
{:else if alertKind === 'DISABLED'}
  <div class="alert alert-warning d-flex align-items-start mb-3" role="alert">
    <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('alerts.mail-disabled.title')}</b>
      <div>{$_('alerts.mail-disabled.body')}</div>
      <a class="alert-link" href="{base}/settings/platform">
        {$_('settings.mail.open-platform-settings')}
      </a>
    </div>
  </div>
{/if}

<div class="card">
  <CardHeader>
    <div slot="left">{$_('settings.mail.title')}</div>
    <div slot="right">
      <button type="button" class="btn btn-sm btn-link" onclick={() => testModal?.open()}>
        <i class="fa-solid fa-paper-plane me-2" aria-hidden="true"></i>
        {$_('settings.mail.send-test')}
      </button>
    </div>
  </CardHeader>

  <div class="card-body">
    <SwitchRow
      id="setting-sendEmailAfterPurchase"
      label={$_('settings.mail.order-mails')}
      hint={$_('settings.mail.order-mails-hint')}
      error={message('sendEmailAfterPurchase')}
      bind:checked={draft.sendEmailAfterPurchase} />

    <SettingRow
      id="setting-mailDisabledKinds"
      label={$_('settings.mail.kinds')}
      hint={$_('settings.mail.kinds-hint')}
      error={message('mailDisabledKinds')}>
      <div class="vstack gap-1" role="group" aria-label={$_('settings.mail.kinds')}>
        {#each MAIL_KINDS as kind (kind)}
          <div class="form-check m-0">
            <input
              id="setting-mailDisabledKinds-{kind}"
              class="form-check-input"
              type="checkbox"
              checked={mailKindEnabled(draft.mailDisabledKinds, kind)}
              onchange={(event) => setKind(kind, event.currentTarget.checked)} />
            <label class="form-check-label" for="setting-mailDisabledKinds-{kind}">
              {$_(`enums.mail-kind.${kind}`)}
              {#if isServiceMailKind(kind)}
                <span class="badge text-bg-secondary ms-1"
                  >{$_('settings.mail.service-badge')}</span>
              {/if}
            </label>
          </div>
        {/each}
      </div>
    </SettingRow>

    <SwitchRow
      id="setting-mailAttachInvoice"
      label={$_('settings.mail.attach-invoice')}
      hint={$_('settings.mail.attach-invoice-hint')}
      error={message('mailAttachInvoice')}
      bind:checked={draft.mailAttachInvoice} />

    <SettingRow
      id="setting-mailReplyTo"
      label={$_('settings.mail.reply-to')}
      hint={$_('settings.mail.reply-to-hint')}
      error={message('mailReplyTo')}>
      <input
        id="setting-mailReplyTo"
        type="email"
        class="form-control"
        class:is-invalid={shown.mailReplyTo}
        maxlength="254"
        autocomplete="off"
        placeholder={$_('settings.mail.reply-to-placeholder')}
        bind:value={draft.mailReplyTo} />
    </SettingRow>

    <SettingRow
      id="setting-mailOrderDeliveredDelayMinutes"
      label={$_('settings.mail.delivered-delay')}
      hint={$_('settings.mail.delivered-delay-hint')}
      error={message('mailOrderDeliveredDelayMinutes')}>
      <div class="input-group">
        <input
          id="setting-mailOrderDeliveredDelayMinutes"
          type="number"
          min="0"
          max="1440"
          step="1"
          class="form-control"
          class:is-invalid={shown.mailOrderDeliveredDelayMinutes}
          bind:value={draft.mailOrderDeliveredDelayMinutes} />
        <span class="input-group-text">{$_('settings.mail.minutes')}</span>
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
  import { CardHeader } from '@panomc/sdk/components/panel';
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import MailTestModal from '../modals/MailTestModal.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import {
    MAIL_KINDS,
    SECTION_KEYS,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
  } from '../../utils/settings.js';
  import {
    isServiceMailKind,
    mailAlert,
    mailKindEnabled,
    setMailKindEnabled,
    validateMail,
  } from '../../utils/settings-extra.js';

  // settings = GET /settings (mailEnabled), extra = GET /health ({ mail, mailEnabled, ... }) or null.
  let { settings: initial = {}, extra = null } = $props();

  const KEYS = SECTION_KEYS.mail;
  const start = untrack(() => initial ?? {});

  let testModal = $state(null);
  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  const user = $derived($page.data?.user);
  const defaultRecipient = $derived(typeof user?.email === 'string' ? user.email : '');
  const alertKind = $derived(mailAlert(settings, extra));
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateMail(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, KEYS)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  function setKind(kind, enabled) {
    draft.mailDisabledKinds = setMailKindEnabled(draft.mailDisabledKinds, kind, enabled);
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
        keys: KEYS,
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
