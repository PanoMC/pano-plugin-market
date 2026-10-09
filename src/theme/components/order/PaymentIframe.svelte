{#if failed}
  <div class="market-payment-iframe market-payment-iframe__alert alert alert-warning mb-0" role="alert">
    {$_('theme.order.payment-ui-error')}
  </div>
{:else if !ready}
  <div
    class="market-payment-iframe d-flex align-items-center gap-2 text-body-secondary"
    role="status">
    <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
    <span>{$_('theme.order.payment-ui-loading')}</span>
  </div>
{:else}
  <iframe
    bind:this={frame}
    class="market-payment-iframe w-100 border-0 rounded"
    src={iframe.url}
    height={iframeHeight(iframe.heightPx)}
    allow={iframeAllow(iframe.allow)}
    referrerpolicy="strict-origin-when-cross-origin"
    {title}
    onload={resize}></iframe>
{/if}

<script>
  import { onMount, untrack } from 'svelte';
  import { plugin } from '@panomc/sdk/controllers';
  import {
    iframeAllow,
    iframeHeight,
    isHttpsUrl,
    loadScripts,
    resizerOptions,
    scriptsPlan,
  } from '../../lib/paymentPanel.js';

  const market = plugin('market');
  const { _ } = market;

  /**
   * Gateway page in an iframe (14 §11.4 `IFRAME`). The scripts of `iframe.scripts` (https only, once each) load
   * before the iframe `src` is set; a script that fails (or an entry that is not https) shows the error through
   * `onerror()` and the panel offers the method picker. `title` = the payment method label.
   */
  let { iframe, title = '', onerror = () => {} } = $props();

  let ready = $state(false);
  let failed = $state(false);
  let frame = $state();
  let resized = false;

  function fail() {
    failed = true;
    onerror();
  }

  onMount(() => {
    let alive = true;
    const current = untrack(() => iframe);
    const plan = scriptsPlan(current?.scripts);

    if (!isHttpsUrl(current?.url) || !plan.valid) {
      fail();
      return undefined;
    }

    loadScripts(plan.urls).then(
      () => {
        if (alive) ready = true;
      },
      () => {
        if (alive) fail();
      },
    );

    return () => {
      alive = false;
    };
  });

  // PayTR style resizer: after the scripts loaded and the frame exists
  function resize() {
    const options = resizerOptions(iframe);

    if (resized || !options || !frame || typeof window.iFrameResize !== 'function') return;

    resized = true;

    try {
      window.iFrameResize(options, frame);
    } catch (e) {
      // the iframe keeps its fixed height
    }
  }
</script>
