import argparse
import io
import json
import os
import random
from pathlib import Path
import time
import uuid

from minio import Minio

FIRST_NAMES = [
    "Alice", "Brian", "Carla", "David", "Elena", "Farid", "Grace", "Hector", "Iris", "Jamal",
    "Kira", "Liam", "Mina", "Noah", "Olivia", "Priya", "Quinn", "Rafael", "Sara", "Tariq",
]

LAST_NAMES = [
    "Nguyen", "Patel", "Gomez", "Kim", "Johnson", "Brown", "Davis", "Miller", "Lopez", "Wilson",
    "Anderson", "Thomas", "Jackson", "White", "Harris", "Martin", "Thompson", "Garcia", "Martinez", "Lee",
]

DEPARTMENTS_WITH_TITLES = {
    "Engineering": [
        "Software Engineer",
        "Senior Software Engineer",
        "Staff Engineer",
        "DevOps Engineer",
    ],
    "Finance": [
        "Financial Analyst",
        "Finance Manager",
        "Senior Accountant",
        "Controller",
    ],
    "HR": [
        "HR Coordinator",
        "HR Business Partner",
        "Recruiter",
        "People Operations Manager",
    ],
    "Sales": [
        "Account Executive",
        "Sales Manager",
        "Sales Development Rep",
        "Regional Director",
    ],
    "Marketing": [
        "Marketing Specialist",
        "Content Strategist",
        "Brand Manager",
        "Growth Marketing Manager",
    ],
    "Operations": [
        "Operations Analyst",
        "Program Manager",
        "Operations Manager",
        "Business Operations Lead",
    ],
}


def make_ssn(existing_ssn: set[str]) -> str:
    while True:
        value = f"{random.randint(100, 999)}-{random.randint(10, 99)}-{random.randint(1000, 9999)}"
        if value not in existing_ssn:
            existing_ssn.add(value)
            return value


def make_employee(employee_number: int, existing_emails: set[str], existing_ssn: set[str]) -> dict:
    first_name = random.choice(FIRST_NAMES)
    last_name = random.choice(LAST_NAMES)

    department = random.choice(list(DEPARTMENTS_WITH_TITLES.keys()))
    title = random.choice(DEPARTMENTS_WITH_TITLES[department])

    base_email = f"{first_name.lower()}.{last_name.lower()}"
    email = f"{base_email}@enterprise.local"
    suffix = 1
    while email in existing_emails:
        suffix += 1
        email = f"{base_email}{suffix}@enterprise.local"
    existing_emails.add(email)

    employee_id = f"E{employee_number:04d}"
    salary = random.randrange(60000, 220001, 500)

    return {
        "employeeId": employee_id,
        "firstName": first_name,
        "lastName": last_name,
        "department": department,
        "title": title,
        "email": email,
        "ssn": make_ssn(existing_ssn),
        "salary": salary,
    }


def generate_employees(count: int, start_id: int) -> list[dict]:
    employees = []
    existing_emails: set[str] = set()
    existing_ssn: set[str] = set()

    for i in range(count):
        employees.append(make_employee(start_id + i, existing_emails, existing_ssn))

    return employees


def write_employees(output_path: Path, employees: list[dict]) -> str:
    payload = json.dumps(employees, indent=2)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as fh:
        fh.write(payload)
        fh.write("\n")
    return payload + "\n"


def build_minio_client(endpoint: str, access_key: str, secret_key: str) -> Minio:
    secure = endpoint.startswith("https://")
    normalized_endpoint = endpoint.replace("https://", "").replace("http://", "")
    return Minio(normalized_endpoint, access_key=access_key, secret_key=secret_key, secure=secure)


def upload_to_minio(client: Minio, bucket: str, object_name: str, payload: str) -> None:
    data = payload.encode("utf-8")
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
    client.put_object(
        bucket,
        object_name,
        io.BytesIO(data),
        length=len(data),
        content_type="application/json",
    )


def env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "t", "yes", "y", "on"}


def random_object_name(base_object: str, prefix: str) -> str:
    base_path = Path(base_object)
    extension = base_path.suffix
    stem = base_path.stem or "employees"
    run_id = uuid.uuid4().hex[:10]
    timestamp = int(time.time())
    root = prefix.strip("/")
    return f"{root}/{stem}-{timestamp}-{run_id}{extension}" if root else f"{stem}-{timestamp}-{run_id}{extension}"


def run_cycle(args: argparse.Namespace, minio_client: Minio | None) -> None:
    employees = generate_employees(args.count, args.start_id)
    payload = write_employees(args.output, employees)

    print(f"Generated {len(employees)} employees into {args.output}")

    if minio_client is not None:
        upload_to_minio(minio_client, args.minio_bucket, args.minio_object, payload)
        print(
            f"Uploaded {len(employees)} employees to s3://{args.minio_bucket}/{args.minio_object} "
            f"via {args.minio_endpoint}"
        )
        if args.attach_random_name:
            attachment_object = random_object_name(args.minio_object, args.minio_random_prefix)
            upload_to_minio(minio_client, args.minio_bucket, attachment_object, payload)
            print(
                f"Uploaded attachment copy to s3://{args.minio_bucket}/{attachment_object}"
            )


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate mock employee data for the mock PGP API server.")
    parser.add_argument("--count", type=int, default=100, help="Number of employees to generate (default: 100)")
    parser.add_argument("--start-id", type=int, default=1001, help="Starting numeric employee ID (default: 1001)")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/employees.json"),
        help="Output JSON file path (default: data/employees.json)",
    )
    parser.add_argument("--seed", type=int, default=42, help="Random seed for deterministic output (default: 42)")
    parser.add_argument(
        "--interval-seconds",
        type=float,
        default=10.0,
        help="Seconds to wait between continuous refreshes (default: 10)",
    )
    parser.add_argument(
        "--run-once",
        action="store_true",
        help="Generate and upload once, then exit",
    )
    parser.add_argument(
        "--minio-endpoint",
        default=os.getenv("MINIO_ENDPOINT", "http://localhost:9000"),
        help="MinIO endpoint URL (default: MINIO_ENDPOINT env or http://localhost:9000)",
    )
    parser.add_argument(
        "--minio-access-key",
        default=os.getenv("MINIO_ACCESS_KEY", "minioadmin"),
        help="MinIO access key (default: MINIO_ACCESS_KEY env or minioadmin)",
    )
    parser.add_argument(
        "--minio-secret-key",
        default=os.getenv("MINIO_SECRET_KEY", "minioadmin"),
        help="MinIO secret key (default: MINIO_SECRET_KEY env or minioadmin)",
    )
    parser.add_argument(
        "--minio-bucket",
        default=os.getenv("MINIO_BUCKET", "employee-data"),
        help="MinIO bucket name (default: MINIO_BUCKET env or employee-data)",
    )
    parser.add_argument(
        "--minio-object",
        default=os.getenv("MINIO_OBJECT", "employees.json"),
        help="MinIO object name (default: MINIO_OBJECT env or employees.json)",
    )
    parser.add_argument(
        "--attach-random-name",
        action="store_true",
        default=env_bool("MINIO_ATTACH_RANDOM_NAME", False),
        help="Also upload a per-run attachment copy using a random object name",
    )
    parser.add_argument(
        "--minio-random-prefix",
        default=os.getenv("MINIO_RANDOM_PREFIX", "attachments"),
        help="Prefix path for random attachment objects (default: MINIO_RANDOM_PREFIX env or attachments)",
    )
    parser.add_argument(
        "--skip-minio-upload",
        action="store_true",
        help="Only write the local file and do not upload to MinIO",
    )

    args = parser.parse_args()

    if args.count < 1:
        raise ValueError("--count must be greater than 0")
    if args.start_id < 1:
        raise ValueError("--start-id must be greater than 0")
    if args.interval_seconds <= 0:
        raise ValueError("--interval-seconds must be greater than 0")

    random.seed(args.seed)
    minio_client = None
    if not args.skip_minio_upload:
        minio_client = build_minio_client(
            args.minio_endpoint,
            args.minio_access_key,
            args.minio_secret_key,
        )

    while True:
        run_cycle(args, minio_client)
        if args.run_once:
            break
        time.sleep(args.interval_seconds)


if __name__ == "__main__":
    main()
