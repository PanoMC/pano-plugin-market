<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-body text-center">
        <div class="pb-3">
          <i class="{config?.icon ?? 'fa-solid fa-circle-question'} fa-3x d-block m-auto"></i>
        </div>
        <h5 class="mb-2">{config?.title ?? ''}</h5>
        <div class="text-body-secondary">{config?.description ?? ''}</div>
      </div>
      <div class="modal-footer flex-nowrap">
        <button
          class="btn btn-link text-decoration-none col-6 m-0"
          type="button"
          disabled={loading}
          onclick={hide}>
          {$_('common.cancel')}
        </button>
        <button
          class="btn btn-{config?.variant === 'danger' ? 'danger' : 'primary'} col-6 m-0"
          type="button"
          disabled={loading}
          onclick={confirm}>
          {#if loading}
            <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
          {/if}
          {config?.confirmLabel ?? ''}
        </button>
      </div>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../i18n';

  let modalElement = $state(null);
  let config = $state(null);
  let loading = $state(false);

  /**
   * Opens the modal. `title`, `description` and `confirmLabel` are already-translated strings.
   * `onConfirm()` may be async; returning `false` keeps the modal open (the caller showed a toast).
   */
  export function open({
    icon = 'fa-solid fa-circle-question',
    title,
    description,
    confirmLabel,
    variant = 'primary',
    onConfirm = () => {},
  }) {
    config = { icon, title, description, confirmLabel, variant, onConfirm };
    loading = false;
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
    }
  }

  function hide() {
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    }
  }

  async function confirm() {
    if (!config || loading) return;
    loading = true;
    let result;
    try {
      result = await config.onConfirm();
    } finally {
      loading = false;
    }
    if (result !== false) hide();
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
