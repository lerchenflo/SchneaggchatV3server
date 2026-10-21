#!/usr/bin/env python3
"""Send test pushes to one FCM token with a Firebase service account, outside the server.

Usage:
  python3 fcm_token_test.py <service-account.json>            asks for the token
  python3 fcm_token_test.py <service-account.json> <token>

Needs: pip install google-auth requests
Without a local Python, next to the JSON on the server:
  docker run --rm -it -v "$PWD":/w -w /w python:3.12-slim \
    sh -c "pip install -q google-auth requests && python fcm_token_test.py schneaggchatv3-firebase-admin.json"

Tests, each printed with the full FCM answer:
  0  Token info (Instance ID API): which app and platform the token belongs to
  1  Dry run, Android only: FCM checks token and credentials, nothing is delivered
  2  Data message, Android only
  3  Data message with the apns block, exactly as the server builds it
  4  Notification message (shown by Android even when the app does not handle it)
"""
import json
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]


def show(title, response):
    print(f"\n=== {title}: HTTP {response.status_code}")
    try:
        print(json.dumps(response.json(), indent=2))
    except ValueError:
        print(response.text[:2000])


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    with open(sys.argv[1]) as f:
        info = json.load(f)
    token = sys.argv[2] if len(sys.argv) > 2 else input("FCM token: ").strip()

    project = info["project_id"]
    print(f"Service account: {info.get('client_email')}")
    print(f"Project:         {project}")
    print(f"Key ID:          {info.get('private_key_id')}")
    print(f"Token:           {token[:24]}... ({len(token)} chars)")

    session = AuthorizedSession(service_account.Credentials.from_service_account_info(info, scopes=SCOPES))
    send_url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"

    try:
        show("0 token info", session.get(
            f"https://iid.googleapis.com/iid/info/{token}?details=true",
            headers={"access_token_auth": "true"},
        ))
    except Exception as e:
        print(f"\n=== 0 token info: failed ({e})")

    # The app picks the payload type from "_class" (PayloadDecoder); without it the push is dropped
    data = {"_class": "system", "type": "system", "title": "FCM test", "message": "Test push from fcm_token_test.py"}
    android = {"priority": "HIGH"}
    # Same apns block as FirebaseService.constructMessage
    apns = {"headers": {"apns-priority": "5"}, "payload": {"aps": {"content-available": 1}}}

    tests = [
        ("1 dry run, Android only", {"validate_only": True, "message": {"token": token, "data": data, "android": android}}),
        ("2 data message, Android only", {"message": {"token": token, "data": data, "android": android}}),
        ("3 data message like the server", {"message": {"token": token, "data": data, "android": android, "apns": apns}}),
        ("4 notification message", {"message": {"token": token, "notification": {"title": "FCM test", "body": "Notification message"}}}),
    ]
    for title, body in tests:
        show(title, session.post(send_url, json=body))


if __name__ == "__main__":
    main()
