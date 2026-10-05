import ApiUtil from '@panomc/sdk/utils/api';
import { loadContextWith } from './list-core.js';

/**
 * GET /context (13 §3.1). Readable with any market node, unlike GET /settings (which needs SET).
 * Returns null on failure; pages then show amounts in the row's own currency and hide
 * context-dependent controls.
 */
export function loadContext(event) {
  return loadContextWith({ get: (options) => ApiUtil.get(options) }, event);
}
