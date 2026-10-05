// Host feature detection (14 §4.1). The only theme module that touches `pano` directly.
import { buildLoginPath, buildRegisterPath } from '../lib/redirectTarget.js';

let pano = null;

export function setPano(value) {
  pano = value || null;
}

export function getPano() {
  return pano;
}

/** True when the host announces `feature` in pano.features (15 §4.1). */
export function has(feature) {
  return typeof pano?.features?.has === 'function' && pano.features.has(feature) === true;
}

export function loginUrl(returnTo) {
  if (has('login-return-url') && typeof pano?.auth?.loginUrl === 'function')
    return pano.auth.loginUrl(returnTo);

  return '/login';
}

export function registerUrl(returnTo) {
  return has('login-return-url') ? buildRegisterPath(returnTo) : '/register';
}

/** Store of the items registered for a view (pano.ui.view.get). */
export function view(viewId) {
  return pano.ui.view.get(viewId);
}
