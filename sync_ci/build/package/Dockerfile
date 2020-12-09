# Builder image
FROM golang:1.15-alpine as builder

RUN apk add --no-cache \
    make \
    git \
    gcc \
    musl-dev

RUN mkdir -p /go/src/github.com/PingCAP-QE/ci/sync_ci
WORKDIR /go/src/github.com/PingCAP-QE/ci/sync_ci

COPY go.mod .
COPY go.sum .

RUN GO111MODULE=on go mod download

COPY . .
RUN make
# Executable image
FROM alpine

COPY --from=builder /go/src/github.com/PingCAP-QE/ci/sync_ci/bin/sync-ci-service /sync-ci-service

WORKDIR /

EXPOSE 36000

ENTRYPOINT ["/sync-ci-service"]