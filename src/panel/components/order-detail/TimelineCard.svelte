<div class="card">
  <CardHeader>
    <div slot="left">{$_('pages.order-detail.cards.timeline')}</div>
  </CardHeader>
  {#if events.length === 0}
    <NoContent icon="" />
  {:else}
    <ul class="list-group list-group-flush">
      {#each events as event (event.id)}
        <li class="list-group-item">
          <div class="d-flex justify-content-between gap-3">
            <div>
              <span class="fw-semibold">
                {isKnownEventType(event.type) ? $_(`enums.order-event.${event.type}`) : event.type}
              </span>
              {#if event.fromStatus || event.toStatus}
                <span class="text-body-secondary">
                  {event.fromStatus ?? '—'} → {event.toStatus ?? '—'}
                </span>
              {/if}
              {#if event.message}
                <div>{event.message}</div>
              {/if}
              <div class="small text-body-secondary">
                {isKnownActor(event.actorType)
                  ? $_(`enums.actor.${event.actorType}`)
                  : (event.actorType ?? '')}
                {#if eventActorName(event)}· {eventActorName(event)}{/if}
              </div>
            </div>
            <div class="text-nowrap text-body-secondary">
              <DateComponent time={event.createdAt} />
            </div>
          </div>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<script>
  import { CardHeader, Date as DateComponent, NoContent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import { eventActorName, isKnownActor, isKnownEventType, timelineOf } from './model.js';

  let { detail } = $props();

  const events = $derived(timelineOf(detail?.events));
</script>
