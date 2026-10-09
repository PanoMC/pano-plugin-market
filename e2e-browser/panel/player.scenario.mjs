// Scenario 72 of 13 section 25.4: the market block on the player page. The panel the platform serves has the player menu API (X-9), so the page shows a
// "Market" tab with the summary (the overview tab has no market card); a failing summary request shows the load error in the tab and never breaks the page.
import { PANEL_MARKET_API } from '../lib/api.mjs';
import { newContext } from '../lib/browser.mjs';
import { assert, assertEqual, hydrated } from '../lib/ui.mjs';
import { provoked } from './lib/panel.mjs';

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
      'player page: the Market tab shows the summary and the overview tab has no market card, a failing summary request does not break the page',
    async run(ctx) {
      const { env, admin } = ctx;
      const buyer = await ctx.buyer('p72');
      const summary = new RegExp(
        `${PANEL_MARKET_API.replaceAll('/', '\\/')}\\/players\\/[^/]+\\/summary`,
      );

      // ---------------------------------------------------------------- the panel the platform serves (it has the player menu API, X-9)
      {
        const { pc, page } = await signedPage(ctx.browser, admin);
        const base = `${env.url}/panel`;

        await openPlayer(page, base, buyer.username);
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
        await openPlayer(page, base, buyer.username);
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
        provoked(pc, mark, /status of 5\d\d/, 'PANEL-72 failing summary');
        await page.getByRole('link', { name: 'Sessions' }).first().waitFor({ timeout: 15000 });
        await page.unroute(summary);
        pc.expectNoErrors('PANEL-72 failing summary');
        await pc.close();
      }
    },
  },
];
