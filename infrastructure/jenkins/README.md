Guide for jenkins helm chart value configuration
===

## Pre requests

- Setup secret `google-oauth2` in namespace `jenkins`, the secret contains keys: 
  - `client-id`
  - `client-secret`
- Setup PVC `jenkins` in namespace `jenkins`
  - size should bigger than 8Gi.
  - if replicas > 1, the PVC access mode should be `ReadWriteMany` to support jenkins server HA mode.

## values guide and schema

- [jenkins chart values summary doc](https://github.com/jenkinsci/helm-charts/blob/main/charts/jenkins/VALUES_SUMMARY.md)
- [jenkins chart values default template](https://github.com/jenkinsci/helm-charts/blob/main/charts/jenkins/values.yaml)

## Checklist

1. values between all `values-*.yaml` should not be conflict.
