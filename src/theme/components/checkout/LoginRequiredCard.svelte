<div class="market-login-required-card card text-center" role="status">
  <div class="market-login-required-card__body card-body py-5">
    <i class="fa-solid fa-user-lock fa-3x text-body-secondary mb-3" aria-hidden="true"></i>
    <p class="mb-3">{$_('theme.checkout.login-required')}</p>
    <div class="d-flex flex-wrap justify-content-center gap-2">
      <a class="market-login-required-card__action btn btn-primary" href={loginHref}>
        <i class="fa-solid fa-right-to-bracket me-2" aria-hidden="true"></i>{$_(
          'theme.checkout.sign-in',
        )}
      </a>
      <a class="market-login-required-card__register btn btn-outline-primary" href={registerHref}>
        <i class="fa-solid fa-user-plus me-2" aria-hidden="true"></i>{$_('theme.checkout.register')}
      </a>
    </div>
    {#if showReturnHint}
      <p class="form-text mt-3 mb-0">{$_('theme.checkout.login-return-hint')}</p>
    {/if}
  </div>
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const { _ } = market;

  /**
   * loginHref / registerHref: from the market/host controller, loginUrl / registerUrl('/store/checkout').
   * showReturnHint: true when the host cannot return the buyer to the checkout (no 'login-return-url').
   */
  let { loginHref = '/login', registerHref = '/register', showReturnHint = false } = $props();
</script>
