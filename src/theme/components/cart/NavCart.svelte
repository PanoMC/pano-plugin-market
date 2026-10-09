{#if mounted && navVisible(count, pathname)}
  <button
    type="button"
    class="market-nav-cart market-nav-cart__link btn btn-link nav-link position-relative"
    data-bs-toggle="offcanvas"
    data-bs-target="#marketCartOffcanvas"
    aria-controls="marketCartOffcanvas"
    aria-label={$_('theme.cart.title')}
    onclick={openCheckout}>
    <i class="fa-solid fa-cart-shopping" aria-hidden="true"></i>
    <span
      class="market-nav-cart__badge badge rounded-pill text-bg-danger position-absolute top-0 start-100 translate-middle"
      aria-live="polite"
      aria-atomic="true"
      ><span aria-hidden="true">{count}</span><span class="visually-hidden">
        {$_('theme.cart.items-in-cart', { values: { count } })}</span
      ></span>
  </button>
{/if}

<script module>
  // navbar injection (doc 01 section 2): the cart button of the navbar, also placeable with <PluginBlock id="market:NavCart" />
  export const view = {
    slot: 'navbar-right',
    id: 'market-cart',
    priority: 50,
    block: true,
    widget: true,
  };
</script>

<script>
  import { onMount } from 'svelte';
  import { getPanoContext } from '@panomc/sdk';
  import { plugin } from '@panomc/sdk/controllers';
  import { goto } from '@panomc/sdk/svelte';
  import { COUNT_KEY, STORAGE_KEY } from '../../lib/cartModel.js';
  import {
    checkoutHref,
    countCacheValue,
    hasOffcanvasHost,
    navVisible,
    resolveNavCount,
  } from './cartView.js';

  const market = plugin('market');
  const { _ } = market;
  const { call } = market.require('api').actions;
  const cart = market.require('cart');
  const session = market.require('session');

  // A slot component outside the market pages: the eager `market/session` controller follows the host session by itself, so a
  // signed-in visitor counts as signed in here too and the badge shows their account cart (browser scenario TH-15).

  // Rendered in the browser only (after mount): the count and the path are unknown during SSR.
  let mounted = $state(false);
  let pathname = $state('');
  let fetched = $state(null);

  // As a widget (`<pano-market-nav-cart>`) the button has no offcanvas to open: it leads to the checkout page instead. In a theme the
  // offcanvas exists and Bootstrap's data attributes open it, so the click is left alone.
  function openCheckout(event) {
    if (hasOffcanvasHost(document)) return;

    event.preventDefault();
    goto(checkoutHref(getPanoContext().context));
  }

  function read(kind, key) {
    try {
      return (kind === 'session' ? sessionStorage : localStorage).getItem(key);
    } catch (e) {
      return null;
    }
  }

  const source = $derived(
    mounted
      ? resolveNavCount({
          mode: cart.state.mode,
          count: cart.state.count,
          user: session.state.user,
          localRaw: read('local', STORAGE_KEY),
          cacheRaw: read('session', COUNT_KEY),
          now: Date.now(),
        })
      : { count: 0 },
  );
  const count = $derived(fetched !== null && source.fetch ? fetched : (source.count ?? 0));

  onMount(() => {
    pathname = window.location.pathname;
    mounted = true;
  });

  // a logged-in visitor outside the market pages: one summary request, cached for 60 s
  $effect(() => {
    if (!source.fetch) return;

    const current = session.state.user;
    let cancelled = false;

    call('GET', '/me/summary').then((res) => {
      if (cancelled || !res.ok) return;

      const value = Number(res.cartItemCount) || 0;
      fetched = value;
      try {
        sessionStorage.setItem(COUNT_KEY, countCacheValue(current, value, Date.now()));
      } catch (e) {
        // storage unavailable: the count is shown for this page only
      }
    });

    return () => {
      cancelled = true;
    };
  });
</script>
