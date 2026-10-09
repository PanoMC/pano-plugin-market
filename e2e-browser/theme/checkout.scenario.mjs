// Theme browser scenarios 16 to 22 of 14 section 20.3 (checkout: guest, login gate, codes, gift, legal, billing), vanilla theme.
// Ids TH-16 .. TH-22 are the numbers of the spec. The fake gateway is a test-mode method: only a signed-in buyer with the PAY node may use it
// (06 section 6.7), so a guest pays by bank transfer in these scenarios and the gateway round trip is made by a signed-in buyer.
import { must, MARKET_API, PANEL_MARKET_API } from '../lib/api.mjs';
import { BUYER_PASSWORD, coupon } from '../lib/bootstrap.mjs';
import { newContext } from '../lib/browser.mjs';
import { assert, assertEqual, open } from '../lib/ui.mjs';
import { addFromCard, product } from './lib/helpers.mjs';
import {
  address,
  applyCode,
  codeApply,
  countCheckoutPosts,
  fillGuest,
  holdAtGateway,
  myOrders,
  openCheckout,
  panelOrder,
  payBuyer,
  placeButton,
  signedIn,
  sleep,
  takeProvokedErrors,
  summaryText,
  text,
  waitForOrderPage,
  waitQuoted,
  withBankTransfer,
  withSettingsAndWait,
} from './lib/checkout.mjs';

const unique = () => Date.now().toString(36).slice(-6) + Math.floor(Math.random() * 99);

/** The radio of a payment method of the picker. */
const methodRadio = (page, id) => page.locator(`input[type="radio"][value="${id}"]`);

/** Selects the payment method and waits until it is the checked one. */
async function pickMethod(page, id) {
  await methodRadio(page, id).check();
  await page.waitForFunction(
    (value) => document.querySelector(`input[type="radio"][value="${value}"]`)?.checked === true,
    id,
  );
}

/** The sign-in form of the host (two steps: the account name, then the password). */
async function signInThroughForm(page, username) {
  await page.locator('#usernameOrEmail').fill(username);
  await page.locator('button[type="submit"]').click();
  await page.locator('#password').fill(BUYER_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 60000 });
}

export const scenarios = [
  {
    id: 'TH-16',
    title:
      'guest checkout: username and e-mail validation, order created, token stored, bank transfer instructions; a signed-in buyer is sent to the gateway and sees CONFIRMING, then paid after the webhook',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      // ---- the guest -------------------------------------------------------------------------------------------------------------------
      await withBankTransfer(admin, async () => {
        const guest = await newContext(browser, { viewport: 'desktop' });
        const page = await guest.page();
        const posts = countCheckoutPosts(page, env);

        await addFromCard(page, env, vip, vip.name);
        await openCheckout(page, env);
        await waitQuoted(page);

        // the method the guest may use is the only enabled one; the fake gateway is shown, disabled, with its reason (scenario 28 asserts the texts)
        assertEqual(
          await page.locator('input[type="radio"][value="fake"]').isDisabled(),
          true,
          'the fake gateway is disabled for a guest',
        );
        await pickMethod(page, 'bank-transfer');

        // nothing typed: the button validates, shows both errors, focuses the first invalid field and sends nothing
        await placeButton(page).click();
        await page.locator('input[aria-invalid="true"]').first().waitFor({ timeout: 10000 });
        assertEqual(
          await page.locator('input[aria-invalid="true"]').count(),
          2,
          'both buyer fields are marked invalid',
        );
        assertEqual(
          await page.evaluate(() => document.activeElement?.getAttribute('autocomplete')),
          'username',
          'the focus is on the first invalid field (the username)',
        );
        assertEqual(posts.length, 0, 'no checkout request was sent with an empty form');

        // an invalid name and an invalid e-mail each explain themselves on blur
        const nameInput = page.getByLabel('Minecraft username');
        const mailInput = page.getByLabel('E-mail');

        await nameInput.fill('bad name!');
        await nameInput.blur();
        await page.getByText(text('theme.checkout.username-invalid')).waitFor({ timeout: 10000 });
        await mailInput.fill('not-an-address');
        await mailInput.blur();
        await page.getByText(text('theme.checkout.email-invalid')).waitFor({ timeout: 10000 });

        const username = `Gst_${unique()}`.slice(0, 16);
        const email = `${username.toLowerCase()}@example.com`;

        await fillGuest(page, username, email);
        await waitQuoted(page);
        await placeButton(page).click();

        const publicId = await waitForOrderPage(page);
        assert(publicId, 'the browser landed on an order page');
        assertEqual(posts.length, 1, 'exactly one checkout request was sent');
        assert(posts[0].key && posts[0].key.length >= 16, 'the request carried an Idempotency-Key');
        assertEqual(
          JSON.parse(posts[0].body).guest?.username,
          username,
          'the request carried the guest',
        );

        const token = await page.evaluate(
          (id) => sessionStorage.getItem(`pano-plugin-market-order:${id}`),
          publicId,
        );
        assert(token && token.length > 8, 'the order token is stored for this tab');

        await page.getByText('DE89370400440532013000').first().waitFor({ timeout: 30000 });
        await page.locator('[role="status"]').first().waitFor({ timeout: 30000 });
        assert(
          !(await page.locator('body').innerText()).includes(text('theme.order.limited')),
          'the guest who just ordered sees the full view, not the limited one',
        );

        const stored = await panelOrder(admin, publicId);
        const dump = JSON.stringify(stored);
        assert(dump.includes(email), 'the panel holds the guest e-mail of the order');
        guest.expectNoErrors('TH-16 guest');
        await guest.close();
      });

      // ---- a signed-in buyer: redirect to the gateway, return, CONFIRMING, webhook, paid ------------------------------------------------
      const account = await payBuyer(buyer, admin, 'redir');

      // the gateway holds the payment: at the return it reports a status the provider does not know yet, so the order page is CONFIRMING
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      const held = await holdAtGateway(page, gateway);

      await addFromCard(page, env, vip, vip.name);
      await openCheckout(page, env);
      await waitQuoted(page);
      await pickMethod(page, 'fake');
      await placeButton(page).click();

      const publicId = await waitForOrderPage(page);
      assertEqual(held.held.length, 1, 'the buyer was sent to the gateway pay page once');
      await page
        .getByText(text('theme.order.state.confirming'))
        .first()
        .waitFor({ timeout: 30000 });

      await held.release();
      await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 90000 });
      assert(publicId, 'the order page of the buyer');
      ctx.expectNoErrors('TH-16 signed in');
      await ctx.close();
    },
  },

  {
    id: 'TH-17',
    title:
      'guest checkout off: the login card replaces the form; signing in keeps the cart and the checkout shows the buyer',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'gate');

      await withSettingsAndWait(
        admin,
        { allowGuestCheckout: false },
        { allowGuestCheckout: true },
        async () => {
          const ctx = await newContext(browser, { viewport: 'desktop' });
          const page = await ctx.page();

          await addFromCard(page, env, vip, vip.name);
          await openCheckout(page, env);
          await page.getByText(text('theme.checkout.login-required')).waitFor({ timeout: 30000 });
          assertEqual(
            await page.locator('#market-checkout-summary-card').count(),
            0,
            'no checkout form for a guest',
          );
          assert(
            (await page.getByRole('link', { name: text('theme.checkout.sign-in') }).count()) > 0,
            'the card offers the sign-in link',
          );
          assert(
            (await page.getByRole('link', { name: text('theme.checkout.register') }).count()) > 0,
            'the card offers the register link',
          );

          const loginHref = await page
            .getByRole('link', { name: text('theme.checkout.sign-in'), exact: true })
            .last()
            .getAttribute('href');
          const returns = decodeURIComponent(loginHref ?? '').includes('/store/checkout');
          assertEqual(
            (await page.getByText(text('theme.checkout.login-return-hint')).count()) > 0,
            !returns,
            'the "come back to the store" hint shows exactly when the host cannot return to the checkout itself',
          );

          await open(page, `${env.url}${loginHref}`);
          await signInThroughForm(page, account.username);

          if (returns) {
            // the host carries `login-return-url`: it returns to the checkout with the cart intact
            await page.waitForURL('**/store/checkout', { timeout: 60000 });
          } else {
            await openCheckout(page, env);
          }

          await page.locator('#market-checkout-summary-card').waitFor({ timeout: 60000 });
          await waitQuoted(page);
          await page
            .getByText(account.username, { exact: true })
            .first()
            .waitFor({ timeout: 30000 });
          assert(
            (await summaryText(page)).includes(vip.name),
            'the cart of the guest is in the checkout of the signed-in buyer',
          );
          // the host's two-step sign-in form answers its first step with a 422 the browser logs; that line alone is the host's
          const own = ctx.errors.filter((e) => !/\/login[^ ]*: .*status of 422/.test(e));
          assertEqual(
            own.length,
            0,
            `TH-17: no console error besides the sign-in step: ${own.join(' | ')}`,
          );
          await ctx.close();
        },
      );
    },
  },

  {
    id: 'TH-18',
    title:
      'coupon: a valid code adds a discount row; an invalid one explains itself inline; five wrong codes lock the input with a countdown',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'cpn');
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      const good = await coupon(admin, 20);

      await addFromCard(page, env, vip, vip.name);
      await openCheckout(page, env);
      await waitQuoted(page);

      await applyCode(page, 'coupon', good.code);
      await page.locator('#market-checkout-summary-card dl').getByText(good.code).first().waitFor({
        timeout: 30000,
      });
      const applied = await summaryText(page);
      assert(applied.includes('€2.00'), `the coupon takes 20 % off 10.00: ${applied}`);
      assert(
        /Total\s*\n?\s*€8\.00/.test(applied),
        `the total is 8.00 after the coupon: ${applied}`,
      );
      assert(
        await page.getByRole('button', { name: `Remove code ${good.code}` }).isVisible(),
        'the applied code can be removed',
      );

      // removing it brings the full price back
      await page.getByRole('button', { name: `Remove code ${good.code}` }).click();
      await page.waitForFunction(
        () =>
          /Total\s*€10\.00/.test(
            document.querySelector('#market-checkout-summary-card')?.innerText ?? '',
          ),
        null,
        { timeout: 30000 },
      );

      // an unknown code: the reason is under the input and the code does not stay applied
      await applyCode(page, 'coupon', 'NOT-A-REAL-CODE');
      await page
        .locator('.invalid-feedback')
        .filter({ hasText: /code|coupon/i })
        .first()
        .waitFor({ timeout: 30000 });
      assertEqual(
        await page.locator('#market-checkout-coupon').inputValue(),
        'NOT-A-REAL-CODE',
        'the wrong code stays in the input so the buyer can correct it',
      );

      // five wrong codes lock the input (the threshold is lowered for the run and restored)
      await withSettingsAndWait(
        admin,
        { couponLockThreshold: 5, couponLockMinutes: 15 },
        { couponLockThreshold: 1000, couponLockMinutes: 15 },
        async () => {
          const wait = page.getByText(/Too many attempts\. Try again in \d+ seconds\./);

          for (let attempt = 1; attempt <= 8; attempt++) {
            if (await wait.count()) break;
            await page.locator('#market-checkout-coupon').fill(`WRONG-${attempt}-${unique()}`);
            await codeApply(page, 'coupon').click();
            await sleep(900);
          }
          await wait.first().waitFor({ timeout: 30000 });
          assertEqual(
            await page.locator('#market-checkout-coupon').isDisabled(),
            true,
            'the locked input is disabled',
          );
          const first = Number(/in (\d+) seconds/.exec(await wait.first().innerText())?.[1]);
          await sleep(3000);
          const later = Number(/in (\d+) seconds/.exec(await wait.first().innerText())?.[1]);
          assert(later < first, `the countdown runs down (${first} then ${later})`);
        },
      );

      ctx.expectNoErrors('TH-18');
      await ctx.close();
    },
  },

  {
    id: 'TH-19',
    title:
      'creator code with a coupon: both rows when combining is allowed; otherwise the not-combinable message',
    async run({ browser, env, admin, buyer }) {
      const streamer = await buyer('streamer');
      const tag = unique().toUpperCase();
      const item = await product(admin, 'Combine Pick', { price: '10.00' });
      const cpn = await coupon(admin, 10);
      const code = `CR${tag}`;
      const creatorId = must(
        await admin.post(`${PANEL_MARKET_API}/creator-codes`, {
          creator: streamer.username,
          code,
          discount: 5,
          unit: 'PERCENT',
          commissionPercent: 10,
        }),
        'creator code',
      ).json.id;
      const sale = must(
        await admin.post(`${PANEL_MARKET_API}/discounts`, {
          name: `E2E sale ${tag}`,
          value: 30,
          unit: 'PERCENT',
          scope: 'PRODUCTS',
          productIds: [item.id],
        }),
        'automatic discount',
      ).json.id;

      try {
        // the quote of the API is the oracle for what the page must show
        const oracle = async (account, combine) => {
          return must(
            await account.post(`${MARKET_API}/checkout/quote`, {
              items: [{ productId: item.id, quantity: 1 }],
              couponCode: cpn.code,
              creatorCode: code,
            }),
            `oracle quote combine=${combine}`,
          ).json.quote;
        };

        for (const combine of [true, false]) {
          await withSettingsAndWait(
            admin,
            { combineDiscountsAndCoupons: combine },
            { combineDiscountsAndCoupons: true },
            async () => {
              // a fresh buyer per run: the codes and lines of a signed-in buyer live on the server cart
              const account = await payBuyer(buyer, admin, `cmb${combine ? 'a' : 'b'}`);
              const expected = await oracle(account, combine);
              const ctx = await signedIn(browser, account);
              const page = await ctx.page();

              await addFromCard(page, env, item, item.name);
              await openCheckout(page, env);
              await waitQuoted(page);
              await applyCode(page, 'coupon', cpn.code);
              await page.waitForTimeout(800);
              await waitQuoted(page);
              await applyCode(page, 'creator', code);
              await page.waitForTimeout(800);
              await waitQuoted(page);

              const shown = await summaryText(page);

              if (combine) {
                assert(shown.includes(cpn.code), `the coupon row is shown (combine on): ${shown}`);
                assert(
                  shown.includes(code),
                  `the creator code row is shown (combine on): ${shown}`,
                );
                assertEqual(
                  Number(expected.couponDiscount) > 0 && Number(expected.creatorDiscount) > 0,
                  true,
                  'the API quote applies both codes when combining is allowed',
                );
              } else {
                assert(
                  shown.includes(text('theme.errors.CODE_NOT_COMBINABLE')) ||
                    (await page
                      .getByText(text('theme.errors.CODE_NOT_COMBINABLE'))
                      .first()
                      .isVisible()
                      .catch(() => false)),
                  `the not-combinable message is shown (combine off): ${shown}`,
                );
                assertEqual(
                  Number(expected.couponDiscount) + Number(expected.creatorDiscount),
                  0,
                  'the API quote takes the automatic discount instead of the codes when combining is off',
                );
              }

              assert(
                shown.includes(`€${Number(expected.total).toFixed(2)}`),
                `the total of the page equals the API quote (€${Number(expected.total).toFixed(2)}): ${shown}`,
              );
              await ctx.close();
            },
          );
        }
      } finally {
        await admin.request('DELETE', `${PANEL_MARKET_API}/discounts/${sale}`);
        await admin.request('DELETE', `${PANEL_MARKET_API}/creator-codes/${creatorId}`);
      }
    },
  },

  {
    id: 'TH-20',
    title:
      'gift: the recipient is required, your own name is rejected, an unknown player only warns and does not block',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'gift');
      const friend = await buyer('friend');
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      const posts = countCheckoutPosts(page, env);
      const held = await holdAtGateway(page, gateway);

      await addFromCard(page, env, vip, vip.name);
      await openCheckout(page, env);
      await waitQuoted(page);
      await pickMethod(page, 'fake');

      await page.getByRole('switch', { name: text('theme.checkout.gift-switch') }).check();
      const recipient = page.getByLabel(text('theme.checkout.gift-recipient'));
      await recipient.waitFor({ timeout: 10000 });

      // empty recipient: blocked client-side, the field is marked and focused
      await placeButton(page).click();
      await page.locator('input[aria-invalid="true"]').first().waitFor({ timeout: 10000 });
      assertEqual(posts.length, 0, 'nothing was sent without a recipient');

      // the buyer's own name
      await recipient.fill(account.username);
      await recipient.blur();
      await page.getByText(text('theme.checkout.gift-self')).waitFor({ timeout: 10000 });

      // an unknown player: a warning appears after the quote, the button still works
      const stranger = `Nobody_${unique()}`.slice(0, 16);
      await recipient.fill(stranger);
      await recipient.blur();
      await page.getByText(text('theme.checkout.recipient-unknown')).first().waitFor({
        timeout: 30000,
      });
      assertEqual(await placeButton(page).isDisabled(), false, 'the warning does not disable Pay');

      // a known player: no warning, and the order is a gift to that player
      await recipient.fill(friend.username);
      await recipient.blur();
      await page.waitForFunction(
        (message) => !document.body.innerText.includes(message),
        text('theme.checkout.recipient-unknown'),
        { timeout: 30000 },
      );
      await page.getByLabel(text('theme.checkout.gift-message')).fill('Enjoy!');
      await page.getByLabel(text('theme.checkout.gift-message')).blur();
      await waitQuoted(page);
      await placeButton(page).click();

      const publicId = await waitForOrderPage(page);
      assertEqual(posts.length, 1, 'one checkout request');
      const body = JSON.parse(posts[0].body);
      assertEqual(body.recipientUsername, friend.username, 'the request names the recipient');
      assertEqual(body.giftMessage, 'Enjoy!', 'and carries the message');
      await page.getByText(`Gift for ${friend.username}`).first().waitFor({ timeout: 30000 });
      ctx.expectNoErrors('TH-20');
      await ctx.close();

      // the unknown player really does not block: an order for a never-seen name is accepted too
      const ctx2 = await signedIn(browser, account);
      const page2 = await ctx2.page();
      const posts2 = countCheckoutPosts(page2, env);
      await holdAtGateway(page2, gateway);

      await addFromCard(page2, env, vip, vip.name);
      await openCheckout(page2, env);
      await waitQuoted(page2);
      await pickMethod(page2, 'fake');
      await page2.getByRole('switch', { name: text('theme.checkout.gift-switch') }).check();
      await page2.getByLabel(text('theme.checkout.gift-recipient')).fill(stranger);
      await page2.getByLabel(text('theme.checkout.gift-recipient')).blur();
      await page2.getByText(text('theme.checkout.recipient-unknown')).first().waitFor({
        timeout: 30000,
      });
      await waitQuoted(page2);
      await placeButton(page2).click();
      await waitForOrderPage(page2);
      assertEqual(posts2.length, 1, 'the order for an unknown recipient was placed');
      await ctx2.close();
    },
  },

  {
    id: 'TH-21',
    title:
      'legal text required: unticked blocks client-side; a text changed between quote and submit re-prompts',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'legal');
      const tag = unique();

      const publish = async (title, content) =>
        must(
          await admin.post(`${PANEL_MARKET_API}/settings/legal`, {
            locale: 'en-US',
            title,
            content,
          }),
          'legal text',
        );

      await publish(`Store terms ${tag}`, `<p>Version one ${tag}</p>`);

      await withSettingsAndWait(
        admin,
        { legalTextRequired: true },
        { legalTextRequired: false },
        async () => {
          const ctx = await signedIn(browser, account);
          const page = await ctx.page();
          const posts = countCheckoutPosts(page, env);
          const held = await holdAtGateway(page, gateway);

          await addFromCard(page, env, vip, vip.name);
          await openCheckout(page, env);
          await waitQuoted(page);
          await pickMethod(page, 'fake');

          const box = page.locator('#market-checkout-legal');
          await box.waitFor({ timeout: 30000 });
          assertEqual(await box.isChecked(), false, 'the legal box is never pre-ticked');

          // unticked: blocked client-side, the reason shows and the box takes the focus
          await placeButton(page).click();
          await page.getByText(text('theme.checkout.legal-required')).waitFor({ timeout: 10000 });
          assertEqual(posts.length, 0, 'nothing was sent with the box unticked');
          assertEqual(
            await page.evaluate(() => document.activeElement?.id),
            'market-checkout-legal',
            'the legal box has the focus',
          );

          // the text can be read in a dialog
          await page.getByRole('button', { name: `Store terms ${tag}` }).click();
          await page.getByText(`Version one ${tag}`).waitFor({ timeout: 10000 });
          await page.locator('.modal.show .btn-close').click();
          await page.locator('.modal.show').waitFor({ state: 'detached', timeout: 10000 });

          // tick, then the owner publishes a new version before the buyer presses Pay
          await box.check();
          await publish(`Store terms ${tag}`, `<p>Version two ${tag}</p>`);
          await placeButton(page).click();

          // no order: the answer asked for the new text, the box is unticked again with the "text changed" note
          await page.getByText(text('theme.checkout.legal-updated')).waitFor({ timeout: 30000 });
          assertEqual(await box.isChecked(), false, 'the box is unticked after the text changed');
          assertEqual(
            (await myOrders(account)).filter((o) => o.createdAt && o.status === 'PENDING').length,
            0,
            'no order was created with the old acceptance',
          );

          // accept the new text: the order goes through
          await box.check();
          await placeButton(page).click();
          const publicId = await waitForOrderPage(page);
          assert(publicId, 'the order is placed once the new text is accepted');
          assert(held.held.length >= 1, 'the buyer was sent to the gateway');
          // the answer that asked for the new text is a 400 the browser logs
          takeProvokedErrors(ctx, /status of 400/, 1, 'TH-21');
          ctx.expectNoErrors('TH-21');
          await ctx.close();
        },
      );
    },
  },

  {
    id: 'TH-22',
    title:
      'billing: REQUIRED enforces the fields; a provider that needs a phone with mode OFF asks only for the phone',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'bill');

      // ---- REQUIRED ------------------------------------------------------------------------------------------------------------------
      await withSettingsAndWait(
        admin,
        { billingInfoMode: 'REQUIRED' },
        { billingInfoMode: 'OPTIONAL' },
        async () => {
          const ctx = await signedIn(browser, account);
          const page = await ctx.page();
          const posts = countCheckoutPosts(page, env);
          const held = await holdAtGateway(page, gateway);

          await addFromCard(page, env, vip, vip.name);
          await openCheckout(page, env);
          await waitQuoted(page);
          await pickMethod(page, 'fake');

          const first = page.locator('#market-checkout-billing-firstName');
          await first.waitFor({ timeout: 30000 });
          assertEqual(
            await page.locator('#market-checkout-billing-open').count(),
            0,
            'with REQUIRED there is no "add billing details" switch: the form is always open',
          );

          await placeButton(page).click();
          await page.locator('#market-checkout-billing-firstName[aria-invalid="true"]').waitFor({
            timeout: 10000,
          });
          assertEqual(posts.length, 0, 'nothing was sent with the billing form empty');

          const billing = address('DE');
          await first.fill(billing.firstName);
          await page.locator('#market-checkout-billing-lastName').fill(billing.lastName);
          await page.locator('#market-checkout-billing-phone').fill(billing.phone);
          await page.locator('#market-checkout-billing-country').selectOption(billing.country);
          await page.locator('#market-checkout-billing-city').fill(billing.city);
          await page.locator('#market-checkout-billing-line1').fill(billing.line1);
          await page.locator('#market-checkout-billing-postalCode').fill(billing.postalCode);
          await page.locator('#market-checkout-billing-postalCode').blur();
          await waitQuoted(page);
          await placeButton(page).click();

          const publicId = await waitForOrderPage(page);
          assertEqual(posts.length, 1, 'one request once the form is complete');
          assertEqual(
            JSON.parse(posts[0].body).billingInfo?.firstName,
            billing.firstName,
            'the request carries the billing details',
          );
          const stored = JSON.stringify(await panelOrder(admin, publicId));
          assert(stored.includes(billing.line1), 'the panel order holds the billing address');
          assert(held.held.length === 1, 'the buyer went to the gateway');
          await ctx.close();
        },
      );

      // ---- OFF, and the provider names PHONE ----------------------------------------------------------------------------------------
      // The fake provider has no required-buyer-field setting, so the one thing a stock provider cannot do here (name PHONE in the quote) is
      // put into the answers of the real quote route in the browser; what the page then asks for, and what it sends, is real.
      await withSettingsAndWait(
        admin,
        { billingInfoMode: 'OFF' },
        { billingInfoMode: 'OPTIONAL' },
        async () => {
          const other = await payBuyer(buyer, admin, 'phone');
          const ctx = await signedIn(browser, other);
          const page = await ctx.page();
          const quoteBodies = [];

          await page.route(`${env.url}${MARKET_API}/checkout/quote`, async (route) => {
            quoteBodies.push(JSON.parse(route.request().postData() || '{}'));
            const response = await route.fetch();
            const json = await response.json();

            if (json.quote) json.quote.requiredBuyerFields = ['PHONE'];
            await route.fulfill({ response, json });
          });

          await addFromCard(page, env, vip, vip.name);
          await openCheckout(page, env);
          await waitQuoted(page);

          const phone = page.locator('#market-checkout-billing-phone');
          await phone.waitFor({ timeout: 30000 });
          assertEqual(
            await page
              .locator('[id^="market-checkout-billing-"]')
              .evaluateAll((nodes) =>
                nodes
                  .filter(
                    (n) =>
                      ['INPUT', 'SELECT', 'TEXTAREA'].includes(n.tagName) && n.type !== 'radio',
                  )
                  .map((n) => n.id)
                  .sort(),
              )
              .then((ids) => ids.join(',')),
            'market-checkout-billing-phone',
            'only the phone is asked',
          );

          await phone.fill('+905551234567');
          await phone.blur();
          await waitQuoted(page);
          await page.waitForFunction(() => true);
          await sleep(1200);
          assert(
            quoteBodies.some((body) => body.billingInfo?.phone === '+905551234567'),
            'the next quote carries the phone the buyer typed',
          );
          await ctx.close();
        },
      );
    },
  },
];
