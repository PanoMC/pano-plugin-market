// Host-bound glue of the order detail page: sends the requests of requests.js through ApiUtil.
// Not unit tested (it needs the host); the flow around it is (mutation.js).
import ApiUtil from '@panomc/sdk/utils/api';
import { call } from '../../utils/api.js';
import { toastError } from '../../utils/toast.js';
import { isStaleError } from './actions.js';
import { hideThen } from './hide-then.js';

/** `{ method, path, body }` of requests.js -> normalised `call()` result. */
export function send(request) {
  const method = request.method.toLowerCase();
  return call(ApiUtil[method]({ path: request.path, body: request.body }));
}

/** GET of a full path (query string included). */
export function fetchPath(path) {
  return call(ApiUtil.get({ path }));
}

/**
 * Submit flow of a form modal: send; on failure toast the code (a stale one also closes the modal and
 * refreshes the page), on success hide the modal first and then let the page refresh and toast.
 * Returns the `call()` result so the modal can react (stay open, mark a field).
 */
export async function submitModal({ request, $_, hide, onDone, onStale }) {
  const result = await send(request);
  if (!result.ok) {
    toastError($_, result);
    if (isStaleError(result.error)) {
      hide();
      await onStale();
    }
    return result;
  }
  hide();
  await onDone(result.body);
  return result;
}

/** Bootstrap modal helpers; safe on the server and before the host provides bootstrap. */
export function showModal(element) {
  if (element && typeof window !== 'undefined' && window.bootstrap) {
    window.bootstrap.Modal.getOrCreateInstance(element).show();
  }
}

export function hideModal(element) {
  if (element && typeof window !== 'undefined' && window.bootstrap) {
    window.bootstrap.Modal.getOrCreateInstance(element).hide();
  }
}

/** Hides the modal, then runs `done` once it is gone (see hide-then.js): the safe moment for a page refresh or a navigation. */
export function hideModalThen(element, done) {
  hideThen(element, typeof window !== 'undefined' ? window.bootstrap : undefined, done);
}

/** True while any Bootstrap modal is open (auto refresh pauses). */
export function anyModalOpen() {
  return typeof document !== 'undefined' && document.querySelector('.modal.show') !== null;
}
