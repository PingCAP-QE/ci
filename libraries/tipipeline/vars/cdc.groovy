def prepareIntegrationTestCommonBinariesWithCacheLock(refs, cacheType = 'binary') {
    final cacheKey = prow.getCacheKey(cacheType, refs, 'it-common')
    lock(cacheKey) {
        cache(path: "./bin", includes: 'cdc,cdc.test', key: cacheKey) {
            sh label: "build common binaries", script: """
                [ -f ./bin/cdc ] && [ -f ./bin/cdc.test ] || make integration_test_build
            """
        }
    }
}

def prepareIntegrationTestPulsarConsumerBinariesWithCacheLock(refs, cacheType = 'binary') {
    final cacheKey = prow.getCacheKey(cacheType, refs, 'it-pulsar-consumer')
    lock(cacheKey) {
        cache(path: "./bin", includes: 'cdc_pulsar_consumer,oauth2_server', key: cacheKey) {
            sh '[ -f ./bin/cdc_pulsar_consumer ] || make pulsar_consumer'
            sh '[ -f ./bin/oauth2-server ] || make oauth2_server'
        }
    }
}

def prepareIntegrationTestKafkaConsumerBinariesWithCacheLock(refs, cacheType = 'binary') {
    final cacheKey = prow.getCacheKey(cacheType, refs, 'it-kafka-consumer')
    lock(cacheKey) {
        cache(path: "./bin", includes: 'cdc_kafka_consumer', key: cacheKey) {
            sh '[ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer'
        }
    }
}

def prepareIntegrationTestStorageConsumerBinariesWithCacheLock(refs, cacheType = 'binary') {
    final cacheKey = prow.getCacheKey(cacheType, refs, 'it-storage-consumer')
    lock(cacheKey) {
        cache(path: "./bin", includes: 'cdc_storage_consumer', key: cacheKey) {
            sh '[ -f ./bin/cdc_storage_consumer ] || make storage_consumer'
        }
    }
}
