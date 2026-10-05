// Safe site-relative redirect target (15 §4.2 sanitizeReturnTo, mirrored for the plugin).
const AUTH_PATHS = [
  '/login',
  '/register',
  '/reset-password',
  '/renew-password',
  '/activate',
  '/activate-new-email',
];
const BASE = 'http://pano.invalid';

/**
 * Returns the sanitised `pathname + search + hash` or `fallback`.
 * Rejects non-strings, > 2048 chars, "//x", "/\x", backslashes, control characters, other origins
 * and the auth routes themselves (loop guard).
 */
export function sanitizeRedirectTarget(value, fallback = '/') {
  if (typeof value !== 'string' || value.length < 1 || value.length > 2048) return fallback;
  if (value[0] !== '/' || value[1] === '/' || value[1] === '\\') return fallback;

  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (value[i] === '\\' || code <= 0x1f || code === 0x7f) return fallback;
  }

  let url;
  try {
    url = new URL(value, BASE);
  } catch (e) {
    return fallback;
  }

  if (url.origin !== BASE) return fallback;

  for (const p of AUTH_PATHS) {
    if (url.pathname === p || url.pathname.startsWith(`${p}/`)) return fallback;
  }

  return url.pathname + url.search + url.hash;
}

/** "/login" or "/login?redirect=<encoded>". */
export function buildLoginPath(returnTo) {
  const target = sanitizeRedirectTarget(returnTo);

  return target === '/' ? '/login' : `/login?redirect=${encodeURIComponent(target)}`;
}

/** "/register" or "/register?redirect=<encoded>". */
export function buildRegisterPath(returnTo) {
  const target = sanitizeRedirectTarget(returnTo);

  return target === '/' ? '/register' : `/register?redirect=${encodeURIComponent(target)}`;
}
