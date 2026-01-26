def prepareIntegrationTestCommonBinariesWithCacheLock(refs, cacheType = 'binary') {
    final cacheKey = prow.getCacheKey(cacheType, refs, 'it-common')
    lock(cacheKey) {
        cache(path: "./bin", includes: 'cdc,cdc.test', key: cacheKey) {
            sh label: "build common binaries", script: """
                [ -f ./bin/cdc ] || make cdc
                [ -f ./bin/cdc.test ] || make integration_test_build
                ls -alh ./bin
                ./bin/cdc version
            """
        }
    }
}

def prepareIntegrationTestPulsarConsumerBinariesWithCacheLock(refs, cacheType = 'binary') {
    final cacheKey = prow.getCacheKey(cacheType, refs, 'it-pulsar-consumer')
    lock(cacheKey) {
        cache(path: "./bin", includes: 'cdc_pulsar_consumer', key: cacheKey) {
            sh '[ -f ./bin/cdc_pulsar_consumer ] || make pulsar_consumer'
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
