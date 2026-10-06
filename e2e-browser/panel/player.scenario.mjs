// Scenario 72 of 13 section 25.4: the market block on the player page. A host with the player menu API (X-9) shows a "Market" tab with the summary; a host
// without it (the panel bundled in the platform jar) shows the same summary as a card on the overview tab; a failing summary request never breaks the page.
// The X-9 half runs against the local panel-ui checkout (`e2e-instance.sh start --ui external:<theme>,<panel>`, MARKET_E2E_PANEL_URL); the other half
// against the panel the platform serves itself.
import { newContext } from '../lib/browser.mjs';
import { assert, assertEqual, hydrated } from '../lib/ui.mjs';
import { blockDevBounce, provoked, waitFor } from './lib/panel.mjs';

const STATS = ['Orders', 'Spent', 'Refunded', 'Credits'];

async function signedPage(browser, admin) {
  const pc = await newContext(browser, { viewport: 'desktop', cookies: admin.playwrightCookies() });

  return { pc, page: await pc.page() };
}

/** Opens a player page of `base` ("<url>/panel" for the bundled host, the dev server url otherwise) and waits for the host layout. */
async function openPlayer(page, base, username, tab = '') {
  await page.goto(`${base}/players/detail/${username}${tab}`, {
    waitUntil: 'domcontentloaded',
    timeout: 240000,
  });
  await hydrated(page);
  await page.getByRole('link', { name: 'Sessions' }).first().waitFor({ timeout: 60000 });
}

const statsShown = async (scope) => {
  for (const label of STATS)
    await scope.getByText(label, { exact: true }).first().waitFor({ timeout: 30000 });
};

export const scenarios = [
  {
    id: 'PANEL-72',
    title:
      'player page: the Market tab shows the summary with the X-9 panel, the card sits on the overview tab without it, a failing summary request does not break the page',
    async run(ctx) {
      const { env, admin } = ctx;
      const buyer = await ctx.buyer('p72');
      const summary = /\/api\/panel\/market\/players\/[^/]+\/summary/;

      // ---------------------------------------------------------------- without X-9: the bundled host
      {
        const { pc, page } = await signedPage(ctx.browser, admin);
        const base = `${env.url}/panel`;

        await openPlayer(page, base, buyer.username);
        const card = page
          .locator('.card')
          .filter({ has: page.getByText('Latest Orders') })
          .first();
        await card.waitFor({ timeout: 60000 });
        await statsShown(card);
        assertEqual(
          await page.locator(`a[href$="/players/detail/${buyer.username}/market"]`).count(),
          0,
          'PANEL-72: a host without the player menu API has no Market tab',
        );

        // a failing hook load (client navigation: sessions tab, then back to the overview) leaves the page alone and the card empty
        await openPlayer(page, base, buyer.username, '/sessions');
        await page.route(summary, (route) =>
          route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: '{"result":"error","error":"UNKNOWN"}',
          }),
        );
        const mark = pc.errors.length;
        await page.getByRole('link', { name: 'Details' }).first().click();
        await waitFor(
          'the refused summary request to be logged',
          async () => pc.errors.length > mark,
        );
        provoked(pc, mark, /status of 5\d\d/, 'PANEL-72 bundled host');
        await page.getByRole('link', { name: 'Sessions' }).first().waitFor({ timeout: 15000 });
        await page.waitForTimeout(500);
        assertEqual(
          await page.getByText('Latest Orders').count(),
          0,
          'PANEL-72: a failing summary leaves no card (and no broken page)',
        );
        assert(
          (await page.locator('body').innerText()).includes(buyer.username),
          'PANEL-72: the player page still shows the player',
        );
        await page.unroute(summary);
        pc.expectNoErrors('PANEL-72 bundled host');
        await pc.close();
      }

      // ---------------------------------------------------------------- with X-9: the local panel-ui checkout
      if (!env.panelUrl)
        throw new Error(
          'PANEL-72: the X-9 half needs the panel-ui checkout: start the instance with `e2e-instance.sh start --ui external:<themePort>,<panelPort>` (MARKET_E2E_PANEL_URL)',
        );
      {
        const { pc, page } = await signedPage(ctx.browser, admin);

        await blockDevBounce(pc, env);
        await openPlayer(page, `${env.panelUrl}/panel`, buyer.username);
        const tab = page.locator(`a[href$="/players/detail/${buyer.username}/market"]`).first();
        await tab.waitFor({ timeout: 60000 });
        assert((await tab.innerText()).includes('Market'), 'PANEL-72: the tab is called Market');
        assertEqual(
          await page.getByText('Latest Orders').count(),
          0,
          'PANEL-72: with the tab the overview tab has no market card',
        );
        await tab.click();
        await statsShown(page.locator('main, body').first());
        await page
          .getByText(/\d+ Latest Orders/)
          .first()
          .waitFor({ timeout: 30000 });

        // the summary request fails: the tab shows the load error, the player layout (tabs) stays
        await openPlayer(page, `${env.panelUrl}/panel`, buyer.username);
        await page.route(summary, (route) =>
          route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: '{"result":"error","error":"UNKNOWN"}',
          }),
        );
        const mark = pc.errors.length;
        await page.locator(`a[href$="/players/detail/${buyer.username}/market"]`).first().click();
        await page.getByText('Could Not Load Data').first().waitFor({ timeout: 30000 });
        provoked(pc, mark, /status of 5\d\d/, 'PANEL-72 X-9 host');
        await page.getByRole('link', { name: 'Sessions' }).first().waitFor({ timeout: 15000 });
        await page.unroute(summary);
        pc.expectNoErrors('PANEL-72 X-9 host');
        await pc.close();
      }
    },
  },
];
