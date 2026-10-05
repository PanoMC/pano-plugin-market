{#if visible}
  <div class="mt-3">
    <div class="card">
      <CardHeader>
        <div slot="left">{$_('pages.player-market.title')}</div>
      </CardHeader>
      <div class="card-body">
        <PlayerMarketPanel {summary} {username} {ctx} error={null} />
      </div>
    </div>
  </div>
{/if}

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { loadContext } from '../../utils/context.js';
  import { loadPlayerSummary } from './summary.js';

  // Hook load: runs inside the player layout load with event.params.username. A failure leaves
  // the card empty (data.error); it never throws into the host page.
  export function load(event) {
    return loadPlayerSummary({ get: (o) => ApiUtil.get(o), loadContext }, event);
  }
</script>

<script>
  import { CardHeader } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import PlayerMarketPanel from './PlayerMarketPanel.svelte';
  import { buildSummaryView, cardVisible } from './summary.js';

  // `data` = the load result; the host also passes `playerData` (unused).
  let { data = null } = $props();

  const summary = $derived(data?.summary ?? null);
  const username = $derived(data?.username ?? '');
  const ctx = $derived(data?.ctx ?? null);
  // An error, or no data at all, renders nothing: the card must not break the host page.
  const visible = $derived(
    cardVisible(
      buildSummaryView({
        summary,
        username,
        ctx,
        user: null,
        error: data?.error ?? (data ? null : 'NETWORK_ERROR'),
        fmt: { money: () => '', credits: () => '' },
      }),
    ),
  );
</script>
