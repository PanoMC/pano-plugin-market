<div class="vstack gap-2">
  <div class={['alert', 'mb-0', VARIANT_CLASS[view.variant] ?? 'alert-secondary']} role="status">
    <div class="d-flex align-items-start gap-3">
      {#if view.spinner}
        <span class="spinner-border spinner-border-sm mt-2 flex-shrink-0" aria-hidden="true"></span>
      {:else}
        <i class={[view.icon, 'fa-lg', 'mt-2', 'flex-shrink-0']} aria-hidden="true"></i>
      {/if}
      <div class="flex-grow-1">
        <h2 class="h5 mb-1" tabindex="-1" bind:this={heading}>{$_(view.titleKey)}</h2>

        {#if view.subKey}
          <p class="mb-1">{$_(view.subKey)}</p>
        {/if}
        {#if view.lineKey}
          <p class="mb-1">{$_(view.lineKey)}</p>
        {/if}
        {#if view.showCountdown && left !== null}
          <p class="mb-1" aria-live="off">
            <i class="fa-regular fa-clock me-1" aria-hidden="true"></i>{$_(
              'theme.order.expires-in',
              {
                values: { time: formatCountdown(left) },
              },
            )}
          </p>
        {/if}

        <div class="small d-flex flex-wrap gap-3">
          {#if order.number !== null && order.number !== undefined}
            <span>{$_('theme.order.number', { values: { number: order.number } })}</span>
          {/if}
          {#if order.createdAt}
            <span>{formatDateTime(order.createdAt)}</span>
          {/if}
          {#if extras.testMode}
            <span class="badge text-bg-warning align-self-center">{$_('theme.order.test')}</span>
          {/if}
          {#if extras.isGift && extras.recipientUsername}
            <span>
              <i class="fa-solid fa-gift me-1" aria-hidden="true"></i>{$_('theme.order.gift-for', {
                values: { username: extras.recipientUsername },
              })}
            </span>
          {/if}
        </div>

        {#if view.backToStore}
          <a class="btn btn-sm btn-outline-secondary mt-2" href="{base}/store">
            {$_('theme.order.back-to-store')}
          </a>
        {/if}
      </div>
    </div>
  </div>

  {#if extras.refundPending}
    <div class="alert alert-info mb-0">{$_('theme.order.refund-pending')}</div>
  {/if}

  {#if extras.buyerActionUrl}
    <div>
      <a
        class="btn btn-outline-primary"
        href={extras.buyerActionUrl}
        target="_blank"
        rel="noopener noreferrer">
        {$_('theme.order.buyer-action')}
      </a>
    </div>
  {/if}

  {#if view.limited}
    <div class="alert alert-secondary mb-0">
      {$_('theme.order.limited')}
      {#if loginHref}
        <a class="alert-link ms-1" href={loginHref}>{$_('theme.order.sign-in')}</a>
      {/if}
    </div>
  {/if}
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import { format as formatCountdown } from '../../lib/countdown.js';
  import { formatDateTime } from '../../utils/format.js';

  const VARIANT_CLASS = {
    success: 'alert-success',
    warning: 'alert-warning',
    info: 'alert-info',
    danger: 'alert-danger',
    secondary: 'alert-secondary',
  };

  /**
   * The status block of 14 §11.3. `view` = viewState() of lib/orderState.js, `extras` = orderExtras(), `left` =
   * ms until the order expires (null = none), `loginHref` = sign-in link shown for a limited view. The heading
   * takes the focus when the view state changes after mount.
   */
  let { view, order, extras, left = null, loginHref = '' } = $props();

  let heading = $state();
  let previous;

  $effect(() => {
    const state = view.state;

    if (previous !== undefined && previous !== state) heading?.focus();

    previous = state;
  });
</script>
