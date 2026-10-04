import { derived } from 'svelte/store';
import { _ as i18n } from '@panomc/sdk/utils/language';
import { showToast } from '@panomc/sdk/toasts';

export const pluginId = 'pano-plugin-market';

// this is to render plugin translations
export const _ = derived(i18n, ($_fn) => {
  return (key, options) => $_fn(`plugins.${pluginId}.${key}`, options);
});

// Success/failure colouring for this plugin's toasts, matching the panel. showToast from
// @panomc/sdk/toasts is the host panel's ToastContainer `show`, whose signature is
// (text, params, toastComponent, options): passing undefined for toastComponent keeps the
// host's DefaultToast, and options.variant maps to Bootstrap's text-success / text-danger.
// These live here rather than in @panomc/sdk/toasts so the plugin keeps working against
// panel builds whose ToastContainer predates the variants (the pin in package.json,
// currently @panomc/sdk 1.0.0-dev.57, only sets the compile-time svelte version); they can
// be dropped for a direct SDK import once the minimum supported panel has them. On an older panel build the extra argument is ignored and
// the toast renders neutral, so this degrades instead of breaking — which is also what the
// theme side does today: theme-core's ToastContainer `show` has no options parameter yet, so
// the two store toasts stay neutral there until it grows one.
export function showSuccessToast(text, params = {}) {
  return showToast(text, params, undefined, { variant: 'success' });
}

export function showErrorToast(text, params = {}) {
  return showToast(text, params, undefined, { variant: 'danger' });
}
