<article class="card" aria-labelledby="market-sub-{sub.id}-title">
  <div class="card-body vstack gap-3">
    <div class="d-flex flex-wrap align-items-start justify-content-between gap-2">
      <div>
        <h2 class="h5 mb-1" id="market-sub-{sub.id}-title">{sub.productName}</h2>
        <div class="text-body-secondary">
          {formatMoney(sub.price, sub.currency)}
          {$_('theme.store.per-period', { period })}
        </div>
      </div>
      <span class={['badge', sub.badge.className]}>
        {sub.badge.key ? $_(sub.badge.key) : sub.badge.raw}
      </span>
    </div>

    {#if sub.pastDue}
      <div class="alert alert-danger py-2 mb-0" role="alert">
        {$_('theme.profile.subscriptions.past-due')}
      </div>
    {/if}

    {#if sub.periodEnd}
      {#if sub.cancelAtPeriodEnd}
        <div class="alert alert-warning py-2 mb-0">
          <i class="fa-solid fa-hourglass-end me-1" aria-hidden="true"></i>{$_(
            'theme.profile.subscriptions.ends-on',
            { values: { date: endDate } },
          )}
        </div>
      {:else}
        <div class="small text-body-secondary">
          {$_('theme.profile.subscriptions.renews-on', { values: { date: endDate } })}
        </div>
      {/if}
    {/if}

    {#if sub.methodLabel || sub.storedMethodLabel}
      <div class="small text-body-secondary">
        <i class="fa-regular fa-credit-card me-1" aria-hidden="true"></i>
        {$_('theme.profile.subscriptions.method')}:
        {[sub.methodLabel, sub.storedMethodLabel].filter(Boolean).join(' · ')}
      </div>
    {/if}

    {#if sub.canCancel || sub.canKeep || sub.canManage || sub.renewalHref}
      <div class="d-flex flex-wrap gap-2">
        {#if sub.renewalHref}
          <a class="btn btn-primary btn-sm" href="{base}{sub.renewalHref}">
            <i class="fa-solid fa-receipt me-1" aria-hidden="true"></i>{$_(
              'theme.profile.subscriptions.renewal-order',
            )}
          </a>
        {/if}

        {#if sub.canUpdateMethod}
          <button
            type="button"
            class="btn btn-outline-primary btn-sm"
            disabled={busy}
            onclick={() => portal('UPDATE_PAYMENT_METHOD')}>
            {$_('theme.profile.subscriptions.update-method')}
          </button>
        {/if}

        {#if sub.canManage}
          <button
            type="button"
            class="btn btn-outline-secondary btn-sm"
            disabled={busy}
            onclick={() => portal('MANAGE')}>
            <i class="fa-solid fa-arrow-up-right-from-square me-1" aria-hidden="true"></i>{$_(
              'theme.profile.subscriptions.manage',
            )}
          </button>
        {/if}

        {#if sub.canKeep}
          <button
            type="button"
            class="btn btn-outline-primary btn-sm"
            disabled={busy}
            onclick={resume}>
            {$_('theme.profile.subscriptions.keep')}
          </button>
        {/if}

        {#if sub.canCancel}
          <button
            type="button"
            class="btn btn-outline-danger btn-sm"
            disabled={busy}
            onclick={() => confirmCancel?.show()}>
            {$_('theme.profile.subscriptions.cancel')}
          </button>
        {/if}
      </div>
    {/if}

    {#if errorKey}
      <ErrorAlert message={$_(errorKey)} />
    {/if}
  </div>
</article>

{#if sub.canCancel}
  <ConfirmModal
    bind:this={confirmCancel}
    id="marketCancelSubscriptionModal-{sub.id}"
    title={$_('theme.profile.subscriptions.cancel-title')}
    message={$_('theme.profile.subscriptions.cancel-confirm', { values: { date: endDate } })}
    confirmLabel={$_('theme.profile.subscriptions.cancel-yes')}
    cancelLabel={$_('theme.profile.subscriptions.cancel-keep')}
    onconfirm={cancel} />
{/if}

<script>
  import { base } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { _ } from '../../../i18n.js';
  import { CANCEL_BODY, actionOutcome, actionPath } from '../../lib/subscriptionModel.js';
  import { call } from '../../utils/api.js';
  import { formatDate, formatMoney, formatPeriod } from '../../utils/format.js';
  import ConfirmModal from '../common/ConfirmModal.svelte';
  import ErrorAlert from '../common/ErrorAlert.svelte';

  /**
   * One row of me/subscriptions (14 §12.4). `subscription` = a subscriptionView() row; `onreplace(row)` gets the
   * raw subscription of the answer, `onreload()` asks the page to reload the list after a 409.
   */
  let { subscription, onreplace = () => {}, onreload = () => {} } = $props();

  const sub = $derived(subscription);
  const period = $derived(formatPeriod(sub.intervalUnit, sub.intervalCount, $_));
  const endDate = $derived(sub.periodEnd ? formatDate(sub.periodEnd) : '');

  let confirmCancel = $state();
  let busy = $state(false);
  let errorKey = $state('');

  const notify = (key) => showToast(`plugins.pano-plugin-market.${key}`);

  /** Applies an action answer. */
  function apply(res) {
    const outcome = actionOutcome(res);

    if (outcome.kind === 'REPLACE') {
      onreplace(outcome.subscription);
    } else if (outcome.kind === 'REDIRECT') {
      window.location.assign(outcome.url);
    } else if (outcome.kind === 'RELOAD') {
      notify(outcome.key);
      onreload();
    } else if (outcome.key === 'theme.errors.PAYMENT_PROVIDER_ERROR') {
      notify(outcome.key);
    } else {
      errorKey = outcome.key;
    }
  }

  async function run(action, options) {
    if (busy) return;
    busy = true;
    errorKey = '';

    const res = await call('POST', actionPath(sub.id, action), options);

    busy = false;
    apply(res);
  }

  // cancellation is at period end only: the buyer cannot ask for an immediate one
  const cancel = () => run('cancel', { body: { ...CANCEL_BODY } });
  const resume = () => run('resume');
  const portal = (purpose) => run('portal', { body: { purpose } });
</script>
