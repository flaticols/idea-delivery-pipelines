# Delivery Pipeline — IntelliJ plugin

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/32269.svg?label=marketplace)](https://plugins.jetbrains.com/plugin/32269-delivery-pipeline)

Google Cloud Deploy delivery pipelines as a **service type** in the IDE's
Services tool window (the same `+` popup as Docker and Kubernetes →
**GCP Delivery Pipelines…**).

```
Services
└─ Delivery Pipelines
   ├─ checkout-pipeline   my-proj/us-central1 · 2/3 succeeded
   │  ├─ dev    rel-042 · succeeded
   │  ├─ stage  rel-042 · in progress
   │  └─ prod   rel-041 · pending approval
   └─ billing-pipeline    other-proj/europe-west1 · 1/1 succeeded
```

- Watch pipelines from **multiple GCP projects, regions, and pipelines**; the
  list persists in `.idea/deliveryPipelines.xml` (shareable via VCS).
- Each target shows the **latest rollout** across recent releases — targets on
  different releases are shown as such.
- Double-click any node → Cloud Console. Delete (⌫) unwatches a pipeline.
  Refresh per pipeline or for everything.

## Auth & data path

`gcloud auth print-access-token` is called once per ~50 minutes (the only
gcloud invocation); all queries then go **directly to the Cloud Deploy REST
API** (`clouddeploy.googleapis.com/v1`) with the cached bearer token — fast,
no per-query CLI startup, no bundled Google SDKs. A 401 invalidates the token
and retries once.

Requires an authenticated gcloud (`gcloud auth login`). Rollout listing tries
the aggregated `releases/-/rollouts` endpoint first and falls back to
per-release listing automatically.

## Build

```nushell
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew buildPlugin   # → build/distributions/delivery-pipeline-<version>.zip
./gradlew verifyPlugin  # IntelliJ Plugin Verifier
```

Builds against a locally installed IntelliJ IDEA (or GoLand) when present;
otherwise downloads IntelliJ IDEA Community. Platform APIs only — the plugin
runs in any IntelliJ-based IDE 2026.1+.

## Install into a real IDE

Settings → Plugins → ⚙ → Install Plugin from Disk… → pick the zip from
`build/distributions/`.

## Beta channel

This plugin is published on
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32269-delivery-pipeline),
currently on the **beta** release channel (the version carries a `-beta`
suffix). To receive beta builds, add
the beta repository in **Settings → Plugins → ⚙ → Manage Plugin Repositories…**:

```
https://plugins.jetbrains.com/plugins/beta/list
```

then install **Delivery Pipeline** as usual. The channel is derived from the
version suffix (trailing build number stripped) — `0.5.2-beta1` goes to `beta`,
an unsuffixed `0.5.2` would go to the stable channel.

## License

[MIT](LICENSE) © Denis Panfilov
