#!/bin/bash

./down.sh

echo "Delete the docker volumes: "
docker volume rm development_db-data
docker volume rm development_postgres-tls
docker volume rm development_gitea-data

echo "Stop and delete minikube cluster"
minikube stop
minikube delete

echo "Done purge"
