#!/bin/bash

location="us-central1"
repository="repository"
projectId="fhir-project-id"
projectName="cql-translation-service"
appDir="."

# only needed the first time
# gcloud auth login

#  Because I always forget this
gcloud config set project $projectId

cd $appDir && 

# Build the docker container
docker build -t $projectName .

# Define the registry location
registryLocation="$location-docker.pkg.dev/$projectId/$repository/$projectName"

# tag the docker container
docker tag $projectName $registryLocation

# push the tagged image into the artifact registry
docker push $registryLocation

# # deploy on google cloud
gcloud run deploy $projectName --image $registryLocation