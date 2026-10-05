// Scroll-to-anchor that fires once per page instance. The order detail page re-assigns `detail` on
// every refresh (mutations, 15 s auto-refresh), so an effect that reads it must not scroll again.

/**
 * @param {string} anchor id of the target element, without '#'
 * @param {(id: string) => { scrollIntoView: () => void } | null | undefined} find
 * @returns {(hash: string, ready: boolean) => boolean} true only on the call that scrolled
 */
export function createAnchorScroller(anchor, find) {
  let done = false;
  return (hash, ready) => {
    if (done || !ready || hash !== `#${anchor}`) return false;
    const element = find(anchor);
    if (!element) return false;
    done = true;
    element.scrollIntoView();
    return true;
  };
}
