import { showErrorToast } from '../../i18n.js';
import { errorKey, errorParams } from './api.js';

/**
 * Error toast for a failed `call()` result. Toast text is always a translated key (never API text);
 * `$_` is the plugin i18n function.
 */
export function toastError($_, result) {
  showErrorToast($_(errorKey(result.error), errorParams(result.error, result.body)));
}
