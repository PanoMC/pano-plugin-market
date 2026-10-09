// Classes that come from data (a status table, a server-chosen icon). The build and a theme read the possible values as
// literals: a class expression in a view calls one of these, and each one keeps its default as a literal here, so the
// style lint (`pano-plugin check --styles badge`) and the fallback stylesheet see the class they fall back to. A value that
// is empty falls back; any other value passes through unchanged.

/** A badge colour class (`text-bg-success`, ...); `text-bg-secondary` when there is none. */
export const badgeClass = (value, fallback = 'text-bg-secondary') => value || fallback;

/** An alert colour class (`alert-danger`, ...); `alert-info` when there is none. */
export const alertClass = (value, fallback = 'alert-info') => value || fallback;

/** A Font Awesome icon class (`fa-solid fa-coins`, an admin-chosen `fa-crown`); a plain circle when there is none. */
export const iconClass = (value, fallback = 'fa-solid fa-circle') => value || fallback;

/** A text colour class (`text-warning`, ...); the muted body colour when there is none. */
export const toneClass = (value, fallback = 'text-body-secondary') => value || fallback;
