// Mutation flow of the order detail page (13 §6, §23): send, refresh the locally held order, report.
// Pure: every effect is injected, so the flow is unit tested.
import { isStaleError } from './actions.js';

/**
 * `deps` = { send(request) => call() result, refresh() => Promise, success(body), failure(result, stale) }.
 * Success: refresh, then `success(body)` (so the toast appears with the new data on screen).
 * Failure: `failure(result, stale)`; a stale error (flags out of date) refreshes first.
 * Returns the `call()` result so a caller (modal) can react (stay open, mark a field).
 */
export async function performMutation(deps, request) {
  const result = await deps.send(request);
  if (!result.ok) {
    const stale = isStaleError(result.error);
    if (stale) await deps.refresh();
    deps.failure(result, stale);
    return { ...result, stale };
  }
  await deps.refresh();
  deps.success(result.body);
  return { ...result, stale: false };
}
