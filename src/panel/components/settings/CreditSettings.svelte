<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { showToast } from '@panomc/sdk/toasts';
  import { _ } from '../../../i18n';

  let { settings: initialSettings = {} } = $props();

  // Local writable copy of the loaded settings: re-derived if the page load()
  // re-runs, reassigned by the client-side refresh() after a save (navigating
  // would remount the whole plugin page and drop the active settings tab).
  let settings = $derived(initialSettings);

  // Writable deriveds: seeded from the loaded settings and editable via bind:value;
  // they re-sync to server truth whenever the settings state changes
  // (e.g. after the refresh() following a save).
  let creditsEnabled = $derived(settings.creditsEnabled ?? true);
  let creditName = $derived(settings.creditName ?? 'Kredi');
  let cashbackPercent = $derived(settings.cashbackPercent ?? 0);
  let onlyAcceptCredits = $derived(settings.onlyAcceptCredits ?? false);

  let saving = $state(false);

  // Save is enabled only when an editable field diverges from the loaded settings;
  // re-syncs to false after a save + refresh() (the deriveds re-seed from settings).
  let isDirty = $derived(
    creditsEnabled !== (settings.creditsEnabled ?? true) ||
      creditName !== (settings.creditName ?? 'Kredi') ||
      Number(cashbackPercent) !== (settings.cashbackPercent ?? 0) ||
      onlyAcceptCredits !== (settings.onlyAcceptCredits ?? false)
  );

  async function refresh() {
    const body = await ApiUtil.get({ path: '/api/panel/market/settings' });
    if (body && !body.error) {
      settings = body;
    }
  }

  async function handleSave() {
    saving = true;
    try {
      const body = await ApiUtil.post({
        path: '/api/panel/market/settings/credits',
        body: {
          creditsEnabled,
          creditName,
          cashbackPercent: Number(cashbackPercent) || 0,
          onlyAcceptCredits
        }
      });

      if (body.error) {
        showToast($_('settings.credits.toast-error'));
        return;
      }

      showToast($_('settings.credits.toast-success'));
      await refresh();
    } catch (e) {
      showToast($_('settings.credits.toast-error'));
    } finally {
      saving = false;
    }
  }
</script>

<div class="card">
  <div class="card-header">
    <h6 class="mb-0">{$_('settings.credits.heading')}</h6>
    <small class="text-body-secondary">{$_('settings.credits.subtitle')}</small>
  </div>

  <div class="card-body">
    <div class="row mb-3">
      <div class="col-md-6">
        <label for="creditsEnabledInput" class="d-block mb-1">{$_('settings.credits.credits-label')}</label>
        <small class="text-body-secondary d-block">{$_('settings.credits.credits-desc')}</small>
      </div>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="creditsEnabledInput" bind:checked={creditsEnabled} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-md-6">
        <label for="onlyAcceptCreditsInput" class="d-block mb-1">{$_('settings.credits.only-credits-label')}</label>
        <small class="text-body-secondary d-block">{$_('settings.credits.only-credits-desc')}</small>
      </div>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="onlyAcceptCreditsInput" bind:checked={onlyAcceptCredits} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-md-6">
        <label class="col-form-label pb-0" for="creditNameInput">{$_('settings.credits.credit-name-label')}</label>
        <small class="text-body-secondary d-block">{$_('settings.credits.credit-name-desc')}</small>
      </div>
      <div class="col-md-6">
        <input type="text" class="form-control" id="creditNameInput" placeholder={$_('settings.credits.credit-name-label')} bind:value={creditName} />
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-md-6">
        <label class="col-form-label pb-0" for="cashbackInput">{$_('settings.credits.cashback-label')}</label>
        <small class="text-body-secondary d-block">{$_('settings.credits.cashback-desc')}</small>
      </div>
      <div class="col-md-6">
        <input type="number" min="0" max="100" step="0.5" class="form-control" id="cashbackInput" placeholder={$_('settings.credits.cashback-placeholder')} bind:value={cashbackPercent} />
      </div>
    </div>
  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary" onclick={handleSave} disabled={saving || !isDirty}>
      {#if saving}
        <span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
      {/if}
      {$_('common.save')}
    </button>
  </div>
</div>
