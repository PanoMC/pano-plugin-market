<script module>
  import ApiUtil from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const { parent } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.settings.title');

    const body = await ApiUtil.get({
      path: '/api/panel/market/settings',
      request: event,
    });

    // Do NOT fall back to an empty settings object on failure: the sections
    // would silently render defaults that, if saved, overwrite the real config.
    // Surface an explicit error state instead.
    if (!body || body.error) {
      return { data: { error: body?.error || 'NETWORK_ERROR' } };
    }

    return { data: body };
  }
</script>

<script>
  import { base, page, goto } from '@panomc/sdk/svelte';
  import { buildQueryParams } from '@panomc/sdk/utils/api';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import GeneralSettings from '../components/settings/GeneralSettings.svelte';
  import PaymentMethods from '../components/settings/PaymentMethods.svelte';
  import CreditSettings from '../components/settings/CreditSettings.svelte';

  let { data } = $props();

  const SECTIONS = [
    { key: 'general', label: 'pages.settings.section-general' },
    { key: 'payments', label: 'pages.settings.section-payments' },
    { key: 'credits', label: 'pages.settings.section-credits' }
  ];

  const loadError = $derived(data?.error || null);

  // The active section lives in the URL (?section=payments): deep links open the
  // right tab, and the browser back button restores the previous one. Clicking a
  // tab navigates (goto) instead of flipping local state, so the address bar and
  // history always reflect the visible section. `general` is the default and is
  // omitted from the query string.
  const section = $derived.by(() => {
    const value = $page.url.searchParams.get('section');
    return value && SECTIONS.some((s) => s.key === value) ? value : 'general';
  });

  function selectSection(key) {
    if (key === section) return;
    const queryParams = buildQueryParams({ section: key === 'general' ? null : key });
    goto(`${base}/market/settings${queryParams}`, { invalidateAll: true });
  }
</script>

<MarketLayout>
  <div class="row g-3">
    <aside class="col-12 col-md-3">
      <div class="nav flex-column nav-pills sticky-md-top" role="tablist" aria-orientation="vertical" aria-label={$_('pages.settings.menu-label')}>
        {#each SECTIONS as item (item.key)}
          <button
            type="button"
            class="nav-link text-start"
            class:active={section === item.key}
            role="tab"
            aria-selected={section === item.key}
            onclick={() => selectSection(item.key)}>
            {$_(item.label)}
          </button>
        {/each}
      </div>
    </aside>

    <div class="col-12 col-md-9">
      {#if loadError}
        <div class="card">
          <div class="card-body text-center text-body-secondary py-5">
            <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
            <div>{$_('pages.settings.load-error')}</div>
          </div>
        </div>
      {:else if section === 'general'}
        <GeneralSettings settings={data} />
      {:else if section === 'payments'}
        <PaymentMethods settings={data} />
      {:else if section === 'credits'}
        <CreditSettings settings={data} />
      {/if}
    </div>
  </div>
</MarketLayout>
