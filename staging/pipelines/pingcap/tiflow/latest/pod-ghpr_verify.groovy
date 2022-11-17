apiVersion: v1
kind: Pod
spec:
  securityContext:
    fsGroup: 1000
  containers:
    - name: golang
      image: "hub.pingcap.net/jenkins/centos7_golang-1.19:latest"
      tty: true
      resources:
        requests:
          memory: 12Gi
          cpu: "4"
        limits:
          memory: 16Gi
          cpu: "6"
    - name: net-tool
      image: wbitt/network-multitool
      tty: true
      resources:
        limits:
          memory: 128Mi
          cpu: 100m
    - name: report
      image: hub.pingcap.net/jenkins/python3-requests:latest
      tty: true
      resources:
        limits:
          memory: 256Mi
          cpu: 100m