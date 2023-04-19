CI Jobs
===

## Run before merged

| Job name                                                     | Description                                   | Trigger comment in PR                    | CI script                                                    | Can be run locally by contributors | Core Instructions to run locally                             | Runner resouce requirement                                   |
| ------------------------------------------------------------ | --------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------ | ---------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| [pull_cdc_integration_kafka_test](./pull_cdc_integration_kafka_test.groovy) | Run cdc integration test with kafka sink type | `/test cdc-integration-kafka-test`       | [link](/pipelines/pingcap/tiflow/release-7.1/pull_cdc_integration_kafka_test.groovy) | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/tests/integration_tests#run-integration-tests-locally) | [link](/pipelines/pingcap/tiflow/release-7.1/pod-pull_cdc_integration_kafka_test.yaml) |
| [pull_cdc_integration_test](pull_cdc_integration_test.groovy) | Run cdc integration test with mysql sink type | `/test /test cdc-integration-mysql-test` | [link](/pipelines/pingcap/tiflow/release-7.1/pull_cdc_integration_test.groovy) | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/tests/integration_tests#run-integration-tests-locally) | [link](/pipelines/pingcap/tiflow/release-7.1/pod-pull_cdc_integration_test.yaml) |
| [pull_dm_compatibility_test](pull_dm_compatibility_test.groovy) | Run DM Compatibility Test                     | `/test dm-compatibility-test`            | [link](/pipelines/pingcap/tiflow/release-7.1/pull_dm_compatibility_test.groovy) | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/dm/tests#compatibility-test) | [link](/pipelines/pingcap/tiflow/release-7.1/pod-pull_dm_compatibility_test.yaml) |
| [pull_dm_integration_test](pull_dm_integration_test.groovy)  | Run DM Integration Test                       | `/test dm-integration-test`              | [link](/pipelines/pingcap/tiflow/release-7.1/pull_dm_integration_test.groovy) | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/dm/tests#integration-test) | [link](/pipelines/pingcap/tiflow/release-7.1/pod-pull_dm_integration_test.yaml) |
| [pull_engine_integration_test](pull_engine_integration_test.groovy) | Run Engine  Integration Test                  | `/test engine-integration-test`          | [link](/pipelines/pingcap/tiflow/release-7.1/pull_engine_integration_test.groovy) | Yes                                | [Please refer to the document](https://github.com/pingcap/tiflow/tree/master/engine/test/integration_tests#run-engine-integration-tests) | [link](/pipelines/pingcap/tiflow/release-7.1/pod-pull_engine_integration_test.yaml) |

Others are refactoring, comming soon.

## Run after merged

> Refactoring, coming soon.