import json
import os
import ssl
from flask import Flask, jsonify, request
from minio import Minio
import pgpy

app = Flask(__name__)

PGP_PATH = "/shared/pgp"
SERVER_PRIVATE_KEY_PATH = os.path.join(PGP_PATH, "server-private.asc")
CLIENT_PUBLIC_KEY_PATH = os.path.join(PGP_PATH, "client-public.asc")
MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "minio:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("MINIO_BUCKET", "employee-data")
MINIO_OBJECT = os.getenv("MINIO_OBJECT", "employees.json")


def load_keys():
    server_private_key, _ = pgpy.PGPKey.from_file(SERVER_PRIVATE_KEY_PATH)
    client_public_key, _ = pgpy.PGPKey.from_file(CLIENT_PUBLIC_KEY_PATH)
    return server_private_key, client_public_key


SERVER_PRIVATE_KEY, CLIENT_PUBLIC_KEY = load_keys()


def load_employee_index():
    minio_client = Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=False,
    )
    response = minio_client.get_object(MINIO_BUCKET, MINIO_OBJECT)
    try:
        employee_list = json.loads(response.read().decode("utf-8"))
    finally:
        response.close()
        response.release_conn()
    return {employee["employeeId"]: employee for employee in employee_list}


@app.get("/health")
def health():
    return jsonify({"status": "ok"})


@app.post("/pgp/data")
def pgp_data():
    payload = request.get_json(force=True)
    encrypted = payload.get("encryptedData")
    if not encrypted:
        return jsonify({"error": "encryptedData is required"}), 400

    encrypted_message = pgpy.PGPMessage.from_blob(encrypted)
    decrypted = SERVER_PRIVATE_KEY.decrypt(encrypted_message)
    decrypted_payload = decrypted.message
    if isinstance(decrypted_payload, (bytes, bytearray)):
        decrypted_payload = decrypted_payload.decode("utf-8")
    request_envelope = json.loads(str(decrypted_payload))

    signed_payload = request_envelope.get("payload")
    signature_text = request_envelope.get("signature")
    if not signed_payload or not signature_text:
        return jsonify({"error": "Missing payload/signature in request envelope"}), 400

    signature = pgpy.PGPSignature.from_blob(signature_text)
    verification = CLIENT_PUBLIC_KEY.verify(signed_payload, signature)
    if not verification:
        return jsonify({"error": "Invalid client signature"}), 401

    request_json = json.loads(signed_payload)

    employee_id = request_json.get("employeeId")
    if not employee_id:
        return jsonify({"error": "employeeId is required in encrypted payload"}), 400

    employee_index = load_employee_index()
    employee_record = employee_index.get(employee_id)
    if employee_record is None:
        return jsonify({"error": f"Employee {employee_id} not found"}), 404

    response_json = {
        "employeeId": employee_id,
        "source": "mock-remote-pgp-api",
        "employee": employee_record,
        "encryptionInTransit": {
            "transport": "mTLS",
            "payload": "PGP"
        }
    }

    response_payload = json.dumps(response_json)
    response_signature = str(SERVER_PRIVATE_KEY.sign(response_payload))
    response_envelope = json.dumps({
        "payload": response_payload,
        "signature": response_signature
    })

    response_message = pgpy.PGPMessage.new(response_envelope)
    encrypted_response = CLIENT_PUBLIC_KEY.encrypt(response_message)

    return jsonify({"encryptedData": str(encrypted_response)})


def create_ssl_context():
    certs_path = "/shared/certs"
    context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    context.load_cert_chain(
        certfile=os.path.join(certs_path, "server.crt"),
        keyfile=os.path.join(certs_path, "server.key"),
    )
    context.load_verify_locations(cafile=os.path.join(certs_path, "ca.crt"))
    context.verify_mode = ssl.CERT_REQUIRED
    return context


if __name__ == "__main__":
    ssl_context = create_ssl_context()
    app.run(host="0.0.0.0", port=8443, ssl_context=ssl_context)
