#!/usr/bin/env bash
# Deploy the netatlas backend to Google Cloud Run.
#
# Mirrors the Validatyr pattern (container -> Cloud Run) but for the Ktor app, with a
# managed Postgres+PostGIS (Supabase, or any Postgres reachable over the internet).
#
# The Gradle build pulls in the Android :composeApp module, so we build the fat jar on
# this machine (Android SDK present) and ship that jar in the image — rather than
# building inside Cloud Build, which has no Android SDK.
#
# Cloud Run runs linux/amd64, so the image is built with --platform linux/amd64
# (matters on Apple Silicon, which defaults to arm64).
#
# Usage:
#   GCP_PROJECT=my-proj \
#   JDBC_URL='jdbc:postgresql://db.<ref>.supabase.co:5432/postgres' \
#   DB_USER='postgres' DB_PASSWORD='********' \
#   backend/deploy.sh
#
# Optional env: REGION (default us-central1), AR_REPO (default netatlas),
#               SERVICE (default netatlas-backend), IMAGE_TAG (default: git short sha).
set -euo pipefail

REGION="${REGION:-us-central1}"
AR_REPO="${AR_REPO:-netatlas}"
SERVICE="${SERVICE:-netatlas-backend}"
IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || echo latest)}"

: "${GCP_PROJECT:?set GCP_PROJECT to your Google Cloud project id}"
: "${JDBC_URL:?set JDBC_URL (e.g. jdbc:postgresql://db.<ref>.supabase.co:5432/postgres)}"
: "${DB_USER:?set DB_USER}"
: "${DB_PASSWORD:?set DB_PASSWORD}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${REGION}-docker.pkg.dev/${GCP_PROJECT}/${AR_REPO}/${SERVICE}:${IMAGE_TAG}"

echo "==> project=${GCP_PROJECT} region=${REGION} image=${IMAGE}"

echo "==> enabling required APIs (idempotent)"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  --project "${GCP_PROJECT}"

echo "==> ensuring Artifact Registry repo '${AR_REPO}' exists"
gcloud artifacts repositories describe "${AR_REPO}" --location "${REGION}" --project "${GCP_PROJECT}" >/dev/null 2>&1 \
  || gcloud artifacts repositories create "${AR_REPO}" --repository-format=docker \
       --location "${REGION}" --project "${GCP_PROJECT}" --description "netatlas images"

echo "==> building backend fat jar (on host)"
( cd "${REPO_ROOT}" && ./gradlew :backend:buildFatJar )

echo "==> building linux/amd64 image"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet
docker build --platform linux/amd64 -f "${REPO_ROOT}/backend/Dockerfile" -t "${IMAGE}" "${REPO_ROOT}"

echo "==> pushing image"
docker push "${IMAGE}"

echo "==> deploying to Cloud Run"
gcloud run deploy "${SERVICE}" \
  --image "${IMAGE}" \
  --project "${GCP_PROJECT}" \
  --region "${REGION}" \
  --platform managed \
  --allow-unauthenticated \
  --port 8080 \
  --cpu 1 --memory 512Mi \
  --min-instances 0 --max-instances 2 \
  --set-env-vars "JDBC_URL=${JDBC_URL},DB_USER=${DB_USER},DB_PASSWORD=${DB_PASSWORD}"

URL="$(gcloud run services describe "${SERVICE}" --project "${GCP_PROJECT}" --region "${REGION}" --format 'value(status.url)')"
echo "==> deployed: ${URL}"
echo "==> health:   ${URL}/healthz"
echo "Point the app's Server URL (gear icon) at: ${URL}"
