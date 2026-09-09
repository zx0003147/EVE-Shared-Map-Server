# Production deployment

This runbook deploys the V1 EVE Shared Map Server as one self-hosted instance. The frozen topology is:

```text
Internet (TCP 80/443)
        |
      Caddy
      /     \
 Web/PWA   shared-map-server
  files           |
             PostgreSQL 18.6
```

Only Caddy publishes host ports. The application port and PostgreSQL are Docker-internal. Shared Marker notes are
sensitive alliance data: the server and database administrators can read them. V1 is not end-to-end encrypted or
zero-knowledge.

## Capacity and operating system

For a small coordination group, at most 500 markers per Workspace, and 30-second client polling:

| Profile | vCPU | RAM | SSD | OS |
| --- | ---: | ---: | ---: | --- |
| Minimum | 1 | 2 GiB | 20 GiB | Ubuntu 24.04 LTS x86_64 |
| Recommended | 2 | 4 GiB | 40 GiB | Ubuntu 24.04 LTS x86_64 |

The recommended profile leaves room for PostgreSQL maintenance, image updates, encrypted backups, and transient
Java memory without implying high-scale capacity. Increase disk based on measured database, image, and backup use.
This deployment is provider-neutral.

## Release and image strategy

Production uses a Git/tag-defined release built on a controlled build host or CI and published as immutable,
explicitly tagged images. The VPS pulls exact image references. It must not edit source, build a random `main`, or
track `latest`.

The official 0.3.0 release tags are `ghcr.io/zx0003147/eve-shared-map-server:0.3.0` and
`ghcr.io/zx0003147/eve-shared-map-ops:0.3.0`. Record the Git commit, registry reference, image ID, and registry digest
in the deployment record. The formal self-hosted manifest pins the corresponding registry digests.

Example controlled builds from a clean, approved release checkout:

```sh
docker build --pull --build-arg APP_VERSION=0.3.0 --build-arg VCS_REF=<server-commit> \
  -t ghcr.io/zx0003147/eve-shared-map-server:0.3.0 .
docker build --pull -f ops/Dockerfile --build-arg APP_VERSION=0.3.0 --build-arg VCS_REF=<server-commit> \
  -t ghcr.io/zx0003147/eve-shared-map-ops:0.3.0 .
docker image inspect ghcr.io/zx0003147/eve-shared-map-server:0.3.0
docker image inspect ghcr.io/zx0003147/eve-shared-map-ops:0.3.0
```

Push and tag only after explicit release authorization. Keep the previous application image available for rollback.

## VPS and SSH preflight

Before changing the host, record the OS/version, CPU, RAM, disk, public IP, current firewall, existing services, port
80/443 occupancy, Docker version, and time state:

```sh
cat /etc/os-release
nproc
free -h
df -h
ss -lntup
timedatectl
```

`System clock synchronized` and NTP must be active because token expiry, invite expiry, and audit timestamps use UTC.

Use a normal sudo user and SSH keys. Confirm a second key-authenticated SSH session works before disabling passwords
or root SSH. Keep the original session open and retain a provider-console rollback route while changing SSH or the
firewall. Docker-group membership grants root-equivalent control of the host; use it only with that risk understood.
Fail2ban is optional.

Install Docker Engine and the Compose plugin from Docker's official Ubuntu apt repository. Do not use an unaudited
third-party install script. Verify `docker version`, `docker info`, `docker compose version`, and a controlled
`hello-world` run before deployment.

## Firewall contract

The default inbound policy is deny. Allow only the actual SSH port, TCP 80 for ACME/redirect, and TCP 443 for HTTPS.
Do not open 5432 or 8080. With SSH still proven in a second session, a typical UFW sequence is:

```sh
sudo ufw allow <actual-ssh-port>/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw enable
sudo ufw status verbose
```

Adjust the SSH rule before enabling UFW if the host does not use port 22. Never remove the only working access path.

## Directory layout and secrets

Recommended host layout:

```text
/opt/eve-shared-map/
  docker-compose.prod.yml
  .env.production
  ops/
  release/self-hosted-release.json
  web/
    current -> releases/<web-version>-<artifact-hash>/
    releases/
  secrets/
  backups-local-staging/
/mnt/eve-shared-map-offsite/   # storage outside this VPS
```

Create a dedicated `eve-map` operator account and restrict files:

```sh
sudo install -d -o eve-map -g eve-map -m 0750 /opt/eve-shared-map
sudo install -d -o root -g eve-map -m 0710 /opt/eve-shared-map/secrets
sudo install -d -o 70 -g 70 -m 0700 /opt/eve-shared-map/backups-local-staging
sudo install -d -o 70 -g 70 -m 0700 /mnt/eve-shared-map-offsite
```

The off-site path must be backed by storage whose failure domain is outside the VPS. A second directory or Docker
volume on the same server is not off-site. An operator-managed encrypted filesystem, rclone mount, SSHFS, or similar
mount may provide the target; the backup job itself remains provider-neutral.

Generate secrets once as root on the VPS with
`sudo ops/generate-secrets.sh /opt/eve-shared-map/secrets`, or use an equivalent cryptographically secure process.
It creates consumer-specific files:

- `postgres-database-user.txt` and `postgres-database-password.txt` for PostgreSQL/Ops UID 70
- `server-database-user.txt` and `server-database-password.txt` for Server UID 10001
- `token-pepper.txt`
- `backup-passphrase.txt`

Standalone Compose file secrets are bind mounts and cannot reliably change host UID/GID. The generator therefore
writes identical database values to two files, owns each file by its consuming non-root container UID, and sets mode
0400. `token-pepper.txt` is owned by UID 10001; `backup-passphrase.txt` is owned by UID 70. The directory is owned by
root, grants the deployment group traverse-only access (0710), and prevents that human group from listing or reading
the secrets. Never commit these files, bake them into an image, put their values in normal environment variables, or
print them in logs. Copy `.env.production.example` to `.env.production`, set the real domain, image references,
consumer-specific paths, and resource limits. Use mode 0600 for a root-run manual deployment. Installer-managed
deployments use root ownership, the dedicated `eve-map` group, and mode 0640 so the backup timer can read paths and
non-secret settings without gaining access to any secret file content.

Set `SHARED_MAP_ALLOWED_ORIGINS` to the exact HTTPS origin serving EVE Static Map Planner Web, not the API origin
unless they are the same. Multiple approved Web deployments are comma-separated. Do not use a wildcard, path, or
plain HTTP remote origin. Recreate the server after changing the allowlist. This setting controls browser CORS only;
Desktop requests do not carry `Origin` and remain valid.

The duplicate database files must remain byte-identical and are rotated together. The token pepper is persistent cryptographic state. Losing it makes all existing device tokens and unconsumed
invites unverifiable even if PostgreSQL is restored. Changing it immediately invalidates those credentials; V1 has
no lossless multi-key pepper rotation. A database password may be rotated only as a coordinated PostgreSQL and secret
file change. Rotating the backup passphrase prevents restoration of older backups unless the old passphrase remains
securely archived. Store a disaster-recovery copy of the token pepper and required configuration separately from the
encrypted database dump.

## DNS and TLS

Choose distinct Shared Marker and Web Map hostnames with the operator. Add an A record for each hostname to the same
VPS public IPv4 address. A Web subdomain does not require a second registered domain. Add AAAA only when IPv6 routing
and firewalling are proven. Verify authoritative and external resolution before starting Caddy. Caddy then obtains
and renews both public certificates and redirects HTTP to HTTPS.

`ops/caddy/Caddyfile` retains the API request-body limit and reverse proxy, and adds the second static Web site from
the read-only `/srv/eve-map/current` mount. Both sites add HSTS and `nosniff`. The Web site revalidates HTML, the
service worker, and `data/manifest.json`; versioned Web Packs are immutable and served as `application/gzip` without
`Content-Encoding`, because the browser verifies compressed bytes before using `DecompressionStream`. Caddy access
logging is intentionally not enabled, avoiding request-header and query-string retention; process logs still go to
Docker. If access logging is added later, Authorization, Cookie, request/response bodies, and sensitive query strings
must be removed.

## Beginner installer

The top-level `install.sh` is a guided wrapper around the production controls in this runbook. It supports only
Ubuntu 24.04 LTS x86_64, refuses an existing installation, checks minimum RAM/disk and port availability, installs
Docker from Docker's official apt repository when necessary, generates the established UID-specific secret files,
downloads the release-locked Planner Web ZIP, verifies SHA-256 and its internal Web Pack, then invokes `ops/deploy.sh`.
It never builds a random Server or Kotlin/JS checkout on the VPS.

The installer consumes a small release manifest containing `selfHostedVersion`, `webVersion`, `serverVersion`,
`webArtifactUrl`, `webSha256`, exact `serverImage`/`opsImage`, `flywayVersion`, and `minimumInstallerVersion`. The
example under `release/` is non-deployable documentation; a release operator must publish a filled manifest and
artifact. Generated Web bundles are not committed to this repository.

An interrupted installer-managed run leaves a root-controlled `.installer-in-progress` marker. Run
`sudo ./install.sh --resume` from the same release checkout; the installer reuses the recorded manifest and existing
protected state. Fresh install still refuses an environment or deployment that lacks this marker.

## Deploy

Validate the bundle first:

```sh
cd /opt/eve-shared-map
docker compose --env-file .env.production -f docker-compose.prod.yml --profile ops config --quiet
```

`ops/deploy.sh` rejects HTTP smoke URLs and untagged/`latest` images, pulls the exact references, starts PostgreSQL,
the server, and Caddy, waits for health, verifies Flyway schema 4, and checks the public health and meta endpoints:

```sh
./ops/deploy.sh /opt/eve-shared-map/.env.production \
  https://markers.example.com https://map.example.com
```

In addition to the existing health/meta/Flyway checks, the deploy executor verifies the public Web root, Web Pack
manifest and versioned gzip response, absence of `Content-Encoding`, exact Web-origin CORS, and browser preflight.

Flyway runs before the application listens. A migration failure prevents application health and must not be hidden.
The compose file never publishes PostgreSQL or port 8080 and never removes the persistent PostgreSQL volume.

Verify the configured Web origin before issuing production invites:

```sh
curl -fsS -D - -o /dev/null \
  -H 'Origin: https://map.example.com' \
  https://marker.example.com/api/v1/meta

curl -fsS -D - -o /dev/null -X OPTIONS \
  -H 'Origin: https://map.example.com' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type,x-request-id,idempotency-key' \
  https://marker.example.com/api/v1/workspaces/00000000-0000-0000-0000-000000000000/markers
```

The first response must include the exact `Access-Control-Allow-Origin`; the preflight must succeed without
`Access-Control-Allow-Credentials`. Repeat with an unapproved origin and confirm `403`. Both the Web site and API
must use HTTPS; do not weaken browser security or rely on mixed content.

## Bootstrap the first Admin

Do not bootstrap until the operator explicitly confirms the production Workspace name and first Admin display name.
There is no bootstrap HTTP endpoint. Run the one-shot CLI:

```sh
docker compose --env-file .env.production -f docker-compose.prod.yml run --rm \
  shared-map-server bootstrap-admin \
  --display-name '<confirmed admin name>' \
  --workspace-name '<confirmed workspace name>' \
  --invite-ttl 1h
```

The raw invite appears once. Deliver it securely; do not redirect it into deployment logs. Record the bootstrap UTC
time, Workspace name, and Admin display name, but never the invite or device token.

## Backup and retention

The backup job performs `pg_dump --format=custom`, validates the dump catalog, encrypts it with GnuPG symmetric
AES-256 using the mounted passphrase file, writes SHA-256 metadata, verifies the copied off-site artifact, and exits
nonzero on failure. It does not enable shell tracing or place credentials in filenames. Daily artifacts are kept by
count (30); the first successful UTC backup in each calendar month is also kept in the monthly set (12). This remains
reliable if the first day is missed. Daily pruning does not delete monthly copies.

Run and inspect one backup immediately after first production acceptance:

```sh
docker compose --env-file .env.production -f docker-compose.prod.yml --profile ops run --rm backup
journalctl -u eve-shared-map-backup.service
```

Install the supplied systemd service/timer templates, review their user and paths, then enable the timer:

```sh
sudo install -m 0644 ops/systemd/eve-shared-map-backup.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now eve-shared-map-backup.timer
systemctl list-timers eve-shared-map-backup.timer
```

The timer is persistent, runs daily around 02:15 UTC, and exposes failures through systemd/journal with a nonzero
service result. Connect journal failure status to email or an uptime/operations alert later; V1 does not send Discord
notifications.

## Restore and quarterly drill

Restore never defaults to the live database. It requires an encrypted artifact, its adjacent checksum, a safe target
database name, and an exact repeated confirmation. Restore into an isolated name first:

```sh
docker compose --env-file .env.production -f docker-compose.prod.yml --profile ops run --rm restore \
  --backup /backups/offsite/daily/eve-shared-map-<timestamp>.dump.gpg \
  --checksum /backups/offsite/daily/eve-shared-map-<timestamp>.dump.gpg.sha256 \
  --target-database eve_shared_map_restore_drill \
  --confirm-reset eve_shared_map_restore_drill
```

The script verifies SHA-256, decrypts to tmpfs, validates the custom dump, resets only the exact confirmed target,
restores with stop-on-error, and verifies expected Flyway schema 4 plus marker, route-handoff, and audit tables. Start
an isolated server against that restored database, use a retained test credential to authenticate, fetch markers and
recent Route Handoffs, and confirm audit rows. Never point the drill at the live database. Perform and record this
drill at least quarterly.

A real production restore additionally requires setting `SHARED_MAP_RESTORE_ENVIRONMENT=production` and passing
`--allow-production-restore`; exact-name confirmation is still required. Before doing so, stop the application,
preserve the failed database, verify the target backup and secrets, and obtain operator approval.

## Update and rollback

For every upgrade that may migrate the database:

1. Verify a recent encrypted off-site backup and checksum; run a fresh pre-deploy backup.
2. Record the current commit, application image reference/ID/digest, database image, and Flyway version.
3. Pull the exact approved target images; never use `latest`.
4. Run compose config validation.
5. Recreate the application as needed. Do not use `down -v`.
6. Let Flyway complete before traffic becomes healthy.
7. Check public HTTPS health/meta, `route-handoffs` feature advertisement, and real authorized marker plus Route
   Handoff read/write smokes.
8. Retain the previous images and the pre-deploy backup.

Application rollback and database rollback are different. If no migration ran, or the old application is proven
compatible with the migrated schema, pinning the prior image is sufficient. A forward-only Flyway migration can make
the old application incompatible; switching images alone is then unsafe. Restore the pre-deploy database into an
isolated database, validate it, and perform an explicitly approved production restore when schema rollback is
required. Never use Flyway `clean` or automatic `repair`.

For installer-managed deployments, `sudo eve-map update [manifest-url]` implements this sequence. It runs the
existing encrypted backup before changing anything, stages and validates the new Web release, updates only the exact
Server/Ops image and expected Flyway fields, atomically switches the Web symlink, and runs the deploy executor. When
the Flyway version is unchanged, a failed deployment restores the prior env, release manifest, and Web symlink and
attempts the prior application deployment. If a forward-only migration may have run, it restores only the Web site
and stops with the explicit database-restore guidance above; it never attempts an automatic Flyway downgrade.

## Installer-managed operations

- `eve-map status`: concise Web/API/PostgreSQL/Caddy/Flyway/Web Pack/HTTPS health.
- `eve-map update`: backed-up, release-manifest-locked Web and Server update.
- `eve-map restart`: restart Server/Caddy and rerun public production validation.
- `eve-map logs [service]`: bounded service logs passed through credential redaction.
- `eve-map diagnostics`: copy-safe host, DNS, Docker, health, schema, Web, cache, CORS, and backup state; secret files
  are never read.
- `eve-map backup`: wrapper around the existing encrypted backup container.
- `eve-map web-pack <file-or-directory>`: validate the Desktop schema/size/checksum/gzip, publish the versioned Pack,
  then atomically replace `data/manifest.json` without touching PostgreSQL or Shared Marker.
- `eve-map version`: installed compatibility and Web Pack versions.

There is intentionally no automatic uninstall in this release. To remove application containers while keeping all
data, stop only the named services and retain `/opt/eve-shared-map`, its secrets/backups/Web releases, and the named
PostgreSQL volume. A full deletion is a separate destructive maintenance procedure that must inventory backups and
require an explicit `DELETE ALL DATA` confirmation; it is not delegated to the beginner CLI.

## Disaster recovery

For complete VPS loss:

1. Provision a new supported VPS and harden SSH/firewall with safe sequencing.
2. Install official Docker Engine and Compose.
3. Restore permission-restricted secret files and production configuration from the separate secret backup.
4. Restore the encrypted PostgreSQL backup into an isolated database and validate its checksum/Flyway state.
5. Deploy the exact recorded server, ops, PostgreSQL, and Caddy image versions.
6. Promote the validated database through the explicit production restore procedure.
7. Restore DNS/Caddy, verify certificate issuance, HTTPS health/meta, authentication, and client reconnect.
8. Create a fresh encrypted off-site backup and repeat an isolated restore check.

## Health, logs, and disk

`GET /health` returns 200 with `status: ok` when PostgreSQL is ready and 503 with no connection details when it is
unavailable. Monitor the public HTTPS endpoint with a simple external uptime service. `GET /api/v1/meta` additionally
verifies protocol 1, features, server version, and universe build.

Server logs are JSON Lines and omit headers, cookies, query strings, bodies, invites, and tokens. Docker uses the
`json-file` driver with 10 MiB × 5-file rotation for PostgreSQL, Server, Caddy, and one-shot operations. Inspect without
printing secret files:

```sh
docker compose --env-file .env.production -f docker-compose.prod.yml ps
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail 100
docker system df
sudo du -sh /var/lib/docker/volumes/eve-shared-map-prod_postgres_data
sudo du -sh /opt/eve-shared-map/backups-local-staging /mnt/eve-shared-map-offsite
```

Never run `docker system prune -a` as routine production maintenance.

## Production acceptance record

Record without secrets:

- VPS OS, CPU/RAM/disk, public ports, firewall state, NTP state
- domain, certificate issuer/expiry, HTTP redirect and HTTPS results
- Server Git commit/version and server/ops image references, IDs, and digests
- PostgreSQL version and confirmation that it has no host port
- exact `/health` and `/api/v1/meta` results
- bootstrap UTC time, Workspace name, and Admin display name
- two-client create/receive/edit/receive/delete/remove smoke result
- server restart and client auto-recovery result
- backup UTC timestamp, SHA-256, off-site copy status, and isolated restore result
- Map artifact/version/hash and ESI, Sovereignty, Feature API 2.0.0, user schema 4, and 30-tool MCP compatibility

Do not record raw invites, device tokens, database credentials, token pepper, backup passphrase, or private backup
locations in public release notes.

## Troubleshooting

- **PostgreSQL unhealthy:** inspect its bounded logs, volume free space, secret-file mounts, and ownership. Do not
  publish port 5432 as a shortcut.
- **Server unhealthy:** inspect startup/migration logs. A failed Flyway migration intentionally prevents readiness.
- **Caddy certificate failure:** verify public A/AAAA resolution, inbound 80/443, clock synchronization, and ACME
  rate-limit messages. Remove an invalid AAAA record rather than advertising unreachable IPv6.
- **401 after disaster recovery:** confirm the original token pepper was restored. Generating a new pepper invalidates
  all old tokens and invites.
- **Backup failed:** check systemd status, free space, off-site mount availability, permissions for UID/GID 70, and
  the nonzero backup job output. Do not delete the last known-good backup.
- **Old image fails after upgrade:** stop retry loops and use the documented schema-aware rollback decision; an image
  rollback cannot reverse an incompatible Flyway migration.
