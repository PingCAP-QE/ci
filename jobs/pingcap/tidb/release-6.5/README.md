CI Jobs
===

## Run before merged

> The runner requirement: `64` core cpu + `128GB` memory 
 
| Job name                                  | Description                               | Trigger comment in PR | CI script                                                          | Can be run locally by contributors | Core Instructions to run locally                                                                                                                                                         |
| ----------------------------------------- | ----------------------------------------- | --------------------- | ------------------------------------------------------------------ | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [ghpr_build](./ghpr_build.groovy)         | lint check and build binary.              | `/test build`         | [link](/pipelines/pingcap/tidb/release-6.5/ghpr_build.groovy)      | yes                                | run `make bazel_build`                                                                                                                                                                   |
| [ghpr-unit-test](./ghpr_unit_test.groovy) | Unit/Func tests                           | `/test unit-test`     | [link](/pipelines/pingcap/tidb/release-6.5/ghpr_unit_test.groovy)  | yes                                | run `make bazel_coverage_test`                                                                                                                                                       | yes |
| [ghpr-check-dev](ghpr_check.groovy)       | More static checks.                       | `/test check-dev`     | [link](/pipelines/pingcap/tidb/release-6.5/ghpr_check.groovy)      | yes                                | run `make gogenerate check explaintest`                                                                                                                                                  |
| [ghpr-check-dev2](ghpr_check2.groovy)     | Basic function tests                      | `/test check-dev2`    | [link](/pipelines/pingcap/tidb/release-6.5/ghpr_check2.groovy)     | no                                 | Run the scripts in `scripts/pingcap/tidb` folder of  `pingcap-qe/ci` repo, [detail](https://github.com/PingCAP-QE/ci/blob/main/pipelines/pingcap/tidb/latest/ghpr_check2.groovy#L82~L89) |
| [ghpr-mysql-test](ghpr_mysql_test.groovy) | Test for compatibility for mysql protocol | `/test mysql-test`    | [link](/pipelines/pingcap/tidb/release-6.5/ghpr_mysql_test.groovy) | no                                 | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                 |

## Run after merged

> Refactoring, coming soon.
