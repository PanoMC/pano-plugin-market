{#if mounted && navVisible(count, pathname)}
  <button
    type="button"
    class="btn btn-link nav-link position-relative"
    data-bs-toggle="offcanvas"
    data-bs-target="#marketCartOffcanvas"
    aria-controls="marketCartOffcanvas"
    aria-label={$_('theme.cart.title')}>
    <i class="fa-solid fa-cart-shopping" aria-hidden="true"></i>
    <span
      class="badge rounded-pill text-bg-danger position-absolute top-0 start-100 translate-middle"
      aria-live="polite"
      aria-atomic="true"
      ><span aria-hidden="true">{count}</span><span class="visually-hidden">
        {$_('theme.cart.items-in-cart', { values: { count } })}</span
      ></span>
  </button>
{/if}

<script>
  import { onMount } from 'svelte';
  import { _ } from '../../../i18n.js';
  import { call } from '../../utils/api.js';
  import { cart } from '../../stores/cart.js';
  import { user } from '../../stores/session.js';
  import { COUNT_KEY, STORAGE_KEY } from '../../lib/cartModel.js';
  import { countCacheValue, navVisible, resolveNavCount } from './cartView.js';

  // Rendered in the browser only (after mount): the count and the path are unknown during SSR.
  let mounted = $state(false);
  let pathname = $state('');
  let fetched = $state(null);

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
          mode: $cart.mode,
          count: $cart.count,
          user: $user,
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

    const current = $user;
    let cancelled = false;

    call('GET', '/api/market/me/summary').then((res) => {
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
