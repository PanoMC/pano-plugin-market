<script module>
  export const view = { contract: 2 };
</script>

<div class="market-buyer-section card">
  <div class="market-buyer-section__body card-body">
    {#if user}
      <div class="d-flex flex-column align-items-center gap-2 text-center">
        <PlayerHead username={user.username} width={48} height={48} />
        <div>
          <div class="fw-semibold">{user.username}</div>
          {#if user.email}
            <div class="small text-body-secondary">{maskEmail(user.email)}</div>
          {/if}
        </div>
      </div>
    {:else}
      <div class="row g-3">
        <div class="col-md-6">
          <label class="market-buyer-section__label form-label" for={usernameId}>
            {$_('theme.checkout.minecraft-username')}
            <span class="text-danger" aria-hidden="true">*</span>
          </label>
          <input
            id={usernameId}
            class={['market-buyer-section__input', 'form-control', errors.username && 'is-invalid']}
            type="text"
            maxlength="32"
            autocomplete="username"
            autocapitalize="off"
            spellcheck="false"
            required
            aria-required="true"
            aria-invalid={errors.username ? 'true' : undefined}
            aria-describedby={errors.username ? `${usernameId}-error` : undefined}
            value={guest.username}
            oninput={(event) => onchange({ username: event.currentTarget.value })}
            onblur={() => onblur('username')} />
          {#if errors.username}
            <div class="invalid-feedback" id="{usernameId}-error">
              {$_(fieldErrorKey('username', errors.username))}
            </div>
          {/if}
        </div>
        <div class="col-md-6">
          <label class="market-buyer-section__email form-label" for={emailId}>
            {$_('theme.checkout.email')}
            <span class="text-danger" aria-hidden="true">*</span>
          </label>
          <input
            id={emailId}
            class={['market-buyer-section__input-2', 'form-control', errors.email && 'is-invalid']}
            type="email"
            maxlength="255"
            autocomplete="email"
            required
            aria-required="true"
            aria-invalid={errors.email ? 'true' : undefined}
            aria-describedby={errors.email ? `${emailId}-error` : undefined}
            value={guest.email}
            oninput={(event) => onchange({ email: event.currentTarget.value })}
            onblur={() => onblur('email')} />
          {#if errors.email}
            <div class="invalid-feedback" id="{emailId}-error">
              {$_(fieldErrorKey('email', errors.email))}
            </div>
          {/if}
        </div>
        <div class="col-12">
          <small class="text-body-secondary">
            {$_('theme.checkout.have-account')}
            <a href={loginHref}>{$_('theme.checkout.sign-in')}</a>
          </small>
        </div>
      </div>
    {/if}
  </div>
</div>

<script>
  import { PlayerHead } from '@panomc/sdk/components/theme';
  import { plugin } from '@panomc/sdk/controllers';
  import { fieldErrorKey, fieldId, maskEmail } from '../../lib/checkoutModel.js';

  const market = plugin('market');
  const { _ } = market;

  /**
   * user: the logged-in user ({username, email}) or null for a guest.
   * guest: {username, email} (draft); errors: {username?, email?} message codes.
   * onchange(patch): patch of guest; onblur(field): 'username' | 'email'.
   */
  let {
    user = null,
    guest = { username: '', email: '' },
    errors = {},
    loginHref = '/login',
    onchange = () => {},
    onblur = () => {},
  } = $props();

  const usernameId = fieldId('guest', 'username');
  const emailId = fieldId('guest', 'email');
</script>
