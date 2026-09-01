# Solar-system allowlist

## Runtime contract

Shared Map Server packages `src/main/resources/universe/solar-system-allowlist-v1.txt`. The resource contains only
canonical positive EVE solar-system IDs and the metadata required to verify the generated set. Runtime Marker
validation is an in-memory membership check. The server does not package a full SDE, connect to Map `static.db` or
`user.db`, call ESI, or require internet access.

Startup fails closed when the resource is missing, empty, duplicated, malformed, count-mismatched, or lacks source
build/hash metadata.

## Current provenance

- CCP source: official EVE Online JSONL SDE archive `eve-online-static-data-3466501-jsonl.zip`
- SDE build: `3466501`
- Packaged protocol value: `sde-3466501`
- Archive SHA-256: `4c398245d758dd1ea1256cf519ad2e0231c4dab556cf39dd1edb0c5246d8eea6`
- Extracted `mapSolarSystems.jsonl` SHA-256:
  `f9975854ebf72af597e6f554389c293d9c51248aea59bb9940585583e863e0e1`
- Generated system count: `8490`

The archive and extracted source are generation inputs only; neither is copied into the server repository or runtime
image.

## Deterministic update procedure

1. Obtain an official CCP JSONL SDE archive and record its build and SHA-256.
2. Extract `mapSolarSystems.jsonl` and record its SHA-256.
3. Generate the resource from the server repository root:

   ```powershell
   .\scripts\generate-solar-system-allowlist.ps1 `
     -InputJsonl 'C:\path\to\mapSolarSystems.jsonl' `
     -SdeBuild '<build-number>'
   ```

4. Review the resource metadata and count. The generator rejects missing/non-positive/duplicate `_key` values and
   writes numerically sorted IDs as UTF-8 without a BOM.
5. Run the allowlist tests and full build with Docker available:

   ```powershell
   .\gradlew.bat --no-daemon --console=plain test --tests '*SolarSystemAllowlistTest' --rerun-tasks
   .\gradlew.bat --no-daemon --console=plain clean build --rerun-tasks
   ```

6. Confirm `/api/v1/meta.universeBuild` matches the generated resource before publishing a server image.

An allowlist refresh is a reviewed server release input. It must never be replaced dynamically at runtime.
