# Delivery Pipeline — IntelliJ plugin

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/32269.svg?label=marketplace)](https://plugins.jetbrains.com/plugin/32269-delivery-pipeline)

Google Cloud Deploy delivery pipelines as a **service type** in the IDE's
Services tool window (the same `+` popup as Docker and Kubernetes →
**GCP Delivery Pipelines…**).

```
Services
└─ Delivery Pipelines
   └─ checkout-pipeline   my-proj/us-central1 · 2/3 succeeded · 1 awaiting approval
      ├─ Releases   20 recent
      │  ├─ rel-042
      │  └─ rel-041
      ├─ dev     rel-042 · succeeded
      ├─ stage   rel-042 · in progress
      └─ prod    rel-041 → rel-042 · pending approval
```

- Watch pipelines from **multiple GCP projects, regions, and pipelines**; the
  list persists in `.idea/deliveryPipelines.xml` (shareable via VCS).
- Each target shows the **latest rollout** across recent releases — targets on
  different releases are shown as such.
- **Approve or reject** a pending rollout in-IDE; **promote** any recent release
  to any target. A per-pipeline **Releases** node lists recent releases.
- Double-click any node → Cloud Console. Delete (⌫) unwatches a pipeline.
  **Refresh** per pipeline shows a progress indicator and updates only on change.

## What's new in 0.5.3

- **Approve / reject** a pending rollout straight from the IDE — no more bouncing
  to the Cloud Console. The action is disabled with a reason when you lack the
  `clouddeploy.rollouts.approve` permission.
- **Incoming** rollup per pipeline: everything awaiting approval or rolling out,
  with inline Approve/Reject; the pipeline node shows the awaiting-approval count.
- A **Releases** node per pipeline plus **Promote to Target…** — send any recent
  release to any target. Rollout ids come from the release's authoritative
  server-side list, so re-promoting an older release no longer clashes.
- **Manual refresh** (toolbar / right-click) with a background progress
  indicator; the tree rebuilds only when the data actually changed.
- Internals: async moved to the platform's coroutines; approval links open the
  Console `…/approve` page; fixed a toolbar hover/tooltip flicker.

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

## Release channels

Published on
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32269-delivery-pipeline)
on the **stable** channel. The channel is derived from the version suffix
(trailing build number stripped) — an unsuffixed `0.5.3` goes to **stable**, a
`0.5.x-beta1` build goes to **beta**. To also receive beta builds, add the beta
repository in **Settings → Plugins → ⚙ → Manage Plugin Repositories…**:

```
https://plugins.jetbrains.com/plugins/beta/list
```

then install **Delivery Pipeline** as usual.

## License

[MIT](LICENSE) © Denis Panfilov
