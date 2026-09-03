# Security Audit — SchneaggchatV3server

Date: 2026-09-01
Scope: full server codebase (`com.lerchenflo.schneaggchatv3server`), Spring Security config, JWT/auth,
rate limiting, media endpoints, Docker/compose deployment, config files.
Method: manual review guided by the `security-review` skill (Kotlin/Spring guide added) + project architecture skill.

Findings sorted worst-first. Each has a confidence level. "Needs verification" items depend on your
deployment/network setup, which isn't visible in the repo.

---

## CRITICAL

### C-1 — Actuator auth bypass when `PROMETHEUS_KEY` is empty → heap dump leaks every secret  ✅ FIXED (2026-09-01)
> Resolved by removing the entire actuator/Prometheus/micrometer stack: dropped the deps in
> `build.gradle.kts`, the `management.*` + `prometheus.key` lines in `application.properties`, the
> actuator guard in `JwtAuthFilter`, the `/actuator/**` permitAll in `SecurityConfig`, and
> `PROMETHEUS_KEY` from `.env.example` and both compose files. No metrics endpoint is exposed anymore.

- **Location**: `core/security/JwtAuthFilter.kt:28-34`, `application.properties` (`prometheus.key`, `management.endpoints.web.exposure.include=*`), `.env.example` (`PROMETHEUS_KEY=`)
- **Confidence**: High (logic), conditional on empty key — and empty is the shipped default in `.env.example`.
- **Issue**: The actuator guard is:
  ```kotlin
  val providedKey = request.getHeader("Authorization")?.removePrefix("Bearer ")
  if (providedKey == null || providedKey != prometheusKey) { 401; return }
  ```
  If `PROMETHEUS_KEY` is unset/empty (the `.env.example` default), `prometheusKey == ""`. An attacker who
  sends header `Authorization: Bearer ` (nothing after the space) produces `providedKey == ""`, so
  `"" != ""` is false → the check passes and all actuator endpoints are reachable.
- **Impact**: `management.endpoints.web.exposure.include=*` exposes everything. `env` is disabled, but
  `heapdump` and `threaddump` are **enabled** (their `access=none` lines are commented out). A heap dump
  leaks `JWT_SECRET` (forge any user's tokens), the Mongo connection string + password, the Gmail app
  password, and every in-flight message. Full server + data compromise.
- **Fix**:
  1. Set a strong random `PROMETHEUS_KEY` in every environment and refuse to boot on empty (fail fast).
  2. `management.endpoints.web.exposure.include=health,prometheus` (allowlist, not `*`).
  3. Explicitly disable heapdump/threaddump: `management.endpoint.heapdump.access=none`, `management.endpoint.threaddump.access=none`.
  4. Reject a blank bearer key regardless of config; use `MessageDigest.isEqual`/constant-time compare.

### C-2 — mongo-express DB admin GUI exposed with authentication disabled
- **Location**: `docker-compose.yml` and `server_docker/docker-compose.yml` — `schneaggchat_db_gui` service (`ME_CONFIG_BASICAUTH: false`, `ports: "8081:8081"`, prod has `restart: unless-stopped`)
- **Confidence**: High (config). Impact severity depends on whether host port 8081 is reachable (see NV-1).
- **Issue**: mongo-express runs with basic auth turned off and its port published to the Docker host. It
  connects to Mongo as **root**. Anyone who can reach `http://<host>:8081` gets a full web admin panel over
  the entire database — no credentials required.
- **Impact**: Read/modify/delete all users (incl. BCrypt password hashes), all messages, refresh tokens,
  push tokens. Complete data breach and takeover, bypassing every application-layer control.
- **Fix**: Don't publish 8081 to the host (drop the `ports:` mapping — reach it via SSH tunnel / internal
  network only), enable `ME_CONFIG_BASICAUTH: true` with strong credentials, and firewall the port. Same
  for the raw Mongo port (see M-1). Ideally omit mongo-express from the production stack entirely.

---

## HIGH

### H-1 — Rate-limit / brute-force bypass via spoofable client IP (`RATE_LIMIT_TRUSTED_PROXIES` is dead config)
- **Location**: `core/security/ratelimit/ClientIpResolver.kt:8-16`, used by `RateLimitFilter.kt:35` and `AuthController.kt:103`
- **Confidence**: High.
- **Issue**: `ClientIpResolver` returns `X-Real-IP` if present, else the first `X-Forwarded-For` entry,
  else `remoteAddr` — with **no trusted-proxy allowlist**. Both headers are fully attacker-controlled. The
  auth bucket is keyed `rl:auth-ip:$ip` (`RateLimitFilter.kt:40`), so an attacker rotates `X-Real-IP:
  <random>` on each request and every request looks like a brand-new IP. The documented
  `RATE_LIMIT_TRUSTED_PROXIES` env var (present in `server_docker/docker-compose.yml` and `.env.example`)
  is **never read anywhere in the code** — grep confirms zero references.
- **Impact**: The 10/min auth limit (and the 100/min IP limit) is defeated → unlimited password brute-force
  against `/auth/login`, unlimited password-reset/delete email triggering, and forged IPs in all audit logs
  (`AppLogger`, `LoggingService`, `GlobalExceptionHandler`).
- **Fix**: Only trust `X-Forwarded-For`/`X-Real-IP` when `remoteAddr` is in the configured
  `RATE_LIMIT_TRUSTED_PROXIES` set; otherwise use `remoteAddr`. Actually wire that property into
  `ClientIpResolver`. Prefer the rightmost XFF entry that isn't a trusted proxy.

---

## MEDIUM

### M-1 — MongoDB port 27017 published to the Docker host
- **Location**: both compose files — `schneaggchat_db` `ports: "27017:27017"`
- **Confidence**: High (config); real exposure depends on host firewall (NV-1).
- **Issue**: The database port is bound on the host. Auth is enabled (root user/pass), so this is weaker
  than C-2, but it still puts the DB directly on the network and invites credential-stuffing/brute force
  against Mongo. Containers already share `spring-mongo-network`, so the app does not need the host mapping.
- **Fix**: Remove the `27017:27017` mapping (and `8081:8081`, see C-2). Inter-container traffic works over
  the Docker network without host publishing.

### M-2 — 20 chars of the JWT signing secret are handed to every client
- **Location**: `core/security/JwtService.kt:20-24` (`getEncryptionKey() = jwtSecret.take(20)`), returned in `AuthService.TokenPair.encryptionKey` on every login/refresh
- **Confidence**: High.
- **Issue**: The same `JWT_SECRET` used as the HS256 signing key (`Keys.hmacShaKeyFor(jwtSecret.toByteArray())`)
  is truncated to its first 20 characters and sent, in plaintext, to every authenticated client as the FCM
  payload decryption key. Every client (and anyone who sees a login response) learns 20 bytes of the signing
  secret.
- **Impact**: Shrinks the brute-force space of the HMAC secret to whatever is left after the leaked 20 chars.
  If `JWT_SECRET` is short (the `.env.example` value is ~44 chars but is a placeholder people copy), the
  remaining entropy may be brute-forceable → token forgery for any user. Also fully couples FCM crypto to the
  auth secret, so you can't rotate one without breaking the other.
- **Fix**: Derive the FCM key from a **separate** secret (dedicated `FCM_ENCRYPTION_KEY` env), not from the
  JWT secret. Never expose any portion of the signing secret to clients.

### M-3 — APNs private key (`ApnsAuthKey.p8`) is not gitignored
- **Location**: `.gitignore` (lists `schneaggchatv3-firebase-admin.json` and `.env`, but **not** `ApnsAuthKey.p8`); the key is mounted from repo root in both compose files.
- **Confidence**: High. Currently **not** committed (`git ls-files` shows no `.p8`), so this is prevention.
- **Issue**: The Apple push signing key lives (or is expected) in the repo working directory but is not
  ignored. A routine `git add .` / `git add -A` will stage and commit it.
- **Impact**: Leaking the `.p8` lets an attacker send push notifications as your app to any device, and it's
  a long-lived credential that must be manually revoked in the Apple developer portal.
- **Fix**: Add `ApnsAuthKey.p8` (and `*.p8`) to `.gitignore` now. If it was ever committed historically,
  rotate the key.

### M-4 — Profile pictures (user and group) have no authorization check  ✅ FIXED (2026-09-01)
> `UserService.getProfilePic` now enforces self / friends / shared-group visibility before returning
> the image. (The **group** picture endpoint was already gated by `isUserInGroup` in
> `GroupController.getProfilePic` — the original finding overstated that half.) Stale `//TODO` removed.

- **Location**: `user/UserService.kt:237-244` (`getProfilePic` takes `requestingUserId` but never uses it — the controller `UserController.kt:108` even has `//TODO: Check user profilepic settings (implement first)`); `group/GroupService.kt:188-198` (`getGroupProfilePic` — no membership check)
- **Confidence**: High.
- **Issue**: Any authenticated user can fetch **any** user's profile picture (`GET /users/profilepic/{id}`)
  or **any** group's picture (`GET /groups/profilepic/{id}`) by iterating ObjectIds. No friendship /
  membership / privacy-setting gate, despite the code acknowledging the gate is unimplemented.
- **Impact**: IDOR / enumeration of profile images across the whole user and group base, ignoring the
  intended privacy settings. Lower impact than message media because pictures are semi-public in a chat app,
  but it bypasses a control the app claims to have.
- **Fix**: Enforce the intended profilepic-visibility setting for users (friends-only, etc.), and require
  group membership for group pictures — mirror the `canUserAccessMessage` pattern already used for message
  image/audio (`message/MessageService.kt:560-580`, which is correctly gated).

---

## LOW / DEFENSE-IN-DEPTH

### L-1 — Verbose error messages returned to clients
- **Location**: `application.properties` (`spring.web.error.include-message=always`); `core/GlobalExceptionHandler.kt:71-80` (returns `e.message` for `IllegalArgumentException`) and `:122-132` (returns Jackson `e.message` for unreadable bodies)
- **Confidence**: High.
- **Issue**: Most echoed messages are your own `require(...)` strings (safe), but the
  `HttpMessageNotReadableException` handler returns the raw Jackson parse message, which can disclose
  internal type/field structure. The catch-all (`:135-151`) correctly returns a generic message — keep that.
- **Fix**: Return a generic "invalid request body" without the exception detail; keep detailed messages in
  server logs only.

### L-2 — `/stats.html` is public
- **Location**: `core/security/SecurityConfig.kt:65-66` (permitAll), `website/WebsiteController.kt:15-28`
- **Confidence**: High.
- **Issue**: Anyone can view log-type counts and active-device counts — low-grade operational/usage
  disclosure (message volume, login counts, user activity trends).
- **Fix**: Put `/stats.html` behind auth (or the actuator-style key), or drop the sensitive counters.

### L-3 — Password reset allowed for unverified emails
- **Location**: `authentication/EmailService.kt:168-197` (comment: "Allow unverified email passwort resets", the `return` is commented out)
- **Confidence**: Medium.
- **Issue**: Reset proceeds even when `emailVerifiedAt == null`. Since the reset link is sent to the
  registered address this is mostly self-limiting, but combined with unverified registration it means an
  account registered under an email the user never proved they own can still be "reset" by whoever controls
  that inbox. Low practical risk here.
- **Fix**: Consider requiring a verified email before password reset, or at least before the account holds
  any sensitive data.

### L-4 — No per-account brute-force lockout (only per-IP rate limit)
- **Location**: `authentication/AuthService.kt:91-106`, rate limiting is IP/user-bucket only
- **Confidence**: Medium (defense-in-depth).
- **Issue**: Even once H-1 is fixed, protection is per-IP. A distributed attacker (many IPs) can still spread
  guesses against a single account. There's no account-level throttle or lockout, and no CAPTCHA.
- **Fix**: Add a per-username failure counter with backoff/temporary lockout, independent of source IP.

### L-5 — `Bearer` stripping replaces all occurrences
- **Location**: `core/security/JwtService.kt:96` (`token.replace("Bearer ", "")`)
- **Confidence**: High (robustness, not currently exploitable).
- **Issue**: `replace` strips every `"Bearer "` substring anywhere in the token, not just a leading prefix.
  The subsequent signature verification makes this non-exploitable today, but it's a fragile parse.
- **Fix**: Use `token.removePrefix("Bearer ").trim()`.

### L-6 — `APNS_DEBUG=true` in local `docker-compose.yml`
- **Location**: `docker-compose.yml` (`APNS_DEBUG: true`)
- **Confidence**: High (dev stack).
- **Issue**: In debug mode access tokens live only 10s (`JwtService.kt:26,56`), the email base URL points at
  the test instance (`EmailService.kt:37-40`), and a `testaccount` is seeded (`MainController.kt:80-83`).
  Fine for local dev, but make sure the production stack sets it `false` (prod `.env.example` default is
  `false` — good; just don't copy the dev compose to prod).
- **Fix**: Confirm prod runs `APNS_DEBUG=false`.

---

## Needs verification (deployment-dependent)

### NV-1 — Are host ports 8081 (mongo-express) and 27017 (Mongo) firewalled?
C-2 and M-1 escalate to full compromise if these host ports are reachable from outside the server. Confirm a
host firewall blocks them, or (better) remove the `ports:` mappings so they're never published. If this box
has a public IP with no firewall, treat C-2 as actively exploitable.

### NV-2 — Is `PROMETHEUS_KEY` actually set to a strong value in production?
C-1 is critical only if the key is empty/weak. Verify the real production `.env` sets a strong random value.

---

## What was checked and found OK

- **JWT token-type confusion**: access/refresh/email/delete/reset tokens each carry a `type` claim that is
  checked on use (`JwtService.validateAccessToken` etc.) — an email/reset token can't be used as an access token.
- **Refresh token rotation**: hashed at rest (SHA-256+Base64), replay-safe via atomic `findAndModify`,
  concurrent-refresh race handled (`AuthService.kt:126-237`).
- **Password change/reset revoke sessions**: both call `refreshTokenRepository.deleteByUserId` (`UserService.kt:398,484`) — no session-fixation-after-reset issue.
- **Message image/audio access is authorized** via `canUserAccessMessage` (`MessageService.kt:560-580`).
- **Group mutations enforce membership/admin** (`GroupService.kt:201-285,367-461`).
- **All feature controllers (users, messages, groups, map, events, games, recap, wake)** retrieve the caller
  via `requireAuth()` and never trust a user id from the request body.
- **NoSQL injection**: queries use typed `Criteria.where(...).is(ObjectId/…)`; inputs validated by
  `ValidationUtils` (ObjectId regex, etc.). No string-concatenated Mongo queries or `$where`.
- **Login user-enumeration**: uniform `BadCredentialsException` for unknown user vs. wrong password.
- **No secrets committed**: `git ls-files` shows no `.p8`, firebase json, or `.env` tracked (but see M-3).
- **BCrypt** for password hashing; **AES-GCM with a full 32-byte SHA-256 key** for FCM payloads (nonce handled by the crypto library).
