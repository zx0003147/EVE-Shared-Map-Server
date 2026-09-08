# Self-hosted VPS acceptance

Use this checklist on a disposable Ubuntu 24.04 LTS x86_64 VPS before declaring a self-hosted release ready. Never
run a destructive fresh-install test against an existing or production instance.

## Fresh install

1. Confirm a clean supported VPS, synchronized UTC clock, at least 2 GiB RAM, 20 GiB free disk, and unused TCP
   80/443. Keep a second proven SSH session and provider console available before changing any firewall rule.
2. Publish the approved Planner Web ZIP, its SHA-256, the exact Server/Ops images, and the matching
   `self-hosted-release.json`. Do not use `latest`.
3. Clone the Server release checkout and run `sudo ./install.sh`. Enter two distinct DNS hostnames, ACME email,
   Workspace name, and first Admin display name. Create only the two displayed A records when prompted.
4. Save the one-time invite, open both HTTPS origins, exchange the invite, and perform a create/read/update/delete
   Shared Marker smoke from two clients.
5. Run `sudo eve-map status`, `sudo eve-map diagnostics`, and `sudo eve-map backup`. Confirm the report contains no
   credential material and copy the encrypted backup plus checksum to genuinely off-site storage.

## Security and persistence

- Confirm only Caddy publishes host ports 80/443; PostgreSQL 5432 and Ktor 8080 have no host bindings.
- Confirm the Server is UID/GID 10001, read-only, capability-free, and has `no-new-privileges`.
- Confirm PostgreSQL/Ops secrets are owned for UID 70, Server secrets for UID 10001, each mode 0400, with the
  containing directory root-owned mode 0710.
- Confirm Caddy sends HSTS and `nosniff`; Web HTML, service worker, and `data/manifest.json` revalidate; the versioned
  `.json.gz` is immutable, `application/gzip`, and has no `Content-Encoding`.
- Confirm exact-origin CORS succeeds for the Web origin, rejects an unrelated origin, and leaves Desktop requests
  without `Origin` unchanged.
- Restart Server/Caddy and then PostgreSQL. Confirm `eve-map status` returns to OK and marker data persists.

## Update and rollback

1. Publish a second compatible test release and run `sudo eve-map update <manifest-url>`.
2. Confirm a pre-update encrypted backup is produced before image/Web changes, exact images are pulled, the Web
   release symlink switches atomically, Flyway reaches the manifest version, and public Web/API/CORS checks pass.
3. Exercise an application/Web failure without a Flyway version change and confirm the previous Web and image env
   are restored. For a forward-only schema change, confirm the CLI stops and directs the operator to the documented
   isolated restore procedure rather than pretending to downgrade Flyway.
4. Run `sudo eve-map web-pack <Desktop-export-directory>`, confirm the new versioned Pack is installed before the
   manifest changes, and verify PostgreSQL and Shared Marker were not restarted.

Record results without invites, device tokens, Authorization headers, passwords, token pepper, passphrases, secret
contents, or private backup locations.
