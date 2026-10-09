// SDK-free core of list.js / context.js: the host calls are injected, so the logic is unit tested.
import { failureOf } from './api.js';
import { guard } from './guard.js';
import { emptyList } from './page.js';
import { PLUGIN_ID } from './plugin.js';

/** GET /context; null on any failure. `get` = ({ path, request }) => Promise<body>. */
export async function loadContextWith({ get }, event) {
  const body = await get({ path: '/context', request: event });
  if (failureOf(body)) return null;
  return body;
}

/**
 * Core of loadList. `deps` = { get, buildQueryParams }.
 * See list.js for the options. The answer keeps the core shape: `data.items`, `data.page` (an object, read it with pageOf()).
 */
export async function loadListWith(deps, event, { path, params = [], nodes, title }) {
  const { get, buildQueryParams } = deps;
  // A failed load is a list with no rows and the error code (04 section 4: `{ items, page }`); the page reads it with pageOf().
  const empty = (error, filters = {}) => ({ data: emptyList(error, { ctx: null, filters }) });

  const allowed = await guard(event, nodes);
  if (allowed.denied) return empty('NO_PERMISSION');
  if (title) allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.${title}`);

  const searchParams = event.url.searchParams;
  const requested = parseInt(searchParams.get('page')) || 1;
  const filters = Object.fromEntries(params.map((name) => [name, searchParams.get(name)]));

  const fetchPage = (page) =>
    get({
      path: path + buildQueryParams({ ...filters, page: page === 1 ? null : page }),
      request: event,
    });

  let [body, ctx] = await Promise.all([fetchPage(requested), loadContextWith(deps, event)]);

  // A stale ?page= (bookmark, back button) points past the last page: refetch page 1 once.
  if (failureOf(body) === 'PAGE_NOT_FOUND' && requested > 1) {
    body = await fetchPage(1);
  }

  const failure = failureOf(body);
  if (failure) {
    const failed = empty(failure, filters);
    failed.data.ctx = ctx;
    return failed;
  }
  return { data: { ...body, ctx, filters } };
}
