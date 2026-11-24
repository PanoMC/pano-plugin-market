<div class="card">
  <CardHeader>
    <div slot="left">{$_('pages.order-detail.cards.timeline')}</div>
  </CardHeader>
  {#if events.length === 0}
    <NoContent />
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
                  {statusText(event.fromStatus)} → {statusText(event.toStatus)}
                </span>
              {/if}
              {#if event.message}
                <div>{noteText(event.message)}</div>
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
  import { MAIL_KINDS } from '../../utils/settings.js';
  import { isKnownStatus } from '../../utils/status.js';
  import { eventActorName, isKnownActor, isKnownEventType, orderNote, timelineOf } from './model.js';

  let { detail } = $props();

  const events = $derived(timelineOf(detail?.events));

  /** Translated order status, the raw value when unknown, '—' when empty. */
  const statusText = (status) =>
    status === null || status === undefined || status === ''
      ? '—'
      : isKnownStatus('order', status)
        ? $_(`enums.order.${status}`)
        : status;

  /** A system message in the panel language (mail kind translated when known); an admin note as typed. */
  const noteText = (message) => {
    const note = orderNote(message);
    if (!note) return message;
    const kind = MAIL_KINDS.includes(note.kind) ? $_(`enums.mail-kind.${note.kind}`) : (note.kind ?? '');
    return $_(`enums.order-note.${note.code}`, { values: { kind, reason: note.reason ?? '' } });
  };
</script>
