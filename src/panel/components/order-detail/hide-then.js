// Hides a Bootstrap modal and runs `done` once it is really gone (13 section 1.4: "a modal is always hidden before a navigation or invalidateAll()").
// Calling the page refresh right after `hide()` remounts the page while the fade-out transition is still running: the host disposes the modal instance
// in the middle of it, Bootstrap's transition callback then reads a removed element ("Cannot read properties of null (reading 'style')") and the backdrop
// can stay behind. `done` runs on `hidden.bs.modal`; the timer is the way out when the event never comes (the modal was not shown, or was disposed).

const FALLBACK_MS = 800;

/** Pure of the host: `element` is the modal element, `bootstrap` the host's `window.bootstrap` (or undefined on the server / before it exists). */
export function hideThen(element, bootstrap, done, { timeout = FALLBACK_MS } = {}) {
  let called = false;
  const run = () => {
    if (called) return;
    called = true;
    done();
  };

  if (!element || !bootstrap?.Modal || !element.classList?.contains('show')) {
    run();
    return;
  }

  element.addEventListener('hidden.bs.modal', run, { once: true });
  bootstrap.Modal.getOrCreateInstance(element).hide();
  setTimeout(run, timeout);
}

/**
 * A submit is refused while a request is in flight (`saving`) and from the successful response until the modal is reopened (`closing`): the modal stays
 * mounted, filled in and clickable for the whole fade-out of [hideThen], and a second click or Enter in that window would post the same money-moving form
 * again with a fresh Idempotency-Key.
 */
export function submitLocked({ saving = false, closing = false } = {}) {
  return Boolean(saving || closing);
}
