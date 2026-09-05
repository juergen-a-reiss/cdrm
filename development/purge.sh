#!/bin/bash

# `minikube dashboard`'s own kubectl proxy survives `minikube delete` — left running, it
# holds port 1964 for a cluster that no longer exists, and the next `minikube dashboard
# --port=1964` then fails with HOST_KUBECTL_PROXY: kubectl proxy: readByteWithTimeout: EOF.
pkill -f "minikube dashboard --port=1964"
lsof -ti:1964 -sTCP:LISTEN | xargs -r kill

./down.sh

echo "Delete the docker volumes: "
docker volume rm development_db-data
docker volume rm development_gitea-data

echo "Stop and delete minikube cluster"
minikube stop
minikube delete

echo "Done purge"
