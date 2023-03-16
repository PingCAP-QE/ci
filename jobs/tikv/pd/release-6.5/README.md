CI Jobs
===

## Run before merged

> The runner requirement: `4` core cpu + `8GB` memory 
 
| Job name                          | Description                  | Trigger comment in PR | CI script                                                | Can be run locally by contributors | Core Instructions to run locally  |
| --------------------------------- | ---------------------------- | --------------------- | -------------------------------------------------------- | ---------------------------------- | --------------------------------- |
| [ghpr_build](./ghpr_build.groovy) | lint check and build binary. | `/test build`         | [link](/pipelines/tikv/pd/release-6.5/ghpr_build.groovy) | yes                                | run `make` and `WITH_RACE=1 make` |

Others are refactoring, comming soon.

## Run after merged

> Refactoring, coming soon.