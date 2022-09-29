#! /usr/bin/env bash

# export BUCKET_HOST=localhost
# export BUCKET_NAME=ci-pipeline-cache
# export BUCKET_PORT="8080"
# export BUCKET_REGION=""
# export BUCKET_SUBREGION=""
# export AWS_ACCESS_KEY_ID=5U0NDDNF3K6LQ6A8DJUZ
# export AWS_SECRET_ACCESS_KEY=LeISRELeaCqbwjQxRlv4IXSBWwD5k0KfLFiJjqix


export BUCKET_HOST=localhost
export BUCKET_NAME=ci-bazel-remote-cache
export BUCKET_PORT="8080"
export BUCKET_REGION=""
export BUCKET_SUBREGION=""
export AWS_ACCESS_KEY_ID=XPDQX692T1W1TDHAPCGS
export AWS_SECRET_ACCESS_KEY=XPDQX692T1W1TDHAPCGS

# key=tmp-2
# deno run --allow-all scripts/plugins/s3-cache.ts --op=restore --key=${key} --key-prefix='tmp-' --path=tmp
# echo '--------------'
# deno run --allow-all scripts/plugins/s3-cache.ts --op=remove  --key=${key}
# echo '--------------'
# deno run --allow-all scripts/plugins/s3-cache.ts --op=backup  --key=${key} --key-prefix='tmp-' --path=tmp --keep-count=1
# echo '=============='

deno run --allow-all scripts/plugins/s3-cache.ts --op=shrink --keep-size-g=900 --key-prefix=data/
