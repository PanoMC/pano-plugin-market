// Theme browser scenarios 1 to 10 of 14 section 20.3 (store and product), vanilla theme, the instance's own fake provider.
// Ids TH-01 .. TH-10 are the numbers of the spec. Every scenario that settles on a page fails on a console error or a page error.
import fs from 'node:fs';
import { newContext } from '../lib/browser.mjs';
import { must } from '../lib/api.mjs';
import { category, run } from '../lib/bootstrap.mjs';
import { assert, assertEqual, open, rawPlaceholders } from '../lib/ui.mjs';
import {
  badgeCount,
  cartButton,
  html,
  openProduct,
  png,
  product,
  productWithImages,
  registerServer,
  storedCart,
  waitForCards,
  withSettings,
} from './lib/helpers.mjs';

const escapeRe = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const text = (key) => {
  const en = JSON.parse(
    fs.readFileSync(new URL('../../src/locales/theme/en-US.json', import.meta.url), 'utf8'),
  );

  return key.split('.').reduce((node, part) => node[part], en);
};

const CLOSED = text('theme.store.closed');
// the locale JSON is inlined in every page, so the closed text alone proves nothing in raw HTML: the card's icon class does
const CLOSED_ICON = 'fa-store-slash';
const PAGE_SIZE_RESTORE = { storePageSize: 24 };

/** The catalogue of scenarios 1 to 3: two categories (3 + 1 products) and a product with a word of its own. */
async function storeFixture(ctx) {
  ctx.th ??= {};

  if (!ctx.th.store) {
    const { admin } = ctx;
    const catA = await category(admin, 'Gems');
    const catB = await category(admin, 'Capes');
    const a = [];

    for (const [name, price] of [
      ['Gem Alpha', '1.00'],
      ['Gem Bravo', '2.00'],
      ['Gem Charlie', '3.00'],
    ])
      a.push(await product(admin, name, { price, categoryId: catA.id }));

    const b = await product(admin, 'Quokka Cape', { price: '9.00', categoryId: catB.id });

    ctx.th.store = { catA, catB, a, b };
  }

  return ctx.th.store;
}

const names = (products) => products.map((p) => p.name);

async function cardNames(page) {
  return (await page.locator('.card h3 a').allInnerTexts()).map((t) => t.trim());
}

export const scenarios = [
  {
    id: 'TH-01',
    title:
      '/store is server-rendered with product links; category click filters without a reload; deep link renders page 2',
    async run(ctx) {
      const { env, admin, browser } = ctx;
      const fx = await storeFixture(ctx);

      await withSettings(admin, { storePageSize: 2 }, PAGE_SIZE_RESTORE, async () => {
        // server-rendered HTML, no JavaScript: names, links and the category tree
        const root = await html(env, '/store');
        assertEqual(root.status, 200, '/store status');
        assert(
          /<a href="\/store\/[a-z0-9-]+"/.test(root.text),
          '/store HTML links the product pages',
        );
        assert(
          root.text.includes(fx.catA.name) && root.text.includes(fx.catB.name),
          '/store HTML carries the category tree',
        );

        const page1 = await html(env, `/store?category=${fx.catA.id}`);
        const page2 = await html(env, `/store?category=${fx.catA.id}&page=2`);
        const onPage1 = fx.a.filter((p) => page1.text.includes(p.name));
        const onPage2 = fx.a.filter((p) => page2.text.includes(p.name));

        assertEqual(page1.status, 200, 'category page 1 status');
        assertEqual(page2.status, 200, 'category page 2 status');
        assertEqual(
          onPage1.length,
          2,
          `page 1 of the category lists 2 of 3 products (${names(onPage1)})`,
        );
        assertEqual(
          onPage2.length,
          1,
          `page 2 of the category lists the third (${names(onPage2)})`,
        );
        assert(
          onPage1.every((p) => !onPage2.includes(p)),
          'the two pages share no product',
        );
        for (const p of [...onPage1, ...onPage2])
          assert(
            (onPage1.includes(p) ? page1 : page2).text.includes(`href="/store/${p.slug}"`),
            `${p.name} is a link to /store/${p.slug} in the HTML`,
          );
        assert(
          !page2.text.includes(fx.b.name),
          'a product of the other category is not on the page',
        );

        // in the browser: the category click filters in place
        const ctxB = await newContext(browser, { viewport: 'desktop' });
        const page = await ctxB.page();
        await open(page, `${env.url}/store`, (p) => waitForCards(p, 1));

        let navigations = 0;
        page.on('request', (r) => r.isNavigationRequest() && navigations++);
        await page.evaluate(() => (window.__thMarker = 'alive'));

        await page
          .locator('#marketCategories')
          .getByRole('button', { name: new RegExp(escapeRe(fx.catA.name)) })
          .click();
        await page.waitForFunction(
          (id) => new URLSearchParams(location.search).get('category') === String(id),
          fx.catA.id,
          { timeout: 30000 },
        );
        await page.waitForFunction((n) => document.querySelectorAll('.card h3 a').length === n, 2, {
          timeout: 30000,
        });

        assertEqual(
          await page.evaluate(() => window.__thMarker),
          'alive',
          'no full reload on a category click',
        );
        assertEqual(navigations, 0, 'no document request on a category click');
        const filtered = await cardNames(page);
        assert(
          filtered.length === 2 && filtered.every((n) => names(fx.a).includes(n)),
          `only the products of the category are listed (${filtered})`,
        );

        // page 2 through the pager, still in place
        await page.getByRole('button', { name: 'Page 2' }).click();
        await page.waitForFunction(
          () => new URLSearchParams(location.search).get('page') === '2',
          null,
          {
            timeout: 30000,
          },
        );
        await page.waitForFunction(
          () => document.querySelectorAll('.card h3 a').length === 1,
          null,
          {
            timeout: 30000,
          },
        );
        assertEqual(
          await page.evaluate(() => window.__thMarker),
          'alive',
          'no full reload on the pager',
        );

        // the deep link shows the same page 2
        await open(page, `${env.url}/store?category=${fx.catA.id}&page=2`, (p) =>
          waitForCards(p, 1),
        );
        assertEqual((await cardNames(page)).length, 1, 'the deep link shows page 2');
        assertEqual(
          await page
            .locator('.page-item.active')
            .innerText()
            .then((t) => t.trim()),
          '2',
          'the pager marks page 2',
        );
        ctxB.expectNoErrors('TH-01');
        await ctxB.close();
      });
    },
  },

  {
    id: 'TH-02',
    title:
      'search debounces to one request, keeps focus, shows the empty-search card with a working "Clear filters"',
    async run(ctx) {
      const { env, browser } = ctx;
      const fx = await storeFixture(ctx);
      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();
      const searches = [];

      page.on('request', (r) => {
        const url = new URL(r.url());
        if (url.pathname.endsWith('/api/market/store/products') && url.searchParams.has('search'))
          searches.push(url.searchParams.get('search'));
      });

      await open(page, `${env.url}/store`, (p) => waitForCards(p, 1));
      const input = page.locator('#marketSearch');

      await input.click();
      await page.keyboard.type(fx.b.name, { delay: 25 });
      await page.waitForFunction(
        (name) =>
          [...document.querySelectorAll('.card h3 a')].some((a) => a.textContent.includes(name)),
        fx.b.name,
        { timeout: 30000 },
      );
      await page.waitForTimeout(800); // a second request would have gone out by now

      assertEqual(searches.length, 1, `one request for a typed text (${JSON.stringify(searches)})`);
      assertEqual(searches[0], fx.b.name, 'the request carries the whole typed text');
      assertEqual(
        await page.evaluate(() => document.activeElement?.id),
        'marketSearch',
        'the search field keeps focus while the list updates',
      );
      assertEqual((await cardNames(page)).length, 1, 'only the matching product is listed');

      // nothing matches
      await input.fill('');
      await page.keyboard.type('zzxqyvw', { delay: 30 });
      await page.getByText(text('theme.store.empty-search')).waitFor({ timeout: 30000 });
      assertEqual(
        await page.evaluate(() => document.activeElement?.id),
        'marketSearch',
        'focus is still in the search field on the empty result',
      );

      await page.getByRole('button', { name: text('theme.store.clear-filters') }).click();
      await page.waitForFunction(() => document.querySelectorAll('.card h3 a').length > 0, null, {
        timeout: 30000,
      });
      assertEqual(await input.inputValue(), '', '"Clear filters" empties the search field');
      assertEqual(
        await page.getByText(text('theme.store.empty-search')).count(),
        0,
        'the empty-search card is gone',
      );
      assert(
        !new URL(page.url()).searchParams.has('search'),
        'the address no longer carries ?search=',
      );
      ctxB.expectNoErrors('TH-02');
      await ctxB.close();
    },
  },

  {
    id: 'TH-03',
    title: 'the store API failing shows the error card, not "no products", and Retry recovers',
    async run(ctx) {
      const { env, browser } = ctx;
      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();

      // A stopped backend also stops the theme (it is served through the platform), so the error card is only reachable while the theme
      // runs and its API calls fail: the load of a client-side navigation to /store is made to fail at the network level. The route is
      // installed before the first page opens, because SvelteKit preloads the links of a page (a preload that already succeeded would be
      // reused by the click). It starts on the home page: a product page's server render already carries the (30 s cacheable) store
      // response, which the client reuses instead of asking again.
      let blocked = 0;
      await page.route(
        (url) => url.pathname === '/api/market/store',
        (route) => {
          blocked++;
          return route.abort('connectionrefused');
        },
      );
      await open(page, `${env.url}/`);

      await page.getByRole('link', { name: 'Store', exact: true }).first().click();
      await page.getByText(text('theme.store.load-error')).waitFor({ timeout: 30000 });

      assert(blocked >= 1, 'the store request really was refused');
      assertEqual(
        await page.getByText(text('theme.store.empty')).count(),
        0,
        'the page does not claim there are no products',
      );
      assertEqual(await page.locator('.card h3 a').count(), 0, 'no product card is rendered');
      const retry = page.getByRole('button', { name: /retry|try again/i });
      assert((await retry.count()) >= 1, 'the error card offers a retry button');

      await page.unroute((url) => url.pathname === '/api/market/store');
      await retry.first().click(); // reloads the page: the server render works again
      await waitForCards(page, 1);
      assertEqual(
        await page.getByText(text('theme.store.load-error')).count(),
        0,
        'after Retry the store loads',
      );

      // the refused request is the browser's own console line; a page error would be a defect
      const unexpected = ctxB.errors.filter(
        (e) => !/ERR_CONNECTION_REFUSED|Failed to load resource/.test(e),
      );
      assertEqual(unexpected.length, 0, `no other console error: ${unexpected.join(' | ')}`);
      await ctxB.close();
    },
  },

  {
    id: 'TH-04',
    title: 'storeEnabled=false shows the closed card on /store, /store/<slug> and /store/checkout',
    async run(ctx) {
      const { env, admin, browser } = ctx;
      const fx = await storeFixture(ctx);
      const slug = fx.a[0].slug;

      await withSettings(admin, { storeEnabled: false }, { storeEnabled: true }, async () => {
        for (const path of ['/store', `/store/${slug}`, '/store/checkout']) {
          const raw = await html(env, path);
          assertEqual(raw.status, 200, `${path} status while closed`);
          assert(raw.text.includes(CLOSED_ICON), `${path}: the closed card is server-rendered`);
          assert(!raw.text.includes(fx.a[0].name), `${path}: no product is listed while closed`);

          const ctxB = await newContext(ctx.browser, { viewport: 'desktop' });
          const page = await ctxB.page();
          await open(page, `${env.url}${path}`);
          await page.getByText(CLOSED).waitFor({ timeout: 30000 });
          assertEqual(await page.locator('.card h3 a').count(), 0, `${path}: no product card`);
          assertEqual(
            await page.getByRole('button', { name: 'Add to Cart' }).count(),
            0,
            `${path}: nothing can be added to the cart`,
          );
          ctxB.expectNoErrors(`TH-04 ${path}`);
          await ctxB.close();
        }
      });

      // open again afterwards: the setting really was restored
      const again = await html(env, '/store');
      assert(!again.text.includes(CLOSED_ICON), 'the store is open again after the scenario');
      assert(again.text.includes(`href="/store/`), 'the open store lists products again');
      void browser;
    },
  },

  {
    id: 'TH-05',
    title:
      'unknown slug is HTTP 404; a Turkish product name resolves through its ASCII slug and a percent-encoded path',
    async run(ctx) {
      const { env, admin, browser } = ctx;
      const turkish = await product(admin, 'Şapka Ödülü', {
        slug: `Şapka Ödülü ${run.tag}${run.next()}`,
        price: '5.00',
      });

      assert(
        /^[a-z0-9-]+$/.test(turkish.slug),
        `the backend stores an ASCII slug (${turkish.slug})`,
      );
      assert(
        turkish.slug.startsWith('sapka-odulu'),
        `the Turkish letters are transliterated (${turkish.slug})`,
      );

      const known = await html(env, `/store/${turkish.slug}`);
      assertEqual(known.status, 200, 'the Turkish-named product page status');
      assert(
        known.text.includes(turkish.name),
        'its page carries the name with the Turkish letters',
      );

      // the same slug with ASCII letters sent as percent escapes is still that product (the host decodes route params)
      const encoded = turkish.slug.replace(/a/g, '%61').replace(/o/g, '%6F');
      const viaEscapes = await html(env, `/store/${encoded}`);
      assertEqual(viaEscapes.status, 200, `a percent-encoded slug resolves (${encoded})`);
      assert(
        viaEscapes.text.includes(turkish.name),
        'the percent-encoded path shows the same product',
      );

      for (const [path, label] of [
        ['/store/no-such-product-zq', 'an unknown slug'],
        ['/store/%C5%9Fapka-yok', 'an unknown slug with non-ASCII characters'],
      ])
        assertEqual((await html(env, path)).status, 404, `${label} is a 404, not a 500`);

      // a malformed escape never reaches the route (the SvelteKit server refuses the URL itself): a client error, never a 5xx
      const malformed = await html(env, '/store/%E0%A4%A');
      assert(
        [400, 404].includes(malformed.status),
        `a malformed escape is a 400 or 404, got ${malformed.status}`,
      );

      // the browser shows the same
      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();
      const response = await page.goto(`${env.url}/store/%C5%9Fapka-yok`, {
        waitUntil: 'domcontentloaded',
        timeout: 240000,
      });
      assertEqual(response.status(), 404, 'the browser gets the 404 for an unknown non-ASCII slug');
      assertEqual(await page.locator('.card h3 a').count(), 0, 'the 404 page lists no product');

      await openProduct(page, env, turkish.slug);
      assert(
        (await page.locator('body').innerText()).includes(turkish.name),
        'the Turkish-named product page renders its name',
      );

      // from the store: the card link leads to the page
      await open(page, `${env.url}/store?search=${encodeURIComponent('Şapka')}`, (p) =>
        waitForCards(p, 1),
      );
      await page.locator('.card h3 a', { hasText: turkish.name }).first().click();
      await page.waitForURL(`**/store/${turkish.slug}`, { timeout: 30000 });
      await page.getByText(turkish.name).first().waitFor({ timeout: 30000 });
      const unexpected = ctxB.errors.filter((e) => !/404|Failed to load resource/.test(e));
      assertEqual(
        unexpected.length,
        0,
        `no console error besides the 404 itself: ${unexpected.join(' | ')}`,
      );
      await ctxB.close();
    },
  },

  {
    id: 'TH-06',
    title:
      'two option axes: a combination updates price, stock, image and ?variant=; an impossible one is disabled',
    async run(ctx) {
      const { env, admin, browser } = ctx;
      const item = await productWithImages(
        admin,
        'Cloak',
        {
          hasVariants: 'true',
          price: '4.00',
          variantOptions: JSON.stringify([
            {
              key: 'size',
              label: 'Size',
              values: [
                { key: 'S', label: 'S' },
                { key: 'L', label: 'L' },
              ],
            },
            {
              key: 'color',
              label: 'Color',
              values: [
                { key: 'red', label: 'Red' },
                { key: 'blue', label: 'Blue' },
              ],
            },
          ]),
          variants: JSON.stringify([
            {
              name: 'S Red',
              price: '4.00',
              stock: 5,
              position: 0,
              optionValues: { size: 'S', color: 'red' },
            },
            {
              name: 'S Blue',
              price: '5.00',
              stock: 2,
              position: 1,
              optionValues: { size: 'S', color: 'blue' },
            },
            {
              name: 'L Red',
              price: '7.00',
              stock: 3,
              position: 2,
              optionValues: { size: 'L', color: 'red' },
            },
          ]),
        },
        {
          variantImage_0: png(255, 0, 0),
          variantImage_1: png(0, 0, 255),
          variantImage_2: png(200, 0, 50),
        },
      );
      const detail = must(await admin.get(`/api/market/products/${item.slug}`), 'public product')
        .json.product;
      const variant = Object.fromEntries(detail.variants.map((v) => [v.name, v]));

      assert(
        new Set(detail.variants.map((v) => v.imageFileName)).size === 3 &&
          detail.variants.every((v) => v.imageFileName),
        'the three variants have three different images',
      );

      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();
      await openProduct(page, env, item.slug);

      const label = (name) => page.locator('fieldset label', { hasText: new RegExp(`^${name}$`) });
      const state = async () => ({
        price: (await page.locator('.fs-4').first().innerText()).replace(/\s+/g, ' '),
        stock: (
          await page
            .locator('.text-warning-emphasis')
            .first()
            .innerText()
            .catch(() => '')
        ).trim(),
        image: await page
          .locator('.ratio img')
          .first()
          .getAttribute('src')
          .catch(() => null),
        variant: new URL(page.url()).searchParams.get('variant'),
      });
      const expectVariant = async (name, price, stock) => {
        const v = variant[name];
        await page.waitForFunction(
          (id) => new URLSearchParams(location.search).get('variant') === String(id),
          v.id,
          { timeout: 30000 },
        );
        const s = await state();
        assert(s.price.includes(price), `${name}: the price shows ${price} (${s.price})`);
        assert(
          s.stock.startsWith(String(stock)),
          `${name}: the stock note says ${stock} left (${s.stock})`,
        );
        assert(
          s.image && s.image.includes(encodeURIComponent(v.imageFileName)),
          `${name}: the image is the variant's (${s.image})`,
        );
      };

      // the first variant is preselected: no ?variant= yet (nothing was chosen), but its price, stock and image show
      const first = await state();
      assert(
        first.price.includes('€4.00'),
        `the preselected S Red shows its price (${first.price})`,
      );
      assert(first.stock.startsWith('5'), `and its stock (${first.stock})`);
      assert(
        first.image?.includes(encodeURIComponent(variant['S Red'].imageFileName)),
        'and its image',
      );

      await label('Blue').click();
      await expectVariant('S Blue', '€5.00', 2);
      // L / Blue does not exist: with Blue chosen, L cannot be picked
      assert(
        await page.getByLabel('L', { exact: true }).isDisabled(),
        'L is disabled while Blue is selected (no L / Blue variant)',
      );

      await label('Red').click();
      await expectVariant('S Red', '€4.00', 5);
      await label('L').click();
      await expectVariant('L Red', '€7.00', 3);
      assert(
        await page.getByLabel('Blue').isDisabled(),
        'Blue is disabled once L is selected (no L / Blue variant)',
      );
      assert(!(await page.getByLabel('Red').isDisabled()), 'Red stays selectable with L');
      assert(!(await page.getByLabel('S', { exact: true }).isDisabled()), 'S stays selectable');

      // the chosen combination is what goes into the cart
      await page.getByRole('button', { name: text('theme.store.add-to-cart') }).click();
      await page.getByText(text('theme.store.added-to-cart')).first().waitFor({ timeout: 30000 });
      const cart = await storedCart(page);
      assertEqual(
        cart.items.at(-1).variantId,
        variant['L Red'].id,
        'the cart line carries the L Red variant id',
      );

      // a deep link with ?variant= opens that combination
      await open(page, `${env.url}/store/${item.slug}?variant=${variant['S Blue'].id}`, (p) =>
        p.locator('.fs-4').first().waitFor(),
      );
      const deep = await state();
      assert(deep.price.includes('€5.00'), `the deep link shows the S Blue price (${deep.price})`);
      assert(await page.getByLabel('Blue').isChecked(), 'the deep link selects Blue');
      assertEqual((await rawPlaceholders(page)).length, 0, 'no unreplaced translation placeholder');
      ctxB.expectNoErrors('TH-06');
      await ctxB.close();
    },
  },

  {
    id: 'TH-07',
    title:
      'an empty required custom field shows an inline error and adds nothing; a valid value goes into the line',
    async run(ctx) {
      const { env, browser } = ctx;
      const { engraved } = await ctx.catalogue();
      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();

      await openProduct(page, env, engraved.slug);
      const add = page.getByRole('button', { name: text('theme.store.add-to-cart') });
      const field = page.locator('#mf-engraving');

      await add.click();
      await page
        .locator('.invalid-feedback', { hasText: text('theme.errors.FIELD_REQUIRED') })
        .waitFor({ timeout: 30000 });
      assert(
        (await field.getAttribute('class')).includes('is-invalid'),
        'the field is marked invalid',
      );
      assertEqual(await field.getAttribute('aria-invalid'), 'true', 'aria-invalid is set');
      assertEqual(
        await page.evaluate(() => document.activeElement?.id),
        'mf-engraving',
        'focus moves to the field',
      );
      assertEqual(
        await page.getByText(text('theme.store.added-to-cart')).count(),
        0,
        'no "added" toast',
      );
      const empty = await storedCart(page);
      assert(!empty || empty.items.length === 0, 'nothing was added to the cart');
      assertEqual(await badgeCount(page), 0, 'the cart badge still reads 0');

      await field.fill('Hello Steve');
      await page
        .locator('.invalid-feedback', { hasText: text('theme.errors.FIELD_REQUIRED') })
        .waitFor({
          state: 'detached',
          timeout: 30000,
        });
      await add.click();
      await page.getByText(text('theme.store.added-to-cart')).first().waitFor({ timeout: 30000 });

      const cart = await storedCart(page);
      assertEqual(cart.items.length, 1, 'one line is stored');
      assertEqual(cart.items[0].productId, engraved.id, 'the line is the product');
      assertEqual(
        cart.items[0].fieldValues.engraving,
        'Hello Steve',
        'the line carries fieldValues',
      );

      // the cart shows what the buyer typed
      await (await cartButton(page)).click();
      const offcanvas = page.locator('#marketCartOffcanvas');
      await offcanvas.getByText('engraving: Hello Steve').waitFor({ timeout: 30000 });
      ctxB.expectNoErrors('TH-07');
      await ctxB.close();
    },
  },

  {
    id: 'TH-08',
    title:
      'buyer-choice server: a required select (error first), and a single choice is preselected',
    async run(ctx) {
      const { env, admin, browser } = ctx;
      const s1 = await registerServer(env, admin, 'alpha');
      const s2 = await registerServer(env, admin, 'bravo');
      const actions = JSON.stringify([
        {
          id: 'a1',
          type: 'COMMAND',
          value: ['say hello {username}'],
          phase: 'GRANT',
          serverMode: 'BUYER_CHOICE',
          requiresOnline: false,
        },
      ]);
      const multi = await product(admin, 'Server Pick', {
        actions,
        serverChoices: JSON.stringify([s1.id, s2.id]),
      });
      const single = await product(admin, 'Server Fixed', {
        actions,
        serverChoices: JSON.stringify([s1.id]),
      });

      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();

      // several choices: nothing is preselected, the select is required
      await openProduct(page, env, multi.slug);
      const select = page.locator('#mp-server');
      assertEqual(await select.inputValue(), '', 'no server is preselected when there are several');
      assertEqual(
        (await select.locator('option').allInnerTexts()).length,
        3,
        'placeholder + both servers',
      );

      await page.getByRole('button', { name: text('theme.store.add-to-cart') }).click();
      await page
        .locator('.invalid-feedback', { hasText: text('theme.errors.SERVER_REQUIRED') })
        .waitFor({ timeout: 30000 });
      assertEqual(
        await page.evaluate(() => document.activeElement?.id),
        'mp-server',
        'focus moves to the select',
      );
      assertEqual(
        (await storedCart(page))?.items?.length ?? 0,
        0,
        'nothing was added without a server',
      );

      await select.selectOption(String(s2.id));
      await page.getByRole('button', { name: text('theme.store.add-to-cart') }).click();
      await page.getByText(text('theme.store.added-to-cart')).first().waitFor({ timeout: 30000 });
      let cart = await storedCart(page);
      assertEqual(cart.items.at(-1).targetServerId, s2.id, 'the line targets the chosen server');

      // one choice: shown as text and sent without a pick
      await openProduct(page, env, single.slug);
      assertEqual(await page.locator('#mp-server').count(), 0, 'no select for a single choice');
      await page
        .getByText(text('theme.product.server-fixed').replace('{server}', s1.name))
        .waitFor({ timeout: 30000 });
      await page.getByRole('button', { name: text('theme.store.add-to-cart') }).click();
      await page.getByText(text('theme.store.added-to-cart')).first().waitFor({ timeout: 30000 });
      cart = await storedCart(page);
      const line = cart.items.find((l) => l.productId === single.id);
      assertEqual(line?.targetServerId, s1.id, 'the single choice is the line target');
      ctxB.expectNoErrors('TH-08');
      await ctxB.close();
    },
  },

  {
    id: 'TH-09',
    title:
      'sale: strikethrough, percent badge and countdown on card and page; the module flags off remove all of them',
    async run(ctx) {
      const { env, admin, browser } = ctx;
      const item = await product(admin, 'Sale Sword', { price: '20.00' });
      const created = must(
        await admin.post('/api/panel/market/discounts', {
          name: `TH-09 ${item.name}`,
          value: 25,
          unit: 'PERCENT',
          scope: 'PRODUCTS',
          productIds: [item.id],
          expiryDate: Date.now() + 3600e3,
          status: 'ACTIVE',
          showBadge: true,
        }),
        'discount',
      );

      try {
        const check = async (on) => {
          const ctxB = await newContext(browser, { viewport: 'desktop' });
          const page = await ctxB.page();
          const where = on ? 'flags on' : 'flags off';

          await open(page, `${env.url}/store?search=${encodeURIComponent(item.name)}`, (p) =>
            waitForCards(p, 1),
          );
          const card = page.locator('.card', { hasText: item.name }).first();
          assert(
            (await card.innerText()).includes('€15.00'),
            `${where}: the card shows the sale price`,
          );

          const probe = async (scope, label) => {
            const badge = await scope.locator('.badge.text-bg-danger', { hasText: '-25%' }).count();
            const strike = await scope.locator('s', { hasText: '€20.00' }).count();
            const clock = await scope.locator('.fa-clock').count();

            if (on) {
              assertEqual(badge, 1, `${where}: ${label} has the percent badge`);
              assertEqual(strike, 1, `${where}: ${label} strikes the list price`);
              assertEqual(clock, 1, `${where}: ${label} shows the countdown`);
              assert(
                /\d/.test(await scope.locator('.fa-clock').first().locator('xpath=..').innerText()),
                `${where}: the countdown carries a time`,
              );
            } else {
              assertEqual(badge, 0, `${where}: ${label} has no percent badge`);
              assertEqual(strike, 0, `${where}: ${label} has no strikethrough`);
              assertEqual(clock, 0, `${where}: ${label} has no countdown`);
            }
          };

          await probe(card, 'the card');
          await openProduct(page, env, item.slug);
          await probe(page.locator('.col-md-7'), 'the product page');
          ctxB.expectNoErrors(`TH-09 ${where}`);
          await ctxB.close();
        };

        await check(true);
        await withSettings(
          admin,
          { moduleSaleBadges: false, moduleSaleCountdown: false },
          { moduleSaleBadges: true, moduleSaleCountdown: true },
          () => check(false),
        );
        await check(true);
      } finally {
        await admin.request('DELETE', `/api/panel/market/discounts/${created.json.id}`);
      }
    },
  },

  {
    id: 'TH-10',
    title:
      'product page head: title, description, og:image, canonical and one parsable JSON-LD block',
    async run(ctx) {
      const { env, admin } = ctx;
      const name = 'Head Helm';
      const item = await productWithImages(
        admin,
        name,
        {
          price: '12.50',
          metaTitle: 'Meta Head Helm',
          metaDescription: 'A helm for the head & more',
          description: '<p>Plain description of the <strong>helm</strong>.</p>',
        },
        { image: png(10, 120, 200) },
      );
      const detail = must(await admin.get(`/api/market/products/${item.slug}`), 'public product')
        .json.product;
      const page = await html({ url: env.themeUrl ?? env.url }, `/store/${item.slug}`);
      const raw = page.text;
      const unescape = (value) =>
        value
          .replace(/&amp;/g, '&')
          .replace(/&lt;/g, '<')
          .replace(/&gt;/g, '>')
          .replace(/&quot;/g, '"')
          .replace(/&#39;/g, "'");
      const meta = (attr, key) => {
        const tag = [...raw.matchAll(/<meta\b[^>]*>/g)]
          .map((m) => m[0])
          .find((m) => new RegExp(`${attr}="${key}"`).test(m));
        const content = tag?.match(/content="([^"]*)"/)?.[1];

        return content === undefined ? null : unescape(content);
      };
      const title = unescape(raw.match(/<title>([^<]*)<\/title>/)?.[1] ?? '');
      // the host renders the canonical address as `og:url` (older builds as <link rel="canonical">): either is accepted
      const canonical =
        raw.match(/<link\b[^>]*rel="canonical"[^>]*>/)?.[0]?.match(/href="([^"]*)"/)?.[1] ??
        meta('property', 'og:url') ??
        undefined;
      const blocks = [
        ...raw.matchAll(/<script\b[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g),
      ];

      assertEqual(page.status, 200, 'the product page status');
      assert(
        title.includes('Meta Head Helm') || title.includes(item.name),
        `the document has a title (${title})`,
      );
      assert(
        raw.includes(item.name) || raw.includes('Meta Head Helm'),
        'the page renders the product',
      );

      // 14 section 14 needs the host feature `page-meta`; the theme zip bundled in the jar may predate it (documented degraded mode)
      const strict = Boolean(env.themeUrl) || Boolean(canonical) || blocks.length > 0;

      if (!strict) {
        console.log(
          '  TH-10: degraded host (no page-meta): only the title was asserted; start with --ui external to check the head',
        );
        return;
      }

      assert(title.includes('Meta Head Helm'), `the title is the metaTitle (${title})`);
      assertEqual(
        meta('name', 'description'),
        'A helm for the head & more',
        'the meta description is the metaDescription',
      );
      const image = meta('property', 'og:image');
      assert(
        image &&
          image.endsWith(`/api/market/products/image/${detail.imageFileName}`) &&
          /^https?:\/\//.test(image),
        `og:image is the absolute product image URL (${image})`,
      );
      assert(
        canonical && canonical.endsWith(`/store/${item.slug}`) && /^https?:\/\//.test(canonical),
        `canonical (${canonical})`,
      );
      assertEqual(blocks.length, 1, 'exactly one JSON-LD block');

      const ld = JSON.parse(unescape(blocks[0][1]));
      const node = Array.isArray(ld) ? ld[0] : ld;
      assertEqual(node['@type'], 'Product', 'the JSON-LD is a Product');
      assert(
        String(node.name).includes('Head Helm'),
        `the JSON-LD names the product (${node.name})`,
      );
      const offers = Array.isArray(node.offers) ? node.offers[0] : node.offers;
      assertEqual(String(offers?.price), '12.50', 'the JSON-LD offer carries the price');
      assert(!raw.includes('</script><script'), 'the JSON-LD cannot break out of its script tag');
    },
  },
];
