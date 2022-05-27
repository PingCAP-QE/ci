CI config repo for PingCAP
===

Using GitOps to maintain all CI infrastructures and jobs.

## How to Setup it in you k8s cluster

Before all please fork it into you personal account or organization.
### Pre require

#### GitOps Tools

- Flux CLI
  > Install by bash or download release binary from [Flux site](https://fluxcd.io/docs/get-started/#install-the-flux-cli)
#### cluster secret data

#### Github private token

Create a github private token with repo permissions, copy and write it.
See [doc](https://fluxcd.io/docs/get-started/#before-you-begin).

- secrets for jenkins component
  > see [here](infrastructure/jenkins/README.md)
- other `WIP`

### Setup GitOps

```bash
export GITHUB_TOKEN=<github private token>
export GITHUB_REPOSITORY_OWNER=<github org or username>
flux check --pre
flux bootstrap github \
    --owner=${GITHUB_REPOSITORY_OWNER} \
    --repository=<your repo name> \
    --branch=main \
    --path=clusters/staging # or other cluster dir.
```

if you repo in under personal account, you should add cli option `--personal`.
