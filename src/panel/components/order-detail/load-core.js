// SDK-free core of the OrderDetail load() (13 §6): the host calls are injected, so it is unit tested.
import { guard } from '../../utils/guard.js';
import { loadContextWith } from '../../utils/list-core.js';
import { PLUGIN_ID } from '../../utils/plugin.js';
import { orderPath } from './requests.js';
import { parseOrderId } from './model.js';

/** deps = { get({path, request}) }; event = SvelteKit load event. */
export async function loadOrderDetailWith(deps, event) {
  const fail = (error, id = null, ctx = null) => ({ data: { id, detail: null, ctx, error } });

  const allowed = await guard(event, ['OV']);
  if (allowed.denied) return fail('NO_PERMISSION');
  allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.pages.order-detail.title`);

  const id = parseOrderId(event.params?.id);
  if (id === null) return fail('NOT_FOUND');

  const [body, ctx] = await Promise.all([
    deps.get({ path: orderPath(id), request: event }),
    loadContextWith(deps, event),
  ]);
  if (!body || typeof body !== 'object') return fail('NETWORK_ERROR', id, ctx);
  if (body.error) return fail(body.error, id, ctx);
  return { data: { id, detail: body, ctx, error: null } };
}
