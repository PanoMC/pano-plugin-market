<style>
  .market-mock-fab {
    position: fixed;
    right: 0;
    bottom: 96px;
    z-index: 2147483000;
    display: flex;
    align-items: center;
    gap: 6px;
    height: 44px;
    min-width: 44px;
    padding: 0 12px;
    border: 0;
    border-radius: 22px 0 0 22px;
    color: #fff;
    background: #6c757d;
    box-shadow: 0 2px 10px rgb(0 0 0 / 35%);
    justify-content: center;
  }
  .market-mock-fab-on {
    background: #d63384;
  }
  .market-mock-badge {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.04em;
    white-space: nowrap;
  }
  .market-mock-drawer {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 2147483001;
    width: min(340px, 100vw);
    padding: 16px;
    overflow-y: auto;
    color: var(--bs-body-color, #212529);
    background: var(--bs-body-bg, #fff);
    border-left: 1px solid var(--bs-border-color, #dee2e6);
    box-shadow: -4px 0 24px rgb(0 0 0 / 25%);
  }
</style>

<button
  type="button"
  class="market-mock-fab"
  class:market-mock-fab-on={active}
  title={$_('mock.fab-title')}
  aria-label={$_('mock.fab-title')}
  aria-expanded={open}
  data-market-mock="fab"
  onclick={() => (open = !open)}>
  <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true">
    <path
      d="M9 2h6v2h-1v5.2l5.6 9.3A2 2 0 0 1 17.9 21H6.1a2 2 0 0 1-1.7-2.5L10 9.2V4H9V2zm3 9.6L8.2 18h7.6L12 11.6z" />
  </svg>
  {#if active}<span class="market-mock-badge" data-market-mock="badge">{$_('mock.badge')}</span
    >{/if}
</button>

{#if open}
  <aside class="market-mock-drawer" data-market-mock="drawer" aria-label={$_('mock.title')}>
    <div class="d-flex align-items-center justify-content-between mb-2">
      <h5 class="mb-0">{$_('mock.title')}</h5>
      <button
        type="button"
        class="btn-close"
        aria-label={$_('mock.close')}
        onclick={() => (open = false)}></button>
    </div>
    <p class="small text-body-secondary">{$_('mock.intro')}</p>

    <div class="form-check form-switch mb-3">
      <input
        class="form-check-input"
        type="checkbox"
        role="switch"
        id="market-mock-switch"
        checked={active}
        onchange={toggle} />
      <label class="form-check-label" for="market-mock-switch">{$_('mock.enable')}</label>
      <div class="form-text">{$_('mock.enabled-hint')}</div>
    </div>

    <div class="mb-3">
      <label class="form-label" for="market-mock-volume">{$_('mock.volume')}</label>
      <select
        class="form-select"
        id="market-mock-volume"
        disabled={!active}
        value={volume ?? DEFAULT_VOLUME}
        onchange={(e) => apply(e.currentTarget.value)}>
        {#each VOLUMES as v (v)}
          <option value={v}>{$_(`mock.volume-${v}`)}</option>
        {/each}
      </select>
    </div>

    {#each [[panelPages, 'mock.panel'], [storePages, 'mock.storefront']] as [list, title] (title)}
      {#if list.length}
        <h6 class="mt-3">{$_(title)}</h6>
        <ul class="list-unstyled small mb-0">
          {#each list as p (p.href)}
            <li><a href={href(p)} data-sveltekit-reload>{p.label}</a></li>
          {/each}
        </ul>
      {/if}
    {/each}
    <p class="small text-body-secondary mt-3">{$_('mock.note')}</p>

    <button type="button" class="btn btn-secondary w-100 mt-2" onclick={reset}>
      {$_('mock.reset')}
    </button>
  </aside>
{/if}

<!-- Development-only floating button + drawer of the market preview mode (fake data). Mounted by
  boot.js on document.body only when the platform runs in development mode. Self-contained styling
  (inline / scoped), so it looks the same in the panel and in every theme. -->
<script>
  import { onMount } from 'svelte';
  import { _ } from '../i18n.js';
  import { base, invalidateAll } from '@panomc/sdk/svelte';
  import { COOKIE, cookieString, parseCookie } from './core.js';
  import { DEFAULT_VOLUME, VOLUMES } from './kit.js';

  let open = $state(false);
  let volume = $state(null);
  let pages = $state([]);

  const active = $derived(volume !== null);
  const panelPages = $derived(pages.filter((p) => p.side === 'panel'));
  const storePages = $derived(pages.filter((p) => p.side === 'theme'));

  onMount(() => {
    volume = parseCookie(document.cookie);
    const onKey = (e) => e.key === 'Escape' && (open = false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  $effect(() => {
    if (open && pages.length === 0) {
      import('./router.js').then((m) => (pages = m.PAGES));
    }
  });

  async function apply(next) {
    volume = next;
    document.cookie = cookieString(next);
    await invalidateAll();
  }

  const toggle = () => apply(active ? null : DEFAULT_VOLUME);
  const reset = () => apply(null);
  const href = (p) => `${p.side === 'panel' ? base : ''}${p.href}`;
</script>
