# Deploying the netatlas backend

The backend is a containerized Ktor service. The recommended hosting mirrors the
[Validatyr](../../validatyr) setup: **Google Cloud Run** for the container + a managed
**Postgres + PostGIS** database (Supabase free tier). The app is already cloud-ready —
`Application.kt` reads `PORT`, `JDBC_URL`, `DB_USER`, `DB_PASSWORD` from the environment.

```
Android app ──HTTPS──> Cloud Run (Ktor container) ──JDBC──> Supabase Postgres + PostGIS
   (Server URL set                 us-central1                 (PostGIS extension enabled)
    via the in-app gear)
```

## Current deployment

| | |
|---|---|
| **Service** | `https://netatlas-backend-872879151769.asia-south1.run.app` (Cloud Run, `asia-south1`) |
| **GCP project** | `validatyr-2026` |
| **Database** | Reuses the `validatyr` Supabase project (`qgdpnxbmomnjlezfsabu`) — netatlas lives in an **isolated `netatlas` schema** owned by a dedicated `netatlas_app` role, with no access to validatyr's tables. Validatyr connects via Supabase API keys (PostgREST), so it's unaffected. |
| **Connection** | Session pooler `aws-1-ap-south-1.pooler.supabase.com:5432`, user `netatlas_app.<ref>`, `sslmode=require`. The DB password lives only in the Cloud Run env vars (never committed). |

> Why schema-reuse instead of a dedicated Supabase project: the free tier caps an account
> at 2 active projects, which was already full. The dedicated-schema approach keeps it free
> and isolated. To split it out later, create a new Supabase project and re-run the migrations.
>
> Health-check note: `/healthz` may be intercepted by some corporate networks (a common
> probe path) — use `/carriers` or `/hexes?...` to verify the service instead.

## Prerequisites
- `gcloud` authenticated (`gcloud auth login`) on a project with **billing enabled**.
- Docker running (the image is built locally and pushed — Cloud Run runs `linux/amd64`,
  which `deploy.sh` builds explicitly).

## 1. Database — Supabase (free, like Validatyr)
1. Create a project at https://supabase.com (free tier).
2. Enable PostGIS: SQL editor → `create extension if not exists postgis;`
   (the app also runs this in `V1__enable_postgis.sql` on boot, but enabling it once
   up front avoids a permissions surprise).
3. Get the connection string: Project Settings → Database → Connection string (JDBC),
   or compose it: `jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres`
   with user `postgres` and your DB password.

> **Pooler note:** for low-traffic POC use the **direct** connection (port 5432). The
> Supabase *transaction* pooler (port 6543) doesn't support all session/prepared-statement
> features Exposed uses.

### Alternative — GCP Cloud SQL (no extra account, but paid)
```bash
gcloud sql instances create netatlas-db --database-version=POSTGRES_16 \
  --tier=db-f1-micro --region=us-central1
gcloud sql databases create netatlas --instance=netatlas-db
# enable PostGIS once connected:  CREATE EXTENSION postgis;
```
Then point `JDBC_URL` at the instance (via the Cloud SQL connector or public IP).

## 2. Deploy to Cloud Run
```bash
GCP_PROJECT=<your-project-id> \
JDBC_URL='jdbc:postgresql://db.<ref>.supabase.co:5432/postgres' \
DB_USER='postgres' \
DB_PASSWORD='<your-db-password>' \
backend/deploy.sh
```
The script: enables the Run + Artifact Registry APIs, creates an `netatlas` Artifact
Registry repo (once), builds the fat jar, builds & pushes a `linux/amd64` image, and
deploys the service. It prints the public URL and `/healthz`.

## 3. Seed (optional) & point the app
```bash
# seed the deployed backend with demo data:
NETATLAS_URL='https://<service>-<hash>-uc.a.run.app' ./gradlew :backend:seed
```
In the app, tap the **gear** in the filter bar and set **Server URL** to the Cloud Run
URL. Collected readings upload there; the heatmap reads from it.

## Notes
- `--allow-unauthenticated` makes the API public (it's read-mostly + anonymous ingest for
  the POC). Add auth / rate limiting before any real launch.
- Migrations run on startup from `backend/db/migrations` (copied into the image).
- Cold starts: `--min-instances 0` keeps it free when idle; bump to 1 to avoid the first
  request's cold start.
