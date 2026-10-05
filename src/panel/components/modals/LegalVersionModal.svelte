<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_('modals.legal-version.title', {
            values: { version: text?.version ?? '', locale: text?.locale ?? '' },
          })}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body">
        <h6 class="mb-3">{text?.title ?? ''}</h6>
        <!-- content is sanitised by the market backend (04 §8, 13 §1 rule 6) -->
        <div class="border rounded p-3">
          {@html text?.content ?? ''}
        </div>
      </div>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { showModal } from '../order-detail/send.js';

  // Read-only view of one legal text version.
  let modalElement = $state(null);
  let text = $state.raw(null);

  export function open(version) {
    text = version;
    showModal(modalElement);
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
