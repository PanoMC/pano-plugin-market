import { describe, expect, test } from 'bun:test';
import {
  plainText,
  priceText,
  productCanonical,
  productDescription,
  productJsonLd,
  productMeta,
  productPageTitle,
  productTitle,
} from '../seo.js';

const ORIGIN = 'https://shop.example';
const base = {
  name: 'VIP Rank',
  price: 12.3,
  currency: 'EUR',
  inStock: true,
  description: '<p>Hello <b>world</b></p>',
};

describe('plainText', () => {
  test('strips tags and keeps words apart', () => {
    expect(plainText('<p>One</p><p>Two</p>')).toBe('One Two');
    expect(plainText('<ul><li>a</li><li>b</li></ul>')).toBe('a b');
  });

  test('decodes entities and collapses whitespace', () => {
    expect(plainText('Fish &amp; chips&nbsp;&lt;3 &quot;x&quot; &#39;y&#39; &#x41;')).toBe(
      'Fish & chips <3 "x" \'y\' A',
    );
    expect(plainText('a \n\n  b\t c')).toBe('a b c');
  });

  test('drops script and style content; non-strings give empty text', () => {
    expect(plainText('a<script>alert(1)</script>b<style>p{}</style>c')).toBe('a b c');
    expect(plainText(null)).toBe('');
    expect(plainText(5)).toBe('');
  });

  test('an unknown entity and an invalid code point stay harmless', () => {
    expect(plainText('&nope; &#0; &#x110000;')).toBe('&nope;');
  });
});

describe('productDescription', () => {
  test('metaDescription wins, then shortDescription, then the plain description', () => {
    expect(
      productDescription({ metaDescription: 'M', shortDescription: 'S', description: '<p>D</p>' }),
    ).toBe('M');
    expect(
      productDescription({ metaDescription: '  ', shortDescription: 'S', description: '<p>D</p>' }),
    ).toBe('S');
    expect(productDescription({ shortDescription: '', description: '<p>D <i>x</i></p>' })).toBe(
      'D x',
    );
    expect(productDescription({})).toBe('');
  });

  test('is cut at 160 characters on a word boundary', () => {
    const words = Array.from({ length: 60 }, (_, i) => `word${i}`).join(' ');
    const out = productDescription({ description: `<p>${words}</p>` });

    expect(out.length).toBeLessThanOrEqual(160);
    expect(words.startsWith(out)).toBe(true);
    expect(words[out.length]).toBe(' ');
  });

  test('a short text is kept, 160 exactly is kept', () => {
    expect(productDescription({ metaDescription: 'x'.repeat(160) })).toHaveLength(160);
    expect(productDescription({ metaDescription: 'x'.repeat(161) })).toHaveLength(160);
  });
});

describe('title', () => {
  test('metaTitle || name', () => {
    expect(productTitle({ name: 'N', metaTitle: 'T' })).toBe('T');
    expect(productTitle({ name: 'N', metaTitle: ' ' })).toBe('N');
  });

  test('object form with page-title-options, plain string without', () => {
    expect(productPageTitle({ name: 'N' }, { titleOptions: true })).toEqual({
      title: 'N',
      raw: true,
      hidden: true,
    });
    expect(productPageTitle({ name: 'N', metaTitle: 'T' })).toBe('T');
  });
});

describe('canonical', () => {
  test('encodes non-ASCII slugs and carries no query', () => {
    expect(productCanonical(ORIGIN, 'çay-seti')).toBe(`${ORIGIN}/store/%C3%A7ay-seti`);
    expect(productCanonical(ORIGIN, 'a b/c')).toBe(`${ORIGIN}/store/a%20b%2Fc`);
  });
});

describe('productJsonLd', () => {
  test('single price', () => {
    const ld = productJsonLd({ product: base, slug: 'vip', origin: ORIGIN });

    expect(ld).toEqual({
      '@context': 'https://schema.org',
      '@type': 'Product',
      name: 'VIP Rank',
      description: 'Hello world',
      sku: 'vip',
      offers: {
        '@type': 'Offer',
        price: '12.30',
        priceCurrency: 'EUR',
        url: `${ORIGIN}/store/vip`,
        availability: 'https://schema.org/InStock',
      },
    });
  });

  test('image is absolute and only present with an image', () => {
    const ld = productJsonLd({
      product: { ...base, imageFileName: 'a b.png' },
      slug: 'vip',
      origin: ORIGIN,
    });
    expect(ld.image).toEqual([`${ORIGIN}/api/market/products/image/a%20b.png`]);
    expect(productJsonLd({ product: base, slug: 'vip', origin: ORIGIN }).image).toBeUndefined();
  });

  test('priceFrom becomes an AggregateOffer over the variants', () => {
    const product = {
      ...base,
      price: 5,
      priceFrom: true,
      variants: [{ price: 5 }, { price: 9.5 }, { price: 7 }],
    };
    const { offers } = productJsonLd({ product, slug: 'vip', origin: ORIGIN });

    expect(offers).toMatchObject({
      '@type': 'AggregateOffer',
      lowPrice: '5.00',
      highPrice: '9.50',
      offerCount: 3,
      priceCurrency: 'EUR',
    });
    expect(offers.price).toBeUndefined();
  });

  test('out of stock', () => {
    const { offers } = productJsonLd({
      product: { ...base, inStock: false },
      slug: 'vip',
      origin: ORIGIN,
    });
    expect(offers.availability).toBe('https://schema.org/OutOfStock');
  });

  test('free products emit 0.00 and a subscription the same offer', () => {
    expect(
      productJsonLd({ product: { ...base, price: 0 }, slug: 'f', origin: ORIGIN }).offers.price,
    ).toBe('0.00');
    expect(
      productJsonLd({
        product: { ...base, billingMode: 'SUBSCRIPTION' },
        slug: 's',
        origin: ORIGIN,
      }).offers['@type'],
    ).toBe('Offer');
  });

  test('an unknown currency is left out and the result is plain JSON', () => {
    const ld = productJsonLd({
      product: { ...base, currency: undefined },
      slug: 'vip',
      origin: ORIGIN,
    });
    expect('priceCurrency' in ld.offers).toBe(false);
    expect(JSON.parse(JSON.stringify(ld))).toEqual(ld);
  });

  test('priceText', () => {
    expect(priceText(1)).toBe('1.00');
    expect(priceText('2.5')).toBe('2.50');
    expect(priceText('x')).toBe(null);
  });
});

describe('productMeta', () => {
  test('type, canonical, description, image and jsonLd', () => {
    const meta = productMeta({
      product: { ...base, imageFileName: 'p.png', metaDescription: 'Meta' },
      slug: 'vip',
      origin: ORIGIN,
    });

    expect(meta).toMatchObject({
      type: 'product',
      canonical: `${ORIGIN}/store/vip`,
      description: 'Meta',
      image: `${ORIGIN}/api/market/products/image/p.png`,
    });
    expect(meta.jsonLd['@type']).toBe('Product');
  });

  test('image and description are omitted when there is none', () => {
    const meta = productMeta({ product: { name: 'X', price: 1 }, slug: 'x', origin: ORIGIN });
    expect('image' in meta).toBe(false);
    expect('description' in meta).toBe(false);
  });
});
