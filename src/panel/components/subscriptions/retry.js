// Host-bound glue shared by the subscription list and detail: the Retry Charge confirmation (13 section 14).
import ApiUtil from '@panomc/sdk/utils/api';
import { showSuccessToast } from '../../../i18n';
import { call, marketPath } from '../../utils/api.js';
import { toastError } from '../../utils/toast.js';

const STALE = new Set(['SUBSCRIPTION_NOT_RETRYABLE', 'NOT_FOUND', 'INVALID_STATE']);

/**
 * Opens the primary ConfirmModal for POST /subscriptions/:id/retry. `onDone(stale)` runs after the
 * modal closed (a stale answer also closes it) so the caller can refresh its data.
 */
export function confirmRetry(confirmModal, $_, subscription, onDone = () => {}) {
  confirmModal?.open({
    icon: 'fa-solid fa-rotate-right',
    title: $_('pages.subscriptions.retry-title'),
    description: $_('pages.subscriptions.retry-description'),
    confirmLabel: $_('pages.subscriptions.actions.retry'),
    onConfirm: async () => {
      const result = await call(
        ApiUtil.post({ path: marketPath(`/subscriptions/${subscription.id}/retry`), body: {} }),
      );
      if (!result.ok) {
        toastError($_, result);
        if (!STALE.has(result.error)) return false;
        setTimeout(() => onDone(true), 350);
        return;
      }
      showSuccessToast($_('pages.subscriptions.toast-retry'));
      setTimeout(() => onDone(false), 350);
    },
  });
}
