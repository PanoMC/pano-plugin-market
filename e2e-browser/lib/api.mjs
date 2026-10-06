// A small HTTP client of the instance with a cookie jar and the CSRF token (the shape of E2eClient in the Kotlin harness).
export class Api {
  constructor(baseUrl, label = 'anon') {
    this.baseUrl = baseUrl;
    this.label = label;
    this.cookies = new Map();
    this.csrfToken = null;
    this.userId = null;
  }

  cookieHeader() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  /** The session cookies as Playwright wants them (host-only, so they are valid on every port of 127.0.0.1). */
  playwrightCookies() {
    const host = new URL(this.baseUrl).hostname;
    return [...this.cookies].map(([name, value]) => ({ name, value, domain: host, path: '/' }));
  }

  async request(method, path, body, headers = {}) {
    const init = {
      method,
      headers: { Accept: 'application/json', ...headers },
      redirect: 'manual',
    };

    if (this.cookies.size) init.headers.Cookie = this.cookieHeader();
    if (this.csrfToken && !('X-CSRF-Token' in init.headers))
      init.headers['X-CSRF-Token'] = this.csrfToken;

    if (body instanceof FormData) {
      init.body = body;
    } else if (body !== undefined && body !== null) {
      init.body = JSON.stringify(body);
      init.headers['Content-Type'] = 'application/json';
    }

    const res = await fetch(this.baseUrl + path, init);

    for (const raw of res.headers.getSetCookie?.() ?? []) {
      const pair = raw.split(';')[0];
      const at = pair.indexOf('=');
      const name = pair.slice(0, at).trim();
      const value = pair.slice(at + 1).trim();

      if (!name) continue;
      if (value) this.cookies.set(name, value);
      else this.cookies.delete(name);
    }

    const text = await res.text();
    let json = null;

    try {
      json = JSON.parse(text);
    } catch {
      /* not JSON */
    }

    return { status: res.status, json, text, error: json?.error ?? null };
  }

  get(path, headers) {
    return this.request('GET', path, undefined, headers);
  }

  post(path, body = {}, headers) {
    return this.request('POST', path, body, headers);
  }

  put(path, body = {}, headers) {
    return this.request('PUT', path, body, headers);
  }

  delete(path, headers) {
    return this.request('DELETE', path, undefined, headers);
  }

  /** A multipart form of text fields (the product and category forms of the panel). */
  multipart(method, path, fields) {
    const form = new FormData();
    for (const [k, v] of Object.entries(fields)) form.append(k, String(v));
    return this.request(method, path, form);
  }

  async login(usernameOrEmail, password, panel = false) {
    const res = await this.post('/api/auth/login', {
      usernameOrEmail,
      password,
      ...(panel ? { panel: true } : {}),
    });

    if (res.json?.csrfToken) this.csrfToken = res.json.csrfToken;

    return res;
  }
}

export function must(res, what) {
  if (res.status < 200 || res.status > 299) {
    throw new Error(`${what}: HTTP ${res.status} ${res.error ?? ''} ${res.text.slice(0, 300)}`);
  }

  return res;
}
