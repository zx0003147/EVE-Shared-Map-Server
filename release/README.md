# Self-hosted release manifest

`self-hosted-release.example.json` documents the deliberately small compatibility lock consumed by `install.sh`
and `eve-map update`. A release operator publishes a filled `self-hosted-release.json` as a release asset together
with the Planner-generated Web ZIP. The manifest must use the ZIP's real SHA-256 and exact non-`latest` Server/Ops
image references.

The repository example is not a release channel and contains non-deployable placeholders. Publishing images,
tags, or a GitHub Release remains a separately authorized release operation.
