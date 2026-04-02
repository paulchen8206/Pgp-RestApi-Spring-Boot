# PGP REST API Spring Boot Demo

This project contains:

- A Spring Boot + Spark Structured Streaming process (`pgp-client`) that continuously calls a remote API over mutual TLS and publishes Kafka events.
- A mock remote PGP REST API server (`mock-pgp-api`) running in Docker.
- A MinIO object store attached to the mock PGP API, storing employee records.
- A long-running employee data generator (`employee-data-generator`) that continuously refreshes `employees.json` locally and in MinIO.
- A LocalStack key-management layer storing enterprise-style PKI and PGP artifacts in SSM Parameter Store paths.
- Bootstrap sync containers that pull managed artifacts from LocalStack into runtime volumes.

## Architecture

1. `localstack-seed` loads enterprise certificate and PGP materials from `enterprise-materials/` into LocalStack SSM paths.
2. `cert-sync` downloads PKI materials from `/enterprise/pki/*` into `certs-data` volume.
3. `pgp-sync` downloads OpenPGP materials from `/enterprise/pgp/*` into `pgp-data` volume.
4. `minio-seed` creates `employee-data` bucket and uploads `employees.json`.
5. `employee-data-generator` continuously regenerates employee data and uploads the latest `employees.json` into MinIO.
6. `pgp-client` starts a Spark Structured Streaming job using Spark's `rate` source.
7. For each micro-batch, `pgp-client` derives employee IDs, encrypts request data, and calls the remote API.
8. `mock-pgp-api` decrypts request, fetches employee data (including SSN and salary) from MinIO, then encrypts response using client public key.
9. `pgp-client` decrypts the response and publishes schema-managed Kafka events to `employee_decrypted` and `emplyee_encrypted`.

### Managed key paths (LocalStack SSM)

- `/enterprise/pki/ca.crt`
- `/enterprise/pki/server.crt`
- `/enterprise/pki/server.key`
- `/enterprise/pki/client.crt`
- `/enterprise/pki/client.key`
- `/enterprise/pki/client.p12`
- `/enterprise/pki/truststore.jks`
- `/enterprise/pgp/client-public.asc`
- `/enterprise/pgp/client-private.asc`
- `/enterprise/pgp/server-public.asc`
- `/enterprise/pgp/server-private.asc`

## Run

Local configuration:

- `.env` is intentionally ignored by Git for local overrides.
- Start from the tracked template:

```bash
cp .env.example .env
```

```bash
docker compose up --build
```

After startup, inspect the streaming processor logs:

```bash
docker compose logs -f pgp-client
```

Kafka UI for topic monitoring:

```bash
open http://localhost:18085
```

Schema Registry endpoint:

```bash
open http://localhost:8081
curl "http://localhost:8081/subjects"
```

Expected `pgp-client` log activity:

```text
Processed streaming event for employeeId=E1001 batchId=1
Processed streaming event for employeeId=E1002 batchId=1
Processed streaming event for employeeId=E1003 batchId=1
```

The streaming processor cycles over employee IDs defined in `app.streaming.employee-ids` and publishes Avro records continuously.

## Generate mock employee data

The mock API reads employee records from `mock-server/data/employees.json`. To regenerate this file:

```bash
cd mock-server
python generate_employees.py --count 200 --start-id 1001 --output data/employees.json
```

Options:

- `--count`: number of records to generate (default `100`)
- `--start-id`: starting numeric ID used to build `employeeId` values like `E1001`
- `--seed`: random seed for deterministic output (default `42`)
- `--output`: destination file path (default `data/employees.json`)

Docker Compose also runs `employee-data-generator` continuously. It rewrites `mock-server/data/employees.json` and uploads the refreshed file to MinIO on the interval defined by `EMPLOYEE_GENERATOR_INTERVAL_SECONDS` in `.env`.

When `MINIO_ATTACH_RANDOM_NAME=true` (enabled in Compose), each cycle also uploads an attachment copy using a random run-based object name under `MINIO_RANDOM_PREFIX` (default `attachments`).

Default `.env` value:

```bash
EMPLOYEE_GENERATOR_INTERVAL_SECONDS=10
```

To inspect its activity:

```bash
docker compose logs --tail=100 employee-data-generator
```

Example attachment object name:

```text
employee-data/attachments/employees-1712050241-a1b2c3d4e5.json
```

## Notes

- Certificate trust and client auth are enforced by the mock server (`ssl.CERT_REQUIRED`).
- The Java app and mock API read runtime files from Docker volumes (`certs-data`, `pgp-data`) after LocalStack sync.
- Enterprise source materials are kept under `enterprise-materials/` and can be replaced with real CA-issued and centrally managed assets.
- Employee dataset is stored in MinIO object storage (`employee-data/employees.json`) and retrieved by the PGP server per request.
- For a production setup, use real PKI, rotate certificates, protect private keys, and add PGP signing/verification in addition to encryption.
