# ADR-0002: Redirect with 302, not 301

- **Status:** Accepted
- **Date:** 2026-11-20
- **Scope:** `shortener-service`, `RedirectController`

## Context
`GET /{code}` sends the browser to the original URL. The status code decides whether later clicks
ever reach the service:
- **301 (Moved Permanently)** is cached by browsers indefinitely. After the first visit, the browser
  goes straight to the destination.
- **302 (Found)** is not cached by default, so every click comes back to the service.

## Decision
- Use **302** with `Cache-Control: max-age=0, private`.
- Make it configurable through `shortener.redirect.status` (301–308), so a deployment that wants 301
  for SEO-heavy, never-changing links can choose it.
- Unknown and deleted codes return `404` Problem Details.

## Consequences
- ✅ **Analytics are complete:** every click is seen and counted (ADR-0004).
- ✅ **Deletes take effect** for everyone, including people who visited before. With 301 a deleted
  link would keep working in their browsers forever, and so would expired links later.
- ✅ Rate limits and future safety checks (such as a blocklist) apply to every click.
- ⚠️ All clicks hit the service, so it carries more traffic. The two-level cache absorbs it
  (ADR-0003), and the redirect path does no database write (analytics are asynchronous).
- ⚠️ BF-1 (see `docs/known-issues.md`) is a server-side cache issue, not a browser-cache one. 302
  ensures that fixing it on the server is enough.
