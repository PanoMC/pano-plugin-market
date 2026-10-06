<ConfirmModal bind:this={confirm} />
<LegalVersionModal bind:this={versionModal} />

{#if loadError}
  <LoadError error={loadError} onRetry={reload} />
{:else}
  <div class="vstack gap-3">
    <div class="card">
      <div class="card-body">
        <SwitchRow
          id="setting-legalTextRequired"
          label={$_('settings.legal.required')}
          hint={$_('settings.legal.required-hint')}
          error={requiredError ? $_(fieldErrorKey(requiredError)) : ''}
          bind:checked={draft.legalTextRequired} />
      </div>
      <div class="card-footer d-flex justify-content-start">
        <button
          type="button"
          class="btn btn-secondary"
          onclick={saveRequired}
          disabled={savingRequired || !requiredDirty || blocked}>
          {#if savingRequired}
            <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
          {/if}
          {$_('common.save')}
        </button>
      </div>
    </div>

    <div class="card">
      <div class="card-body">
        <SettingRow
          id="legal-locale"
          label={$_('settings.legal.locale')}
          error={editorMessage('locale')}>
          <select
            id="legal-locale"
            class="form-select"
            class:is-invalid={editorShown.locale}
            value={locale}
            onchange={onLocaleChange}>
            {#each locales as option (option.code)}
              <option value={option.code}>{option.name}</option>
            {/each}
          </select>
        </SettingRow>

        <SettingRow
          id="legal-title"
          label={$_('settings.legal.title')}
          error={editorMessage('title')}>
          <input
            id="legal-title"
            type="text"
            class="form-control"
            class:is-invalid={editorShown.title}
            autocomplete="off"
            placeholder={$_('settings.legal.title')}
            bind:value={editor.title} />
        </SettingRow>

        <div class="mb-0">
          <div class="mb-2">{$_('settings.legal.content')}</div>
          {#key editorKey}
            <ClientEditor bind:content={editor.content} />
          {/key}
          {#if editorShown.content}
            <div class="invalid-feedback d-block">{editorMessage('content')}</div>
          {/if}
        </div>
      </div>
      <div class="card-footer d-flex justify-content-start">
        <button type="button" class="btn btn-secondary" onclick={onPublish} disabled={publishing}>
          {#if publishing}
            <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
          {/if}
          {$_('settings.legal.publish')}
        </button>
      </div>
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('settings.legal.versions.title', { values: { count: versions.length } })}
        </div>
      </CardHeader>

      {#if versions.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover">
            <thead>
              <tr>
                <th scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.legal.versions.version')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.legal.versions.locale')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.legal.versions.title-column')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.legal.versions.status')}
                </th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('settings.legal.versions.created')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each versions as version (version.id)}
                <tr>
                  <th scope="row" class="align-middle text-center">
                    <div class="dropdown position-static">
                      <button
                        type="button"
                        class="btn btn-link"
                        data-bs-toggle="dropdown"
                        aria-expanded="false"
                        title={$_('common.actions')}
                        aria-label={$_('common.actions')}>
                        <span class="fas fa-ellipsis-v"></span>
                      </button>
                      <div class="dropdown-menu dropdown-menu-start">
                        <button
                          type="button"
                          class="dropdown-item"
                          onclick={() => versionModal?.open(version)}>
                          <i class="fas fa-eye me-2" aria-hidden="true"></i>
                          {$_('common.view')}
                        </button>
                      </div>
                    </div>
                  </th>
                  <td class="align-middle">{version.version}</td>
                  <td class="align-middle">{localeName(version.locale)}</td>
                  <td class="align-middle">{version.title}</td>
                  <td class="align-middle">
                    {#if version.active}
                      <span class="badge text-bg-success">{$_('common.active')}</span>
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">
                    <DateComponent time={version.createdAt} />
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  </div>
{/if}

<script>
  import { untrack } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import {
    CardHeader,
    Date as DateComponent,
    NoContent,
  } from '@panomc/sdk/components/panel';
  import { Languages, currentLanguage } from '@panomc/sdk/utils/language';
  import { _, showSuccessToast } from '../../../i18n';
  import ClientEditor from '../ClientEditor.svelte';
  import ConfirmModal from '../ConfirmModal.svelte';
  import LoadError from '../LoadError.svelte';
  import LegalVersionModal from '../modals/LegalVersionModal.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, saveSection } from './save.js';
  import { call, marketPath } from '../../utils/api.js';
  import { toastError } from '../../utils/toast.js';
  import {
    SECTION_KEYS,
    activeTextFor,
    buildLegalBody,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    languageOptions,
    legalDraftDirty,
    legalRequiredBlocked,
    seedValues,
    sortVersions,
    validateLegalDraft,
  } from '../../utils/settings.js';

  // settings = GET /settings, extra = GET /settings/legal ({ texts[] }), null when that request failed.
  let { settings: initial = {}, extra: initialExtra = null, extraError = null } = $props();

  const KEYS = SECTION_KEYS.legal;
  const start = untrack(() => initial ?? {});
  const startTexts = untrack(() => initialExtra?.texts ?? null);

  let confirm = $state(null);
  let versionModal = $state(null);
  let settings = $state.raw(start);
  let texts = $state.raw(startTexts);
  let reloadError = $state(null);
  let draft = $state(seedValues(start, KEYS));
  let savingRequired = $state(false);
  let publishing = $state(false);
  let submitted = $state(false);

  const locales = $derived(languageOptions($Languages));
  const defaultLocale = untrack(() => {
    const options = languageOptions($Languages);
    const current = $currentLanguage?.code;
    return options.find((o) => o.code === current)?.code ?? options[0]?.code ?? current ?? 'en-US';
  });
  let locale = $state(defaultLocale);
  let editor = $state({ title: '', content: '' });
  // What the editor was pre-filled with; the content part settles once Tiptap has normalised the HTML.
  let baselineTitle = $state.raw('');
  let baselineContent = $state.raw('');
  let settling = $state(true);
  let editorKey = $state(0);

  const loadError = $derived(
    texts === null ? (reloadError ?? extraError ?? 'NETWORK_ERROR') : null,
  );
  const versions = $derived(sortVersions(texts ?? []));
  const requiredDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, KEYS)));
  const blocked = $derived(legalRequiredBlocked(draft.legalTextRequired, texts ?? []));
  const requiredError = $derived(blocked ? 'NO_ACTIVE_TEXT' : null);
  const editorErrors = $derived(validateLegalDraft({ locale, ...editor }));
  const editorShown = $derived(submitted ? editorErrors : {});
  const editorDirty = $derived(
    legalDraftDirty(editor, { title: baselineTitle, content: baselineContent }),
  );

  const editorMessage = (key) => (editorShown[key] ? $_(fieldErrorKey(editorShown[key])) : '');
  const localeName = (code) => locales.find((o) => o.code === code)?.name ?? code;

  // Pre-fills the editor with the active text of a locale. The editor is re-mounted so it starts from it.
  function fill(nextLocale, list = texts ?? []) {
    const active = activeTextFor(list, nextLocale);
    locale = nextLocale;
    editor.title = active?.title ?? '';
    editor.content = active?.content ?? '';
    baselineTitle = editor.title;
    baselineContent = editor.content;
    submitted = false;
    settling = true;
    editorKey += 1;
  }

  if (startTexts) untrack(() => fill(defaultLocale, startTexts));

  $effect(() => {
    // The first content change within a moment of mounting is the editor normalising its input.
    const current = editor.content;
    if (settling) baselineContent = current;
  });

  $effect(() => {
    editorKey;
    const timer = setTimeout(() => (settling = false), 400);
    return () => clearTimeout(timer);
  });

  async function fetchTexts() {
    const result = await call(ApiUtil.get({ path: marketPath('/settings/legal') }));
    return result.ok ? { texts: result.body.texts ?? [] } : { error: result.error };
  }

  async function reload() {
    const [fetched, nextSettings] = await Promise.all([fetchTexts(), fetchSettings()]);
    if (fetched.texts) {
      texts = fetched.texts;
      reloadError = null;
      if (nextSettings) settings = nextSettings;
      fill(locale, fetched.texts);
    } else {
      reloadError = fetched.error;
    }
  }

  function onLocaleChange(event) {
    const next = event.currentTarget.value;
    if (next === locale) return;
    if (!editorDirty) {
      fill(next);
      return;
    }
    // Keep the select on the current locale until the admin agrees to drop the edits.
    event.currentTarget.value = locale;
    confirm?.open({
      title: $_('settings.legal.confirm-switch.title'),
      description: $_('settings.legal.confirm-switch.description'),
      confirmLabel: $_('settings.legal.confirm-switch.cta'),
      onConfirm: () => fill(next),
    });
  }

  async function saveRequired() {
    if (savingRequired || blocked || !requiredDirty) return;
    savingRequired = true;
    try {
      const result = await saveSection({ baseline: settings, values: draft, keys: KEYS });
      if (result.status === 'saved') {
        const next = (await fetchSettings()) ?? { ...settings, ...result.sent };
        settings = next;
        draft = seedValues(next, KEYS);
      }
    } finally {
      savingRequired = false;
    }
  }

  async function publish() {
    publishing = true;
    try {
      const result = await call(
        ApiUtil.post({
          path: marketPath('/settings/legal'),
          body: buildLegalBody({ locale, ...editor }),
        }),
      );
      if (!result.ok) {
        toastError($_, result);
        return;
      }
      showSuccessToast($_('settings.legal.toast-published'));
      const fetched = await fetchTexts();
      if (fetched.texts) {
        texts = fetched.texts;
        fill(locale, fetched.texts);
      }
    } finally {
      publishing = false;
    }
  }

  function onPublish() {
    if (publishing) return;
    submitted = true;
    if (Object.keys(editorErrors).length > 0) return;
    confirm?.open({
      title: $_('settings.legal.confirm-publish.title'),
      description: $_('settings.legal.confirm-publish.description'),
      confirmLabel: $_('settings.legal.publish'),
      onConfirm: publish,
    });
  }
</script>
