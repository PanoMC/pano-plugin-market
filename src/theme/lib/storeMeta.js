// Page metadata of /store (14 §14). Pure. Returned from load as `meta` only when the host has 'page-meta'.
import { DEFAULT_SORT } from './storeFilter.js';
import { siteUrl } from './paths.js';

export const DESCRIPTION_MAX = 160;
const BACKGROUND_NAME =
  /^store-background-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(png|jpg|gif|webp)$/;

export function storeBackgroundUrl(name) {
  return typeof name === 'string' && BACKGROUND_NAME.test(name)
    ? siteUrl(`/store/background/${name}`)
    : '';
}

/** At most 160 characters, cut on a word boundary when the cut falls inside a word. */
export function cutDescription(text, max = DESCRIPTION_MAX) {
  const value = String(text ?? '')
    .replace(/\s+/g, ' ')
    .trim();
  if (value.length <= max) return value;

  const head = value.slice(0, max);
  if (/\s/.test(value[max])) return head.trimEnd();

  const space = head.search(/\s\S*$/);

  return (space > 0 ? head.slice(0, space) : head).trimEnd();
}

/** { description?, canonical, robots?, type } for the store page. */
export function storeMeta({ settings, filter, origin }) {
  const meta = { type: 'website' };

  const description = cutDescription(settings?.storeDescription);
  if (description) meta.description = description;

  const category = filter?.category != null ? `?category=${filter.category}` : '';
  meta.canonical = `${origin}/store${category}`;

  const sortSet = filter?.sort && filter.sort !== DEFAULT_SORT;
  if (filter?.search || (filter?.page ?? 1) > 1 || sortSet) meta.robots = 'noindex,follow';

  return meta;
}

/** pageTitle of the store page, with its optional description and background. */
export function storePageTitle(settings) {
  const storeName = typeof settings?.storeName === 'string' ? settings.storeName.trim() : '';
  const storeDescription =
    typeof settings?.storeDescription === 'string' ? settings.storeDescription.trim() : '';

  const pageTitle = { title: 'plugins.pano-plugin-market.theme.store.title' };

  if (storeName && storeDescription) {
    pageTitle.subtitle = 'plugins.pano-plugin-market.theme.store.subtitle';
    pageTitle.subtitleValues = { storeDescription };
  }

  const backgroundImage = storeBackgroundUrl(settings?.storeBackgroundImage);
  if (backgroundImage) pageTitle.backgroundImage = backgroundImage;

  return pageTitle;
}
