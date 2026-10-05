{#if data.state === 'ERROR'}
  <StoreStateCard
    icon="fa-solid fa-triangle-exclamation fa-3x"
    text={$_(messageKey(data.code))}
    onretry={() => location.reload()} />
{:else if gone}
  <StoreStateCard icon="fa-solid fa-receipt fa-3x" text={$_('theme.order.not-found')} />
{:else}
  <div class="vstack gap-4" aria-busy={loading ? 'true' : 'false'}>
    <OrderStatusBlock {view} {order} {extras} {left} {loginHref} />

    {#if offline}
      <div class="small text-body-secondary" role="status">{$_('theme.order.poll-offline')}</div>
    {/if}

    {#if pollStatus === 'STOPPED'}
      <div>
        <button type="button" class="btn btn-outline-secondary btn-sm" onclick={refreshStatus}>
          <i class="fa-solid fa-rotate-right me-1" aria-hidden="true"></i>{$_(
            'theme.order.refresh-status',
          )}
        </button>
      </div>
    {/if}

    {#if refreshFailed}
      <ErrorAlert message={$_(messageKey(refreshFailed))} onretry={reload} />
    {/if}

    <!--
      Payment panel slot (MTU-09): rendered when view.panels.payment (AWAITING_PAYMENT, owner) and, read-only,
      when view.panels.instructions (PROCESSING). It needs: id, order, token (the access token in use or null),
      view, onrefetch = reload. Nothing is mounted here before that component exists.
    -->

    {#if view.panels.items && order.items?.length}
      <section class="vstack gap-2" aria-labelledby="market-order-items-title">
        <h2 class="h5 mb-0" id="market-order-items-title">{$_('theme.order.items')}</h2>
        <OrderItems items={order.items} currency={order.currency} {removeCents} />
      </section>
    {/if}

    {#if view.panels.totals}
      <section class="vstack gap-2" aria-labelledby="market-order-totals-title">
        <h2 class="h5 mb-0" id="market-order-totals-title">{$_('theme.order.totals.title')}</h2>
        <OrderTotals {order} {pricesIncludeVat} {removeCents} />
      </section>
    {/if}

    {#if !view.limited && (view.panels.shipments || order.shippingAddress || order.billingInfo || order.email)}
      <section class="vstack gap-2" aria-labelledby="market-order-shipping-title">
        {#if view.panels.shipments && order.shipments?.length}
          <h2 class="h5 mb-0" id="market-order-shipping-title">{$_('theme.order.shipments')}</h2>
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
  import { get } from 'svelte/store';
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { error } from '@panomc/sdk/svelte';
  import { resolveSlug } from '../components/product/productModel.js';
  import { parseOrderId, parseReturnHint, resolveOrderLoad } from '../lib/orderState.js';
  import { ensureSettings, setSettings } from '../stores/storeSettings.js';
  import { call } from '../utils/api.js';
  import { has } from '../utils/host.js';

  export async function load(event) {
    const id = parseOrderId(resolveSlug(event.params?.id, has('decoded-route-params')));

    if (!id) throw error(404);

    const locale = get(currentLanguage)?.code;

    // The SSR load never forwards the access token (14 §11.2): it asks for the limited view only; the browser
    // re-fetches with X-Order-Token after mount. A session owner is recognised by the cookie as usual.
    const [res, settings] = await Promise.all([
      call('GET', `/api/market/orders/${encodeURIComponent(id)}`, { event, query: { locale } }),
      ensureSettings(event),
    ]);

    const result = resolveOrderLoad({
      id,
      token: event.url.searchParams.get('token'),
      returnHint: parseReturnHint(event.url.searchParams.get('return')),
      res,
      settings,
      features: { meta: has('page-meta') },
    });

    if (result.notFound) throw error(404);

    if (result.data.state === 'READY' && settings) setSettings(settings);

    return result;
  }
</script>

<script>
  import { getContext, onMount, untrack } from 'svelte';
  import { _ } from '../../i18n.js';
  import ErrorAlert from '../components/common/ErrorAlert.svelte';
  import OrderActions from '../components/order/OrderActions.svelte';
  import OrderItems from '../components/order/OrderItems.svelte';
  import OrderStatusBlock from '../components/order/OrderStatusBlock.svelte';
  import OrderTotals from '../components/order/OrderTotals.svelte';
  import ShipmentList from '../components/order/ShipmentList.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import { messageKey } from '../lib/errorMap.js';
  import {
    expiryLeft,
    hasOrderParams,
    orderExtras,
    stripOrderParams,
    viewState,
  } from '../lib/orderState.js';
  import { createPoller, signatureOfOrder } from '../lib/polling.js';
  import { now } from '../stores/clock.js';
  import * as orderTokens from '../stores/orderTokens.js';
  import { bindSession } from '../stores/session.js';
  import { storeSettings } from '../stores/storeSettings.js';
  import { loginUrl } from '../utils/host.js';

  let { data } = $props();

  bindSession(getContext('session'));

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

  const nowMs = $derived($now);
  const view = $derived(viewState(order, init.returnHint, nowMs, mountedAt));
  const extras = $derived(orderExtras(order));
  const left = $derived(expiryLeft(order, nowMs));
  const removeCents = $derived((settings?.removeCents ?? $storeSettings?.removeCents) === true);
  const pricesIncludeVat = $derived(
    (settings?.pricesIncludeVat ?? $storeSettings?.pricesIncludeVat) === true,
  );
  const loginHref = $derived(loginUrl(`/store/order/${id}`));

  function setOrder(next) {
    currentOrder = next;
    order = next;
  }

  /** Loads the full order again (with the access token when one is known). */
  async function fetchOrder() {
    loading = true;

    const res = await call('GET', `/api/market/orders/${encodeURIComponent(id)}`, {
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

    // 1. a mail-link token moves to sessionStorage and leaves the address bar
    if (init.urlToken) orderTokens.save(id, init.urlToken);

    if (hasOrderParams(location.href))
      history.replaceState(history.state, '', stripOrderParams(location.href));

    token = init.urlToken || orderTokens.get(id);

    const inPage = () => ['IFRAME', 'EMBEDDED'].includes(currentOrder?.payment?.start?.kind);

    poller = createPoller({
      viewState: () => viewState(currentOrder, init.returnHint, Date.now(), mountedAt).state,
      inPage,
      fetchStatus: () =>
        call('GET', `/api/market/orders/${encodeURIComponent(id)}/status`, {
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
        await fetchOrder();

        if (currentOrder.limited === true) {
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
