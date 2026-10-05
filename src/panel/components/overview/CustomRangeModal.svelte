<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('pages.overview.custom-range.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body">
        {#key openCount}
          <DateRange bind:from bind:to onApply={apply} />
        {/key}
      </div>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import DateRange from '../DateRange.svelte';

  // from / to: the range in use (epoch ms); onApply(from, to) is called with the new custom range.
  let { from: initialFrom = null, to: initialTo = null, onApply = () => {} } = $props();

  let modalElement = $state(null);
  let from = $state(null);
  let to = $state(null);
  let openCount = $state(0);

  export function open() {
    from = initialFrom;
    to = initialTo;
    openCount += 1;
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
    }
  }

  function apply() {
    if (from === null || to === null) return;
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    }
    onApply(from, to);
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
