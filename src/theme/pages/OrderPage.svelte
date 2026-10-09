{#if data.state === 'ERROR'}
  <StoreStateCard
    icon="fa-solid fa-triangle-exclamation fa-3x"
    text={$_(messageKey(data.code))}
    onretry={() => location.reload()} />
{:else if gone}
  <StoreStateCard icon="fa-solid fa-receipt fa-3x" text={$_('theme.order.not-found')} />
{:else}
  <div class="market-order-page vstack gap-4" aria-busy={loading ? 'true' : 'false'}>
    <OrderStatusBlock {view} {order} {extras} {left} {loginHref} />

    {#if offline}
      <div class="small text-body-secondary" role="status">{$_('theme.order.poll-offline')}</div>
    {/if}

    {#if pollStatus === 'STOPPED'}
      <div>
        <button
          type="button"
          class="market-order-page__action btn btn-outline-secondary btn-sm"
          onclick={refreshStatus}>
          <i class="fa-solid fa-rotate-right me-1" aria-hidden="true"></i>{$_(
            'theme.order.refresh-status',
          )}
        </button>
      </div>
    {/if}

    {#if refreshFailed}
      <ErrorAlert message={$_(messageKey(refreshFailed))} onretry={reload} />
    {/if}

    {#if !view.limited && (view.panels.payment || view.panels.instructions)}
      <PaymentPanel {id} {order} {view} {token} {removeCents} onrefetch={reload} />
    {/if}

    {#if view.panels.items && order.items?.length}
      <section class="vstack gap-2" aria-labelledby="market-order-items-title">
        <h2 class="market-order-page__title h5 mb-0" id="market-order-items-title">
          {$_('theme.order.items')}
        </h2>
        <OrderItems items={order.items} currency={order.currency} {removeCents} />
      </section>
    {/if}

    {#if view.panels.totals}
      <section class="vstack gap-2" aria-labelledby="market-order-totals-title">
        <h2 class="market-order-page__title-2 h5 mb-0" id="market-order-totals-title">
          {$_('theme.order.totals.title')}
        </h2>
        <OrderTotals {order} {pricesIncludeVat} {removeCents} />
      </section>
    {/if}

    {#if !view.limited && (view.panels.shipments || order.shippingAddress || order.billingInfo || order.email)}
      <section class="vstack gap-2" aria-labelledby="market-order-shipping-title">
        {#if view.panels.shipments && order.shipments?.length}
          <h2 class="market-order-page__shipments h5 mb-0" id="market-order-shipping-title">
            {$_('theme.order.shipments')}
          </h2>
        {/if}
        <ShipmentList
          shipments={order.shipments ?? []}
          shippingAddress={order.shippingAddress}
          billingInfo={order.billingInfo}
          email={order.email}
          owner={!view.limited}
          showShipments={view.panels.shipments} />
      </section>
    {/if}

    <OrderActions {id} {order} {view} {token} onrefetch={reload} />
  </div>
{/if}

<script module>
  // page metadata (doc 01 section 2): the build registers this view as a page, no register.js entry. The data of the page
  // comes from the `market/order` controller (`controller` below, doc 02 section 4); this function only turns its
  // `notFound` into a 404 and hands the settings it fetched to `market/settings`.
  export const view = { path: '/store/order/[id]', controller: 'order' };

  import { plugin } from '@panomc/sdk/controllers';
  import { error } from '@panomc/sdk/svelte';

  export async function load(event) {
    const market = plugin('market');
    const result = await market.load('order', {
      // a server load is made for its request; the browser has one host for the whole page
      event: typeof window === 'undefined' ? event : undefined,
      params: { ...event.params, url: event.url },
    });

    if (!result) throw error(503, 'market/order is not available');

    if (result.notFound) throw error(404);

    const settings = result.data.settings;

    if (
      result.data.state === 'READY' &&
      settings &&
      Object.keys(settings).length > 0 &&
      typeof window !== 'undefined'
    )
      market.use('settings')?.actions.set(settings);

    return result;
  }
</script>

<script>
  import { onMount, untrack } from 'svelte';
  import { get } from 'svelte/store';
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import ErrorAlert from '../components/common/ErrorAlert.svelte';
  import OrderActions from '../components/order/OrderActions.svelte';
  import OrderItems from '../components/order/OrderItems.svelte';
  import OrderStatusBlock from '../components/order/OrderStatusBlock.svelte';
  import OrderTotals from '../components/order/OrderTotals.svelte';
  import PaymentPanel from '../components/order/PaymentPanel.svelte';
  import ShipmentList from '../components/order/ShipmentList.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import { messageKey } from '../lib/errorMap.js';
  import {
    expiryLeft,
    hasOrderParams,
    orderExtras,
    stripOrderParams,
    tokenAfterRefetch,
    viewState,
  } from '../lib/orderState.js';
  import { createPoller, signatureOfOrder } from '../lib/polling.js';
  import * as orderTokens from '../stores/orderTokens.js';

  const market = plugin('market');
  const _ = market._;
  const clock = market.require('clock');
  const storeSettings = market.require('settings');
  const { call } = market.require('api').actions;
  const { loginUrl } = market.require('host').actions;

  let { data } = $props();

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);
  const id = init.id;

  let order = $state(init.order ?? {});
  let settings = $state(init.settings ?? {});
  let token = $state(null);
  let mountedAt = $state(0);
  let loading = $state(false);
  let gone = $state(false);
  let refreshFailed = $state('');
  let pollStatus = $state('RUNNING');
  let offline = $state(false);

  // the poller reads these between renders, so they are plain mirrors of the state above
  let currentOrder = init.order ?? {};
  let expiryPolled = false;
  let poller = null;

  const nowMs = $derived(clock.state.now);
  const view = $derived(viewState(order, init.returnHint, nowMs, mountedAt));
  const extras = $derived(orderExtras(order));
  const left = $derived(expiryLeft(order, nowMs));
  const removeCents = $derived(
    (settings?.removeCents ?? storeSettings.state.settings?.removeCents) === true,
  );
  const pricesIncludeVat = $derived(
    (settings?.pricesIncludeVat ?? storeSettings.state.settings?.pricesIncludeVat) === true,
  );
  const loginHref = $derived(loginUrl(`/store/order/${id}`));

  function setOrder(next) {
    currentOrder = next;
    order = next;
  }

  /** Loads the full order again (with the access token when one is known). */
  async function fetchOrder() {
    loading = true;

    const res = await call('GET', `/orders/${encodeURIComponent(id)}`, {
      query: { locale: get(currentLanguage)?.code },
      headers: orderTokens.tokenHeaders(id, token),
    });

    loading = false;

    if (res.ok && res.order) {
      setOrder(res.order);
      refreshFailed = '';
      expiryPolled = false;

      return true;
    }

    if (res.code === 'NOT_FOUND') {
      gone = true;
      poller?.stop();
    } else refreshFailed = res.code || 'NETWORK';

    return false;
  }

  /** After an action or a manual refresh: reload, then restart the polling schedule. */
  async function reload() {
    await fetchOrder();
    poller?.reset();
  }

  function refreshStatus() {
    poller?.refresh();
  }

  // an order that expires while the page is open is polled once right away (14 §11.5)
  $effect(() => {
    if (
      view.state === 'AWAITING_PAYMENT' &&
      left === 0 &&
      !expiryPolled &&
      mountedAt > 0 &&
      poller
    ) {
      expiryPolled = true;
      poller.pollNow({ reset: false });
    }
  });

  onMount(() => {
    mountedAt = Date.now();

    // 1. a mail-link token moves to sessionStorage and leaves the address bar (a good stored token is remembered
    // so a bogus link token cannot destroy it)
    const stored = orderTokens.get(id);

    if (init.urlToken) orderTokens.save(id, init.urlToken);

    if (hasOrderParams(location.href))
      history.replaceState(history.state, '', stripOrderParams(location.href));

    token = init.urlToken || stored;

    const inPage = () => ['IFRAME', 'EMBEDDED'].includes(currentOrder?.payment?.start?.kind);

    poller = createPoller({
      viewState: () => viewState(currentOrder, init.returnHint, Date.now(), mountedAt).state,
      inPage,
      fetchStatus: () =>
        call('GET', `/orders/${encodeURIComponent(id)}/status`, {
          headers: orderTokens.tokenHeaders(id, token),
        }),
      refetch: fetchOrder,
      isHidden: () => document.hidden,
      onState: (state) => {
        pollStatus = state.status;
        offline = state.offline;
      },
      signature: () => signatureOfOrder(currentOrder),
    });

    const onVisibility = () => poller?.visibilityChanged();
    document.addEventListener('visibilitychange', onVisibility);

    (async () => {
      // 2. a limited view with a known token is fetched again with the header
      if (currentOrder.limited === true && token) {
        let ok = await fetchOrder();
        let verdict = tokenAfterRefetch({ ok, limited: currentOrder.limited === true });

        // the link token did not unlock the order: try the token that was stored before it once
        if (verdict === 'DROP' && stored && stored !== token) {
          orderTokens.save(id, stored);
          token = stored;
          ok = await fetchOrder();
          verdict = tokenAfterRefetch({ ok, limited: currentOrder.limited === true });
        }

        if (verdict === 'DROP') {
          orderTokens.remove(id);
          token = null;
        }
      }

      // 3. polling, unless the order is settled
      if (!gone) poller?.start();
    })();

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      poller?.stop();
      poller = null;
    };
  });
</script>
