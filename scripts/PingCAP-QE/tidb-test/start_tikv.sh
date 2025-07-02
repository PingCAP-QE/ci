#! /usr/bin/env bash

chmod +x bin/pd-server bin/tikv-server
bin/pd-server --name=pd --data-dir=pd &> pd.log &
echo "wait pd ready..."
sleep 10
bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &> tikv.log &
echo "wait tikv ready..."
sleep 10
