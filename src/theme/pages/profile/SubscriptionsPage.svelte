<div class="vstack gap-4">
  {#if data.pills}
    <ProfilePills summary={data.summary} current="subscriptions" />
  {/if}

  <h2 class="h5 mb-0">{$_('theme.profile.subscriptions.title')}</h2>

  {#if data.state === 'ERROR' && !subscriptions}
    <ErrorAlert message={$_(messageKey(data.code))} onretry={reload} />
  {:else}
    <div class="vstack gap-3" aria-busy={loading ? 'true' : 'false'}>
      {#if loading && !subscriptions?.length}
        <LoadingBlock rows={3} />
      {:else if loadError}
        <ErrorAlert message={$_(messageKey(loadError))} onretry={reload} />
      {:else if subscriptions?.length}
        <div class={['vstack', 'gap-3', loading && 'opacity-50']}>
          {#each subscriptions as subscription (subscription.id)}
            <SubscriptionCard {subscription} onreplace={replace} onreload={reload} />
          {/each}
        </div>
      {:else}
        <NoContent icon="fa-solid fa-rotate fa-3x" text={$_('theme.profile.subscriptions.empty')} />
      {/if}
    </div>
  {/if}
</div>

<script module>
  import { redirect } from '@panomc/sdk/svelte';
  import { SUBSCRIPTION_PATH, resolveSubscriptionsLoad } from '../../lib/subscriptionModel.js';
  import { call } from '../../utils/api.js';
  import { has, loginUrl } from '../../utils/host.js';

  const SUMMARY_PATH = '/api/market/me/summary';

  export async function load(event) {
    const returnTo = `${event.url.pathname}${event.url.search}`;
    const { session } = await event.parent();

    // a guest returns to this page after signing in (login-return-url) or lands on the plain login
    if (!session?.user) throw redirect(302, loginUrl(returnTo));

    const pills = !has('page-sidebar-id');

    const [subscriptions, summary] = await Promise.all([
      call('GET', SUBSCRIPTION_PATH, { event }),
      pills ? call('GET', SUMMARY_PATH, { event }) : null,
    ]);

    const result = resolveSubscriptionsLoad({
      subscriptions,
      summary,
      features: { sidebar: has('page-sidebar-id'), meta: has('page-meta') },
    });

    if (result.redirect) throw redirect(302, loginUrl(returnTo));

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
  import ProfilePills from '../../components/profile/ProfilePills.svelte';
  import SubscriptionCard from '../../components/profile/SubscriptionCard.svelte';
  import { messageKey } from '../../lib/errorMap.js';
  import { createSequencer } from '../../lib/storeFilter.js';
  import { readSubscriptions, replaceSubscription } from '../../lib/subscriptionModel.js';
  import { bindSession } from '../../stores/session.js';

  let { data } = $props();

  bindSession(getContext('session'));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);

  let subscriptions = $state(init.state === 'READY' ? (init.subscriptions ?? []) : null);
  let loadError = $state('');
  let loading = $state(false);

  const seq = createSequencer();

  /** The card shows the subscription the server returned. */
  function replace(row) {
    subscriptions = replaceSubscription(subscriptions ?? [], row);
  }

  /** One request per reload; an answer that is no longer the latest request is dropped. */
  async function reload() {
    loading = true;

    const mine = seq.beginGrid();
    const res = await call('GET', SUBSCRIPTION_PATH);
    if (!seq.isGridLatest(mine)) return;

    loading = false;

    const rows = readSubscriptions(res);

    if (rows) {
      subscriptions = rows;
      loadError = '';
    } else loadError = res.code || 'NETWORK';
  }

  onMount(() => () => seq.invalidate());
</script>
