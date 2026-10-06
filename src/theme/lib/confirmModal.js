// The confirm / dismiss state of ConfirmModal, apart from Svelte so it can be tested with a stub Bootstrap modal.
//
// The confirmed action runs exactly once, after the modal has finished hiding (an action that removes the component while Bootstrap still
// fades the modal out makes Bootstrap read a removed element). Bootstrap's hide() does nothing while the modal is still fading in, and a
// second click on the confirm button would otherwise queue the action twice, so the action is driven by the single hidden event.

/**
 * @param {{ getModal: () => any, getElement: () => any, onconfirm: () => void, oncancel: () => void }} options
 */
export function createConfirmController({ getModal, getElement, onconfirm, oncancel }) {
  let confirmed = false;
  let hiding = false;

  return {
    /** the modal is about to open: forget a flag left over from an earlier round */
    reset() {
      confirmed = false;
      hiding = false;
    },

    /** `hide.bs.modal`: the modal started to hide (confirm button, X, Cancel, Esc, backdrop) */
    hideStarted() {
      hiding = true;
    },

    /** `hidden.bs.modal`: the one place the outcome is decided */
    hidden() {
      const was = confirmed;
      confirmed = false;
      hiding = false;

      if (was) onconfirm();
      else oncancel();
    },

    /** click on the confirm button */
    confirm() {
      // a second click, or a click on a modal that is already going away because it was dismissed
      if (confirmed || hiding) return;

      const modal = getModal();
      const element = getElement();

      // no Bootstrap (nothing to wait for): run the action right away
      if (!modal || !element) {
        onconfirm();
        return;
      }

      confirmed = true;

      // hide() is a no-op while the modal is still fading in: wait for it to be fully shown first
      if (element.classList.contains('show') && !modal._isTransitioning) modal.hide();
      else element.addEventListener('shown.bs.modal', () => modal.hide(), { once: true });
    },
  };
}
