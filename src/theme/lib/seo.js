// Page title and metadata of /store/[slug] (14 §14). Pure: no SDK, no DOM.
// `meta` is returned from load only when the host announces 'page-meta'; the title object form only when it
// announces 'page-title-options'. The description comes from sanitised HTML and is only ever used as an
// attribute value / JSON string.
import { cutDescription } from './storeMeta.js';

export { cutDescription };
export const DESCRIPTION_MAX = 160;

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

function decodeEntities(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (match, body) => {
    const key = body.toLowerCase();

    if (key[0] === '#') {
      const code = key[1] === 'x' ? parseInt(key.slice(2), 16) : parseInt(key.slice(1), 10);
      if (!Number.isFinite(code) || code <= 0 || code > 0x10ffff) return ' ';

      try {
        return String.fromCodePoint(code);
      } catch (e) {
        return ' ';
      }
    }

    return key in ENTITIES ? ENTITIES[key] : match;
  });
}

/** Strips tags, decodes the common entities and collapses whitespace. */
export function plainText(html) {
  if (typeof html !== 'string' || html === '') return '';

  // tags become a space so adjacent blocks do not glue their words together
  const stripped = html
    .replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(/<[^>]*>/g, ' ');

  return decodeEntities(stripped).replace(/\s+/g, ' ').trim();
}

/** metaDescription || shortDescription || plainText(description), cut at 160 characters on a word boundary. */
export function productDescription(product) {
  const pick = [product?.metaDescription, product?.shortDescription]
    .map((text) => (typeof text === 'string' ? text.trim() : ''))
    .find((text) => text !== '');

  return cutDescription(pick ?? plainText(product?.description), DESCRIPTION_MAX);
}

export const productPath = (slug) => `/store/${encodeURIComponent(slug)}`;

export const productCanonical = (origin, slug) => `${origin}${productPath(slug)}`;

export function productImageUrl(origin, product) {
  return product?.imageFileName
    ? `${origin}/api/plugins/pano-plugin-market/products/image/${encodeURIComponent(product.imageFileName)}`
    : null;
}

/** Display title of the product: metaTitle || name. */
export function productTitle(product) {
  const meta = typeof product?.metaTitle === 'string' ? product.metaTitle.trim() : '';

  return meta || String(product?.name ?? '');
}

/**
 * pageTitle of the page. With 'page-title-options' the page renders its own h1 and the document title is
 * hidden; without it the host shows the plain string as the visible title (and the page renders no h1).
 */
export function productPageTitle(product, { titleOptions = false } = {}) {
  const title = productTitle(product);

  return titleOptions ? { title, raw: true, hidden: true } : title;
}

/** `price` as a string with two decimals; null when it is not a number. */
export function priceText(value) {
  const n = Number(value);

  return Number.isFinite(n) ? n.toFixed(2) : null;
}

/** schema.org Product (14 §14). */
export function productJsonLd({ product, slug, origin }) {
  const canonical = productCanonical(origin, slug);
  const image = productImageUrl(origin, product);
  const description = productDescription(product);
  const variants = Array.isArray(product?.variants) ? product.variants : [];
  const currency = product?.currency || undefined;
  const availability =
    product?.inStock === false ? 'https://schema.org/OutOfStock' : 'https://schema.org/InStock';

  let offers;

  if (product?.priceFrom && variants.length) {
    const prices = variants.map((variant) => Number(variant?.price)).filter(Number.isFinite);
    const high = prices.length ? Math.max(...prices) : Number(product.price);

    offers = {
      '@type': 'AggregateOffer',
      lowPrice: priceText(product.price) ?? '0.00',
      highPrice: priceText(Math.max(high, Number(product.price) || 0)) ?? '0.00',
      offerCount: variants.length,
      priceCurrency: currency,
      url: canonical,
      availability,
    };
  } else {
    offers = {
      '@type': 'Offer',
      price: priceText(product?.price) ?? '0.00',
      priceCurrency: currency,
      url: canonical,
      availability,
    };
  }

  const out = {
    '@context': 'https://schema.org',
    '@type': 'Product',
    name: productTitle(product),
  };
  if (description) out.description = description;
  if (image) out.image = [image];
  out.sku = String(slug);
  out.offers = offers;

  return JSON.parse(JSON.stringify(out)); // drops undefined members (an unknown currency)
}

/** `meta` object of the page (only for a host with 'page-meta'). */
export function productMeta({ product, slug, origin }) {
  const meta = {
    type: 'product',
    canonical: productCanonical(origin, slug),
  };

  const description = productDescription(product);
  if (description) meta.description = description;

  const image = productImageUrl(origin, product);
  if (image) meta.image = image;

  meta.jsonLd = productJsonLd({ product, slug, origin });

  return meta;
}
