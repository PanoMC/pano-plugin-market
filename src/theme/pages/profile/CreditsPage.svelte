{#if data.state === 'ERROR'}
  <div class="vstack gap-3">
    {#if data.pills}
      <ProfilePills summary={data.summary} current="credits" />
    {/if}
    <StoreStateCard
      icon="fa-solid fa-triangle-exclamation fa-3x"
      text={$_(messageKey(data.code))}
      onretry={() => location.reload()} />
  </div>
{:else}
  <div class="vstack gap-4">
    {#if data.pills}
      <ProfilePills summary={data.summary} current="credits" />
    {/if}

    <section class="card" aria-labelledby="market-balance-title">
      <div class="card-body">
        <h2 class="h6 text-body-secondary mb-1" id="market-balance-title">
          {$_('theme.profile.credits.balance')}
        </h2>
        <div class="display-6">{formatCredits(data.balance, data.creditName)}</div>
      </div>
    </section>

    {#if data.topUp}
      <TopUpCard
        topUp={data.topUp}
        packs={data.packs}
        settings={data.settings}
        creditName={data.creditName} />
    {/if}

    <section aria-labelledby="market-ledger-title">
      <h2 class="h5 mb-3" id="market-ledger-title">{$_('theme.profile.credits.ledger-title')}</h2>

      {#if ledgerError}
        <ErrorAlert message={$_(messageKey(ledgerError))} onretry={retry} />
      {:else}
        <div aria-busy={loading ? 'true' : 'false'}>
          {#if loading && !ledger.entries.length}
            <LoadingBlock rows={5} />
          {:else if ledger.entries.length}
            <div class={[loading && 'opacity-50']}>
              <LedgerTable entries={ledger.entries} creditName={data.creditName} />
            </div>
          {:else}
            <NoContent icon="fa-solid fa-coins fa-3x" text={$_('theme.profile.credits.empty')} />
          {/if}

          <div class="mt-3">
            <Pager {page} totalPage={ledger.totalPage} onpage={onPage} />
          </div>
        </div>
      {/if}
    </section>
  </div>
{/if}

<script module>
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { error, redirect } from '@panomc/sdk/svelte';
  import { get } from 'svelte/store';
  import { creditsGate, parseListQuery, resolveCreditsLoad } from '../../lib/profileModel.js';
  import { ensureSettings } from '../../stores/storeSettings.js';
  import { call } from '../../utils/api.js';
  import { has, loginUrl } from '../../utils/host.js';

  const CREDITS_PATH = '/api/market/me/credits';
  const CONFIG_PATH = '/api/market/checkout/config';
  const PACKS_PATH = '/api/market/store/products';
  const SUMMARY_PATH = '/api/market/me/summary';

  export async function load(event) {
    const returnTo = `${event.url.pathname}${event.url.search}`;
    const { session } = await event.parent();

    // a guest returns to this page after signing in (login-return-url) or lands on the plain login
    if (!session?.user) throw redirect(302, loginUrl(returnTo));

    const { page } = parseListQuery(event.url.searchParams);
    const pills = !has('page-sidebar-id');

    const [first, settings, summary] = await Promise.all([
      call('GET', CREDITS_PATH, { event, query: { page } }),
      ensureSettings(event),
      pills ? call('GET', SUMMARY_PATH, { event }) : null,
    ]);

    let credits = first;
    let usedPage = page;

    // a page past the end (the ledger cannot shrink, but a stale link can be wrong): back to the first page
    if (!credits.ok && credits.code === 'PAGE_NOT_FOUND' && page !== 1) {
      usedPage = 1;
      credits = await call('GET', CREDITS_PATH, { event, query: { page: 1 } });
    }

    let config = null;
    let packs = null;

    // the top-up limits are served by checkout/config only; the packs are ordinary products
    if (creditsGate(settings).topUp) {
      [config, packs] = await Promise.all([
        call('GET', CONFIG_PATH, { event, query: { locale: get(currentLanguage)?.code } }),
        call('GET', PACKS_PATH, { event, query: { kind: 'CREDIT_PACK', pageSize: 60 } }),
      ]);
    }

    const result = resolveCreditsLoad({
      credits,
      config,
      packs,
      summary,
      page: usedPage,
      settings,
      features: { sidebar: has('page-sidebar-id'), meta: has('page-meta') },
    });

    if (result.redirect) throw redirect(302, loginUrl(returnTo));
    if (result.notFound) throw error(404);

    result.data.pills = pills;

    return result;
  }
</script>

<script>
  import { getContext, onMount, untrack } from 'svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../../i18n.js';
  import ErrorAlert from '../../components/common/ErrorAlert.svelte';
  import LoadingBlock from '../../components/common/LoadingBlock.svelte';
  import LedgerTable from '../../components/profile/LedgerTable.svelte';
  import ProfilePills from '../../components/profile/ProfilePills.svelte';
  import TopUpCard from '../../components/profile/TopUpCard.svelte';
  import Pager from '../../components/store/Pager.svelte';
  import StoreStateCard from '../../components/store/StoreStateCard.svelte';
  import { messageKey } from '../../lib/errorMap.js';
  import { listSearch, readLedger } from '../../lib/profileModel.js';
  import { createSequencer } from '../../lib/storeFilter.js';
  import { bindSession } from '../../stores/session.js';
  import { setSettings } from '../../stores/storeSettings.js';
  import { formatCredits } from '../../utils/format.js';

  let { data } = $props();

  bindSession(getContext('session'));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);

  let page = $state(init.page ?? 1);
  let ledger = $state(init.ledger ?? readLedger(null));
  let ledgerError = $state('');
  let loading = $state(false);

  const seq = createSequencer();

  function writeUrl() {
    try {
      const search = listSearch({ page });

      window.history.replaceState(
        window.history.state,
        '',
        `${window.location.pathname}${search ? `?${search}` : ''}${window.location.hash}`,
      );
    } catch (e) {
      // no-op
    }
  }

  /** One request per page change; an answer that is no longer the latest request is dropped. */
  async function fetchLedger(next) {
    page = next;
    loading = true;

    const mine = seq.beginGrid();
    let res = await call('GET', CREDITS_PATH, { query: { page: next } });
    if (!seq.isGridLatest(mine)) return;

    if (!res.ok && res.code === 'PAGE_NOT_FOUND' && next !== 1) {
      page = next = 1;
      res = await call('GET', CREDITS_PATH, { query: { page: 1 } });
      if (!seq.isGridLatest(mine)) return;
    }

    loading = false;

    if (res.ok) {
      ledgerError = '';
      ledger = readLedger(res);
      writeUrl();
    } else ledgerError = res.code || 'NETWORK';
  }

  function onPage(next) {
    fetchLedger(next);
  }

  function retry() {
    fetchLedger(page);
  }

  onMount(() => {
    if (data.state === 'READY' && data.settings) setSettings(data.settings);

    return () => seq.invalidate();
  });
</script>
