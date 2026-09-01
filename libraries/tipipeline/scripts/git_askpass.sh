#!/bin/sh

case "${1-}" in
Username*)
    printf '%s\n' "${TIPIPELINE_GIT_USERNAME-}"
    ;;
Password*)
    printf '%s\n' "${TIPIPELINE_GIT_PASSWORD-}"
    ;;
*)
    exit 1
    ;;
esac
