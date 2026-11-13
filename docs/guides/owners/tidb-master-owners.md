---
prompt: |
  parse the prow OWNERS and OWNERS_ALIASES file to generate report about the code owners (the approvers role).

  Goals:

  1. the report should saved in markdown file.
  2. the report should contain two tables, one is the SIGs, another is about the scopes view.
    - for the SIGs table: it should include sig name, sig member, scope(folders or files) they take effects on.
      - you should list the matched files for the non-default pattern in the filter mode OWNERS file.
      - do not list the detail member for community SIGs.
    - for the scopes view table: path(folder or file), who can approve(SIG list or standalone member).
  3. do not tell reader the filter pattern detail, such as "Matched file (filter):".
  4. you should ensure the content in markdown table cell display well, for example: do not use list items and '|' characters.
repo: pingcap/tidb
branch: master
---
# TiDB OWNERS report (approvers only)

- Source: OWNERS and OWNERS_ALIASES in repository.
- Notes:
  - Directory scopes are recursive unless otherwise noted.
  - For filter-based OWNERS, specific matched files are listed for non-default patterns.
  - Community SIG membership is managed in pingcap/community and not expanded here.

## SIGs view

| SIG | Members | Scope (folders/files) |
|---|---|---|
| sig-community-approvers | Managed in pingcap/community (not listed) | Repository default for files not covered by more specific OWNERS (subject to inheritance) |
| sig-approvers-modules | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | `pkg/**` (fallback for modules without dedicated OWNERS), `OWNERS_ALIASES` |
| sig-critical-approvers-dep | bb7133, cfzjywxk | `go.mod` |
| sig-approvers-planner | AilinKid, 0xPoe, elsa0520, fixdb, hawkingrei, qw4990, time-and-fate, winoros, terry1purcell, ghazalfamilyusa, henrybw, wddevries, King-Dylan | `pkg/planner/**`, `pkg/bindinfo/**` |
| sig-approvers-metrics | XuHuaiyu, zimulala, yibin87, nolouch | pkg/metrics/** |
| sig-approvers-br | BornChanger, 3pointer, YuJuncen, Leavrth | `br/**`, `br/pkg/storage/**` |
| sig-approvers-lightning | Benjamin2037, D3Hunter, gmhdbjd, OliverS929 | `lightning/**` (except where filtered), `lightning/cmd/tidb-lightning/**`, `lightning/cmd/tidb-lightning-ctl/**`, `br/pkg/storage/**`, `pkg/lightning/**` |
| sig-critical-approvers-tidb-lightning | yudongusa, BenMeadowcroft | `lightning/tidb-lightning.toml`, `pkg/lightning/config/**` |
| sig-approvers-dumpling | Benjamin2037, gmhdbjd, D3Hunter | dumpling/** |
| sig-approvers-executor | windtalker, XuHuaiyu, zanmato1984 | pkg/executor/** |
| sig-approvers-import | D3Hunter, gmhdbjd, Benjamin2037 | `cmd/importer/**`, `pkg/executor/importer/**`, `pkg/importsdk/**` |
| sig-approvers-distsql | windtalker, XuHuaiyu, zanmato1984, cfzjywxk, Benjamin2037 | pkg/distsql/** |
| sig-approvers-ddl | Benjamin2037, wjhuang2016, D3Hunter, gmhdbjd | `pkg/ddl/**`, `cmd/ddltest/**`, `pkg/ingestor/**`, `pkg/util/naming/**` |
| sig-approvers-disttask | Benjamin2037, D3Hunter, gmhdbjd, wjhuang2016 | pkg/disttask/** |
| sig-approvers-domain | D3Hunter, gmhdbjd, wjhuang2016 | pkg/domain/** |
| sig-approvers-infoschema | wjhuang2016, D3Hunter, Benjamin2037, gmhdbjd | pkg/infoschema/** |
| sig-approvers-meta | Benjamin2037, gmhdbjd, wjhuang2016, D3Hunter | pkg/meta/** |
| sig-approvers-owner | Benjamin2037, wjhuang2016, D3Hunter | pkg/owner/** |
| sig-approvers-table | Benjamin2037, cfzjywxk, gmhdbjd, wjhuang2016 | `pkg/table/**`, `pkg/tablecodec/**` |
| sig-approvers-lock | Benjamin2037, wjhuang2016 | pkg/lock/** |
| sig-approvers-parser | bb7133, Benjamin2037, BornChanger, D3Hunter | `pkg/parser/**` (except where filtered) |
| sig-critical-approvers-parser | yudongusa, BenMeadowcroft | `pkg/parser/parser.y` |
| sig-approvers-resourcemanager | Benjamin2037, D3Hunter, gmhdbjd, wjhuang2016 | pkg/resourcemanager/** |
| sig-approvers-docs | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | docs/** |
| sig-approvers-keyspace | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/keyspace/** |
| sig-approvers-kv | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/kv/** |
| sig-approvers-privilege | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/privilege/** |
| sig-approvers-server | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/server/** |
| sig-approvers-session | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | `pkg/session/**` (except where filtered) |
| sig-critical-approvers-tidb-server | yudongusa, BenMeadowcroft | `pkg/session/OWNERS`,<br>`pkg/session/bootstrap.go`,<br>`pkg/session/upgrade.go`,<br>`pkg/sessionctx/vardef/OWNERS`,<br>`pkg/sessionctx/vardef/sysvar.go`,<br>`pkg/sessionctx/vardef/tidb_vars.go`,<br>`pkg/sessionctx/variable/OWNERS`,<br>`pkg/sessionctx/variable/sysvar.go`,<br>`pkg/sessionctx/variable/session.go`,<br>`pkg/sessionctx/variable/tidb_vars.go`,<br>`pkg/config/OWNERS`,<br>`pkg/config/config.go`,<br>`pkg/config/config.toml.example` |
| sig-approvers-sessionctx | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/sessionctx/** (except subfolders with their own filters) |
| sig-approvers-sessiontxn | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/sessiontxn/** |
| sig-approvers-store | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/store/** |
| sig-approvers-telemetry | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/telemetry/** |
| sig-approvers-testkit | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/testkit/** |
| sig-approvers-timer | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/timer/** |
| sig-approvers-ttl | Benjamin2037, XuHuaiyu, bb7133, cfzjywxk, fixdb, kolafish, niubell, terry1purcell, zanmato1984, zhangjinpeng87 | pkg/ttl/** |
| sig-approvers-extension | bb7133, YangKeao | pkg/extension/** |
| sig-approvers-plugin | bb7133, YangKeao | cmd/pluginpkg/** |
| sig-approvers-stats | mjonss, 0xPoe, time-and-fate, terry1purcell | pkg/statistics/** |
| sig-approvers-autoid-service | bb7133, tiancaiamao | pkg/autoid_service/** |
| sig-approvers-expression | windtalker, XuHuaiyu, zanmato1984 | pkg/expression/** |

## Scopes view

| Scope (folder/file) | Who can approve |
|---|---|
| / (default, files not covered by more specific OWNERS that inherit) | sig-community-approvers |
| OWNERS_ALIASES | sig-approvers-modules |
| go.mod | sig-critical-approvers-dep |
| br/** | sig-approvers-br |
| br/pkg/storage/** | sig-approvers-br, sig-approvers-lightning |
| cmd/ddltest/** | sig-approvers-ddl |
| cmd/importer/** | sig-approvers-import |
| cmd/pluginpkg/** | sig-approvers-plugin |
| docs/** | sig-approvers-docs |
| dumpling/** | sig-approvers-dumpling |
| lightning/** (except where filtered) | sig-approvers-lightning |
| lightning/tidb-lightning.toml | sig-critical-approvers-tidb-lightning |
| lightning/cmd/tidb-lightning/** | sig-approvers-lightning |
| lightning/cmd/tidb-lightning-ctl/** | sig-approvers-lightning |
| pkg/** (fallback for modules without dedicated OWNERS) | sig-approvers-modules |
| pkg/autoid_service/** | sig-approvers-autoid-service |
| pkg/bindinfo/** | sig-approvers-planner |
| pkg/config/OWNERS | sig-critical-approvers-tidb-server |
| pkg/config/config.go | sig-critical-approvers-tidb-server |
| pkg/config/config.toml.example | sig-critical-approvers-tidb-server |
| pkg/ddl/** | sig-approvers-ddl |
| pkg/distsql/** | sig-approvers-distsql |
| pkg/disttask/** | sig-approvers-disttask |
| pkg/domain/** | sig-approvers-domain |
| pkg/executor/** | sig-approvers-executor |
| pkg/executor/importer/** | sig-approvers-import |
| pkg/expression/** | sig-approvers-expression |
| pkg/extension/** | sig-approvers-extension |
| pkg/importsdk/** | sig-approvers-import |
| pkg/infoschema/** | sig-approvers-infoschema |
| pkg/ingestor/** | sig-approvers-ddl |
| pkg/keyspace/** | sig-approvers-keyspace |
| pkg/kv/** | sig-approvers-kv |
| pkg/lightning/** | sig-approvers-lightning |
| pkg/lightning/config/** | sig-critical-approvers-tidb-lightning |
| pkg/lock/** | sig-approvers-lock |
| pkg/meta/** | sig-approvers-meta |
| pkg/metrics/** | sig-approvers-metrics |
| pkg/owner/** | sig-approvers-owner |
| pkg/parser/** (except where filtered) | sig-approvers-parser |
| pkg/parser/parser.y | sig-critical-approvers-parser |
| pkg/planner/** | sig-approvers-planner |
| pkg/privilege/** | sig-approvers-privilege |
| pkg/resourcemanager/** | sig-approvers-resourcemanager |
| pkg/server/** | sig-approvers-server |
| pkg/session/** (except where filtered) | sig-approvers-session |
| pkg/session/OWNERS | sig-critical-approvers-tidb-server |
| pkg/session/bootstrap.go | sig-critical-approvers-tidb-server |
| pkg/session/upgrade.go | sig-critical-approvers-tidb-server |
| pkg/sessionctx/** (base) | sig-approvers-sessionctx |
| pkg/sessionctx/vardef/OWNERS | sig-critical-approvers-tidb-server |
| pkg/sessionctx/vardef/sysvar.go | sig-critical-approvers-tidb-server |
| pkg/sessionctx/vardef/tidb_vars.go | sig-critical-approvers-tidb-server |
| pkg/sessionctx/variable/OWNERS | sig-critical-approvers-tidb-server |
| pkg/sessionctx/variable/sysvar.go | sig-critical-approvers-tidb-server |
| pkg/sessionctx/variable/session.go | sig-critical-approvers-tidb-server |
| pkg/sessionctx/variable/tidb_vars.go | sig-critical-approvers-tidb-server |
| pkg/sessiontxn/** | sig-approvers-sessiontxn |
| pkg/statistics/** | sig-approvers-stats |
| pkg/store/** | sig-approvers-store |
| pkg/table/** | sig-approvers-table |
| pkg/tablecodec/** | sig-approvers-table |
| pkg/telemetry/** | sig-approvers-telemetry |
| pkg/testkit/** | sig-approvers-testkit |
| pkg/timer/** | sig-approvers-timer |
| pkg/ttl/** | sig-approvers-ttl |
| pkg/util/naming/** | sig-approvers-ddl |
