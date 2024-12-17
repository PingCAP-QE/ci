# Building Docker Images from Source

This guide explains how to build Docker images for PingCAP components from
source code for develop purposes.

## Dockerfile Locations

| Repo                                                 | Develop Dockerfile in repo                                                                                                                                                                              | CD Ref Dockerfile                                                                            |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| [TiDB](https://github.com/pingcap/tidb)              | [`Dockerfile`](https://github.com/pingcap/tidb/blob/master/Dockerfile)                                                                                                                                  | https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/tidb/Dockerfile    |
| [TiFlow (CDC,DM)](https://github.com/pingcap/tiflow) | [`deployments/ticdc/docker/Dockerfile`](https://github.com/pingcap/tiflow/blob/master/deployments/ticdc/docker/Dockerfile) [`dm/Dockerfile`](https://github.com/pingcap/tiflow/blob/master/dm/Dockerfile) `To be updated` | https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/tiflow/Dockerfile  |
| [TiFlash](https://github.com/pingcap/tiflash)        | Not yet                                                                                                                                                                                                 | https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/tiflash/Dockerfile |
| [TiKV](https://github.com/tikv/tikv)                 | [`Dockerfile`](https://github.com/tikv/tikv/blob/master/Dockerfile) `To be updated`                                                                                                                     | https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/tikv/Dockerfile    |
| [PD](https://github.com/tikv/pd)                     | [`Dockerfile`](https://github.com/tikv/pd/blob/master/Dockerfile) `To be updated`                                                                                                                       | https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/pd/Dockerfile      |
