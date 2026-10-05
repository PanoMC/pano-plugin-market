// SDK-free core of list.js / context.js: the host calls are injected, so the logic is unit tested.
import { marketPath } from './api.js';
import { guard } from './guard.js';
import { PLUGIN_ID } from './plugin.js';

/** GET /context; null on any failure. `get` = ({ path, request }) => Promise<body>. */
export async function loadContextWith({ get }, event) {
  const body = await get({ path: marketPath('/context'), request: event });
  if (!body || body.error) return null;
  return body;
}

/**
 * Core of loadList. `deps` = { get, buildQueryParams }.
 * See list.js for the options.
 */
export async function loadListWith(deps, event, { path, params = [], nodes, emptyKey, title }) {
  const { get, buildQueryParams } = deps;
  const empty = (error, filters = {}) => ({
    data: { [emptyKey]: [], count: 0, totalPage: 1, page: 1, error, ctx: null, filters },
  });

  const allowed = await guard(event, nodes);
  if (allowed.denied) return empty('NO_PERMISSION');
  if (title) allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.${title}`);

  const searchParams = event.url.searchParams;
  const requested = parseInt(searchParams.get('page')) || 1;
  const filters = Object.fromEntries(params.map((name) => [name, searchParams.get(name)]));

  const fetchPage = (page) =>
    get({
      path: marketPath(path) + buildQueryParams({ ...filters, page: page === 1 ? null : page }),
      request: event,
    });

  let [body, ctx] = await Promise.all([fetchPage(requested), loadContextWith(deps, event)]);
  let page = requested;

  // A stale ?page= (bookmark, back button) points past the last page: refetch page 1 once.
  if (body?.error === 'PAGE_NOT_FOUND' && requested > 1) {
    page = 1;
    body = await fetchPage(1);
  }

  if (!body || body.error) {
    const failed = empty(body?.error || 'NETWORK_ERROR', filters);
    failed.data.ctx = ctx;
    return failed;
  }
  return { data: { ...body, page, ctx, filters } };
}
