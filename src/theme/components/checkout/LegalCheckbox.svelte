<div>
  <div class="form-check">
    <input
      id={checkId}
      class={['form-check-input', invalid && 'is-invalid']}
      type="checkbox"
      {checked}
      {disabled}
      aria-required={required ? 'true' : undefined}
      aria-invalid={invalid ? 'true' : undefined}
      aria-describedby={describedBy}
      onchange={(event) => onchange(event.currentTarget.checked)} />
    <label class="form-check-label" for={checkId}>
      {label.before}<button
        type="button"
        class="btn btn-link p-0 align-baseline"
        onclick={() => modal?.show()}>{legal.title}</button
      >{label.after}
      {#if required}
        <span class="text-danger" aria-hidden="true">*</span>
      {/if}
    </label>
    {#if invalid}
      <div class="invalid-feedback d-block" id="{checkId}-error">
        {$_('theme.checkout.legal-required')}
      </div>
    {/if}
  </div>
  {#if updated}
    <div class="form-text text-warning-emphasis" id="{checkId}-updated" role="status">
      {$_('theme.checkout.legal-updated')}
    </div>
  {/if}
</div>

<LegalModal bind:this={modal} title={legal.title} content={legal.content} />

<script>
  import { _ } from '../../../i18n.js';
  import { LEGAL_CHECK_ID, splitLegalLabel, TITLE_SENTINEL } from '../../lib/paymentModel.js';
  import LegalModal from './LegalModal.svelte';

  /**
   * "I have read and accept the [title]": `legal` = checkout config legal ({ required, id, title, content });
   * the title opens LegalModal. `checked` is never stored (14 §10.2). `invalid`: shown after a submit without it
   * or LEGAL_ACCEPTANCE_REQUIRED; `updated`: the text changed while the buyer was on the page (re-ticked).
   * onchange(checked).
   */
  let {
    legal,
    checked = false,
    invalid = false,
    updated = false,
    disabled = false,
    onchange = () => {},
  } = $props();

  const checkId = LEGAL_CHECK_ID;

  const required = $derived(legal?.required === true);
  const label = $derived(
    splitLegalLabel($_('theme.checkout.legal-accept', { values: { title: TITLE_SENTINEL } })),
  );
  const describedBy = $derived(
    [invalid ? `${checkId}-error` : '', updated ? `${checkId}-updated` : '']
      .filter(Boolean)
      .join(' ') || undefined,
  );

  let modal = $state();
</script>
