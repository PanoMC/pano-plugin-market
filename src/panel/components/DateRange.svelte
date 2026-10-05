<div class="input-group">
  <span class="input-group-text">{$_('components.date-range.from')}</span>
  <input
    class="form-control"
    class:is-invalid={reversed}
    type="date"
    aria-label={$_('components.date-range.from')}
    bind:value={fromText} />
  <span class="input-group-text">{$_('components.date-range.to')}</span>
  <input
    class="form-control"
    class:is-invalid={reversed}
    type="date"
    aria-label={$_('components.date-range.to')}
    bind:value={toText} />
  <button class="btn btn-outline-secondary" type="button" onclick={apply}>
    {$_('common.apply')}
  </button>
</div>

<script>
  import { _ } from '../../i18n';
  import { dayToEpoch, toDateInput } from '../utils/format.js';

  // from / to: epoch ms (from = start of day, to = 23:59:59.999), browser zone.
  let { from = $bindable(null), to = $bindable(null), onApply = () => {} } = $props();

  let fromText = $state(toDateInput(from));
  let toText = $state(toDateInput(to));

  const fromMs = $derived(dayToEpoch(fromText, false));
  const toMs = $derived(dayToEpoch(toText, true));
  const reversed = $derived(fromMs !== null && toMs !== null && fromMs > toMs);

  function apply() {
    if (reversed) return;
    from = fromMs;
    to = toMs;
    onApply();
  }
</script>
