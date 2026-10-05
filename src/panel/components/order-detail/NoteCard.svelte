<div class="card">
  <div class="card-body vstack gap-2">
    <div>{$_('pages.order-detail.cards.note')}</div>
    {#if canEdit}
      <form class="vstack gap-2" onsubmit={save}>
        <textarea
          class="form-control"
          class:is-invalid={invalid}
          rows="3"
          maxlength={ORDER_NOTE_MAX}
          placeholder={$_('pages.order-detail.note-placeholder')}
          oninput={() => (invalid = false)}
          bind:value={text}></textarea>
        <div>
          <button class="btn btn-primary" type="submit" disabled={busy || !dirty}>
            {$_('common.save')}
          </button>
        </div>
      </form>
    {:else if order?.note}
      <div class="text-break" style="white-space: pre-wrap;">{order.note}</div>
    {:else}
      <span class="text-body-secondary">—</span>
    {/if}
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { canEditNote } from './actions.js';
  import { ORDER_NOTE_MAX, noteRequest } from './requests.js';

  // onMutate(request, toastKey) => call() result.
  let { order = null, user = null, onMutate = async () => ({ ok: false }) } = $props();

  let busy = $state(false);
  let invalid = $state(false);

  const loaded = $derived(order?.note ?? '');
  // Follows the saved note (a derived value that typing overrides); a background refresh that brings
  // the same note never overwrites what the admin is typing.
  let text = $derived(loaded);
  const canEdit = $derived(canEditNote(user));
  const dirty = $derived(text !== loaded);

  async function save(event) {
    event.preventDefault();
    if (busy || !order) return;
    const built = noteRequest(order.id, text);
    invalid = built.error?.note === true;
    if (built.error) return;
    busy = true;
    try {
      await onMutate(built.request, 'pages.order-detail.toast.note-saved');
    } finally {
      busy = false;
    }
  }
</script>
