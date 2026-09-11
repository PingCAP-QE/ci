#! /usr/bin/env bash

function wait_for_http() {
    local url="$1"
    local name="$2"

    for attempt in $(seq 1 30); do
        if curl -fsS --max-time 2 "${url}" >/dev/null 2>&1; then
            echo "${name} is ready"
            return 0
        fi
        echo "Waiting for ${name}... (attempt ${attempt}/30)"
        sleep 1
    done

    echo "${name} did not become ready: ${url}" >&2
    return 1
}

chmod +x bin/pd-server bin/tikv-server
bin/pd-server --name=pd --data-dir=pd &> pd.log &
wait_for_http "http://127.0.0.1:2379/pd/api/v1/health" "PD" || exit 1
bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &> tikv.log &
echo "wait tikv ready..."
sleep 10
