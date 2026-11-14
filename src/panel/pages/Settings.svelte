<MarketLayout area="settings" sections={sectionsFor('settings', user)} active={section}>
  <div class="settings-content vstack gap-3" lang={$currentLanguage?.code}>
    {#if data.error}
      <LoadError error={data.error} />
    {:else if Section}
      <Section
        settings={data.settings}
        ctx={data.ctx}
        extra={data.extra}
        extraError={data.extraError} />
    {:else}
      <div class="alert alert-info d-flex align-items-start mb-0" role="alert">
        <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
        <div>
          <h5 class="alert-heading mb-2">{$_('settings.pending.title')}</h5>
          <div>{$_('settings.pending.body')}</div>
        </div>
      </div>
      <PluginHook name="market:panel:settings:section:{section}" props={{ ctx: data.ctx }} />
    {/if}
  </div>
</MarketLayout>

<script module>
  import { api } from '@panomc/sdk/plugin-api';
  import { failureOf } from '../utils/api.js';
  import { loadContext } from '../utils/context.js';
  import { extraPathFor, resolveSection as knownSection } from '../utils/settings.js';
  import { settingsFeature } from '../navigation.js';
  import { featureOn } from '../utils/features.js';

  /** A section whose feature this build ships switched off opens as `general`. */
  const resolveSection = (value) => {
    const section = knownSection(value);
    return featureOn(settingsFeature(section)) ? section : 'general';
  };

  /**
   * GET /settings, GET /context and the section's own extra data (13 §17) in parallel.
   *
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const { parent } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.settings.title');

    const section = resolveSection(event.url.searchParams.get('section'));
    const extraPath = extraPathFor(section, event.url.searchParams);
    const extraPaths = extraPath ? [extraPath].flat() : [];

    const [settings, ctx, extra] = await Promise.all([
      api.panel.get({ path: '/settings', request: event }),
      loadContext(event),
      Promise.all(extraPaths.map((path) => api.panel.get({ path, request: event }))),
    ]);
    // one path: its answer; several: the list of answers (the first one decides success)
    const first = extra[0] ?? null;

    // Do NOT fall back to an empty settings object on failure: the sections would silently render
    // defaults that, if saved, overwrite the real config. Surface an explicit error state instead.
    const settingsFailure = failureOf(settings);
    if (settingsFailure) return { data: { section, ctx, error: settingsFailure } };

    const extraOk = first && failureOf(first) === null;

    return {
      data: {
        section,
        ctx,
        settings,
        extra: extraOk ? (Array.isArray(extraPath) ? extra : first) : null,
        extraError: extraPath && !extraOk ? failureOf(first) || 'NETWORK_ERROR' : null,
      },
    };
  }
</script>

<script>
  import { page } from '@panomc/sdk/svelte';
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PluginHook from '../components/PluginHook.svelte';
  import { SECTION_COMPONENTS } from '../components/settings/registry.js';
  import { sectionsFor } from '../navigation.js';

  let { data } = $props();

  const user = $derived($page.data?.user);
  const section = $derived(resolveSection(data.section));
  // The active section lives in the URL (?section=); every section link is a real link, so the
  // address bar and history always match what is shown. `general` is the default.
  const Section = $derived(SECTION_COMPONENTS[section] ?? null);
</script>

<style>
  .settings-content :global(label),
  .settings-content :global(.col-form-label),
  .settings-content :global(h1),
  .settings-content :global(h2),
  .settings-content :global(h3),
  .settings-content :global(h4),
  .settings-content :global(h5:not(.alert-heading)),
  .settings-content :global(h6),
  .settings-content :global(th[scope='col']),
  .settings-content :global(.settings-status-table th[scope='row']),
  .settings-content :global(.card-header > .row > div:first-child) {
    text-transform: capitalize;
  }

  .settings-content :global(.form-text) {
    text-transform: none;
  }
</style>
