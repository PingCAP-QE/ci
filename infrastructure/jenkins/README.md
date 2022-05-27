Guide for jenkins helm chart value configuration
===


## Pre requests

- Setup secret `github-oauth2` in namespace `jenkins`, the secret contains keys: 
  - `client-id`
  - `client-secret`

## values guide and schema

- [jenkins chart values summary doc](https://github.com/jenkinsci/helm-charts/blob/main/charts/jenkins/VALUES_SUMMARY.md)
- [jenkins chart values default template](https://github.com/jenkinsci/helm-charts/blob/main/charts/jenkins/values.yaml)

## Checklist

1. values between all `values-*.yaml` should not be conflict.
