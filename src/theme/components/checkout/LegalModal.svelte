<div
  class="market-legal-modal modal fade"
  {id}
  tabindex="-1"
  aria-labelledby="{id}-title"
  aria-hidden="true"
  bind:this={element}>
  <div class="modal-dialog modal-lg modal-dialog-scrollable">
    <div class="modal-content">
      <div class="market-legal-modal__header modal-header">
        <h2 class="market-legal-modal__title modal-title fs-5" id="{id}-title">{title}</h2>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('theme.common.close')}></button>
      </div>
      <div class="market-legal-modal__body modal-body">
        <!-- the legal text is sanitised by the market backend (14 §2 rule 7) -->
        {@html content}
      </div>
      <div class="market-legal-modal__footer modal-footer">
        <button
          type="button"
          class="market-legal-modal__action btn btn-secondary"
          data-bs-dismiss="modal">
          {$_('theme.common.close')}
        </button>
      </div>
    </div>
  </div>
</div>

<script>
  import { onMount } from 'svelte';
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const { _ } = market;

  /** `title` and `content` = checkout config legal.title / legal.content (server-sanitised HTML). */
  let { id = 'market-legal-modal', title = '', content = '' } = $props();

  let element = $state();
  let modal;

  onMount(() => {
    // Bootstrap is the host's global; the instance lives and dies with this component.
    modal = window.bootstrap?.Modal.getOrCreateInstance(element);

    return () => {
      modal?.dispose();
      modal = undefined;
    };
  });

  export function show() {
    modal?.show();
  }

  export function hide() {
    modal?.hide();
  }
</script>
