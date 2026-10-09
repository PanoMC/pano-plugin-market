// Host-bound glue of the settings sections: the POST /settings call, the toasts and the focus move.
// The decisions (validation, partial body, status) live in utils/settings.js and are unit tested.
import { api } from '@panomc/sdk/plugin-api';
import { get } from 'svelte/store';
import { _, showErrorToast, showSuccessToast } from '../../../i18n';
import { call } from '../../utils/api.js';
import { focusFirstInvalid, submitSettings } from '../../utils/settings.js';
import { toastError } from '../../utils/toast.js';

export const postSettings = (body) => call(api.panel.post({ path: '/settings', body }));

/** POST /settings/credits: the credit keys are written by their own endpoint (13 §17 credits). */
export const postCreditSettings = (body) =>
  call(api.panel.post({ path: '/settings/credits', body }));

/** GET /settings; null on any failure. */
export async function fetchSettings() {
  const result = await call(api.panel.get({ path: '/settings' }));
  return result.ok ? result.body : null;
}

/** Toast + focus for a result of submitSettings / saveCurrencies that was not saved. */
export function reportFailure(result, order) {
  const t = get(_);
  if (result.status === 'invalid') {
    showErrorToast(t('settings.toast-invalid'));
    focusFirstInvalid(result.errors, order);
  } else if (result.status === 'failed') {
    toastError(t, { error: result.error, body: {} });
    if (result.errors && Object.keys(result.errors).length > 0)
      focusFirstInvalid(result.errors, order);
  }
}

/**
 * Validated, partial POST /settings of one section. `errors` = the client-side validation result.
 * Returns the submitSettings result; on `saved` the success toast is shown, on a failure the error toast.
 */
export async function saveSection({
  baseline,
  values,
  keys,
  errors = {},
  order = keys,
  post = postSettings,
}) {
  const result = await submitSettings({ post, baseline, values, keys, errors });
  if (result.status === 'saved') showSuccessToast(get(_)('settings.toast-saved'));
  else reportFailure(result, order);
  return result;
}
