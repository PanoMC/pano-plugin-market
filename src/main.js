import { PanoPlugin } from '@panomc/sdk';
import { registerPanel } from './panel/register.js';
import { registerTheme } from './theme/register.js';

// Entry point: only dispatches to the register.js of the active side (D-WB2).
// Lane-scoped check builds replace the register.js of the other side with an empty stub
// (rollup.config.js, MARKET_UI_SIDE), so one side never compiles the other side's files.
export default class PanoMarketPlugin extends PanoPlugin {
  onLoad() {
    if (this.pano.isPanel) {
      registerPanel(this.pano);
    } else {
      registerTheme(this.pano);
    }
  }

  onContextUpdate(ctx) {}

  onUnload() {}
}
