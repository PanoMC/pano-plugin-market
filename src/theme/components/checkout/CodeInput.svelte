<div>
  <label class="form-label" for={id}>{label}</label>

  {#if isApplied}
    <div class="d-flex align-items-center gap-2">
      <span class="badge text-bg-success text-break">{info.code ?? applied}</span>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        aria-label={$_('theme.checkout.code-remove', { values: { code: info.code ?? applied } })}
        {disabled}
        onclick={remove}>
        <i class="fa-solid fa-xmark" aria-hidden="true"></i>
      </button>
    </div>
  {:else}
    <div class="input-group">
      <input
        {id}
        class={['form-control', invalid && 'is-invalid']}
        type="text"
        maxlength="64"
        autocomplete="off"
        autocapitalize="off"
        spellcheck="false"
        aria-invalid={invalid ? 'true' : undefined}
        aria-describedby={invalid || locked ? `${id}-feedback` : undefined}
        disabled={disabled || locked}
        value={text}
        oninput={(event) => (typed = event.currentTarget.value)}
        onkeydown={(event) => {
          if (event.key !== 'Enter') return;
          event.preventDefault();
          apply();
        }} />
      <button
        type="button"
        class="btn btn-outline-primary"
        disabled={disabled || locked || busy || text.trim() === ''}
        onclick={apply}>
        {#if busy}
          <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
        {/if}
        {$_('theme.checkout.code-apply')}
      </button>
      {#if invalid}
        <div class="invalid-feedback" id="{id}-feedback">{$_(info.messageKey)}</div>
      {/if}
    </div>
    {#if locked}
      <div class="form-text text-danger" id="{id}-feedback" role="status">
        {$_('theme.checkout.code-locked-wait', { values: { seconds } })}
      </div>
    {/if}
  {/if}
</div>

<script>
  import { _ } from '../../../i18n.js';
  import { now } from '../../stores/clock.js';

  /**
   * One code field (coupon, creator code). `applied` = the code in the draft; `info` = codeState() of
   * lib/summaryModel.js (`status` APPLIED | INVALID | LOCKED | IDLE, `messageKey`, `until` = epoch ms a lock
   * ends); `busy` = a quote is running. onapply(code): the trimmed text, sent as typed (nothing is upper-cased);
   * onremove().
   */
  let {
    id,
    label,
    applied = '',
    info = { status: 'IDLE' },
    busy = false,
    disabled = false,
    onapply = () => {},
    onremove = () => {},
  } = $props();

  // what the buyer typed; until then the applied code (restored draft, server cart) shows in the field
  let typed = $state(null);

  const text = $derived(typed ?? applied);

  const isApplied = $derived(info.status === 'APPLIED' && applied !== '');
  const invalid = $derived(info.status === 'INVALID');
  const seconds = $derived(
    info.status === 'LOCKED' ? Math.max(0, Math.ceil((Number(info.until) - $now) / 1000)) : 0,
  );
  const locked = $derived(info.status === 'LOCKED' && seconds > 0);

  function remove() {
    typed = null;
    onremove();
  }

  function apply() {
    const code = text.trim();

    if (code !== '') onapply(code);
  }
</script>
