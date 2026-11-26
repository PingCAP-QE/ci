CI Jobs
===

## Run before merged

| Job name                                                                                              | Description                                   | Trigger comment in PR                    | CI script                                                                       | Can be run locally by contributors | Core Instructions to run locally                                                                                                         | Runner resouce requirement                                                        |
| ----------------------------------------------------------------------------------------------------- | --------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| [pull_cdc_integration_kafka_test](/jenkins/jobs/pingcap/tiflow/latest/pull_cdc_integration_kafka_test/dsl.groovy) | Run cdc integration test with kafka sink type | `/test cdc-integration-kafka-test`       | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_cdc_integration_kafka_test/Jenkinsfile) | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/tests/integration_tests#run-integration-tests-locally)      | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_cdc_integration_kafka_test/pod-test.yaml) |
| [pull_cdc_integration_test](/jenkins/jobs/pingcap/tiflow/latest/pull_cdc_integration_test/dsl.groovy)             | Run cdc integration test with mysql sink type | `/test /test cdc-integration-mysql-test` | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_cdc_integration_test/Jenkinsfile)       | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/tests/integration_tests#run-integration-tests-locally)      | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_cdc_integration_test/pod-test.yaml)       |
| [pull_dm_compatibility_test](/jenkins/jobs/pingcap/tiflow/latest/pull_dm_compatibility_test/dsl.groovy)           | Run DM Compatibility Test                     | `/test dm-compatibility-test`            | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_dm_compatibility_test/Jenkinsfile)      | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/dm/tests#compatibility-test)                                | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_dm_compatibility_test/pod.yaml)      |
| [pull_dm_integration_test](/jenkins/jobs/pingcap/tiflow/latest/pull_dm_integration_test/dsl.groovy)               | Run DM Integration Test                       | `/test dm-integration-test`              | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_dm_integration_test/Jenkinsfile)        | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/dm/tests#integration-test)                                  | [link](/jenkins/jobs/pingcap/tiflow/latest/pull_dm_integration_test/pod.yaml)        |

### Optional MySQL 8.4 compatibility trial

After the staging job has been deployed, comment
`/test pull-dm-integration-test-mysql84` on a TiFlow PR targeting `master`.
The [staging job](/jenkins/jobs/pingcap/tiflow/staging/pull_dm_integration_test_mysql84/Jenkinsfile)
uses MySQL 5.7 on port 3306 and MySQL **8.4.13.1**, pinned by image digest,
on port 3307. MariaDB remains unchanged. It runs G00–G11 and TLS_GROUP.

The independent `pull-dm-integration-test-mysql84` status is **optional**:
failures remain visible, but it is not a merge gate. The existing required
`pull-dm-integration-test` job keeps MySQL 8.0.21. Do not add the trial's
context to branch protection or supplemental required Tide contexts.
The trial does not run automatically on file changes or before merge.

Every manual trial runs all groups without reusing passed-stage markers;
one group's failure does not cancel the other groups. Binary caches have a
separate key. Database versions are printed, including `VERSION_WITH_UPDATE()`
on MySQL 8.4, and available DM logs are archived on failure.

This adds test infrastructure, not a claim of MySQL 8.4 compatibility. In
particular, existing TiFlow tests still use `RESET MASTER` and
`SHOW MASTER STATUS`, which MySQL 8.4 removed. Fix those in the TiFlow PR
under test, preserving the MySQL 5.7/MariaDB paths; this pipeline does not
rewrite tests, disable resets, or turn failures into success. The MySQL 8.4
server temporarily retains native-password authentication to keep the server
upgrade separate from a client/authentication migration.

Before considering promotion, record a real staging build and confirm that
its pending/failed status does not block merging. Promotion requires a
separate reviewed change.

Others are refactoring, comming soon.

## Run after merged

> Refactoring, coming soon.
