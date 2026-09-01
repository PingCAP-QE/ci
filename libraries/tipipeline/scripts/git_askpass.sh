#!/bin/sh
case "$1" in
Username*) echo "$TIPIPELINE_GIT_USERNAME" ;;
Password*) echo "$TIPIPELINE_GIT_PASSWORD" ;;
esac
