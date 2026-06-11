# Backend contract: call log / recording "check-existing" endpoints

This document describes two new endpoints the Android app calls **before**
uploading call logs or call recordings, so it only sends records the server
doesn't already have. It complements the existing upload contracts in
`README.md` §6 (`POST /api/crm/call-recordings/upload`) and the call-log sync
endpoint (`POST /api/crm/call-logs/sync`) — those are unchanged.

## Rollout / compatibility

- The Android app calls these endpoints **before every sync** (including
  manual "Sync Now" and after "Reset sync history").
- If either endpoint responds with **404 or 501**, the app treats it as "not
  supported yet" and falls back to its previous upload-everything behavior.
  This means the backend can be deployed incrementally without breaking
  existing installs — duplicates stop appearing once both the check-existing
  endpoints **and** the unique constraints below are live.
- A **401** response clears the app's session and prompts re-login, same as
  any other authenticated endpoint.
- Requests are sent in batches of up to **200 records**.

## 1. `POST /api/crm/call-logs/check-existing`

```
POST /api/crm/call-logs/check-existing
Content-Type: application/json
Authorization: Bearer <agent token>

{
  "deviceId": "abc123-device-id",
  "records": [
    {
      "clientRef": "10482",
      "phoneNumber": "+15551234567",
      "callType": "outgoing",
      "callStartedAt": "2026-06-08T12:30:00.000Z",
      "durationSeconds": 42
    }
  ]
}
```

Field notes:
- `clientRef` — an opaque identifier chosen by the client (the local Android
  `CallLog._ID`, as a string). The server does **not** need to interpret it —
  just echo back which `clientRef`s correspond to records it already has.
- `callType` — lowercase: `incoming | outgoing | missed | rejected | blocked`.
- `callStartedAt` — ISO-8601 UTC, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
- `durationSeconds` — integer seconds.

### Response

```json
{
  "existing": ["10480", "10481"],
  "missing": ["10482"]
}
```

- `existing` — `clientRef`s for records the server already has for this
  agent/device (matched via the dedup key below). The app marks these as
  synced **without re-uploading**.
- `missing` — `clientRef`s the server doesn't have. The app uploads these via
  `POST /api/crm/call-logs/sync` as before.
- Either list may be omitted if empty; the app treats a missing list as empty.
  Every `clientRef` from the request should appear in exactly one of the two
  lists.

### Server-side dedup key (call logs)

Records are matched on:

```
agentId/userId (from auth token) + deviceId + normalizedPhoneNumber + callType + callStartedAt
```

## 2. `POST /api/crm/call-recordings/check-existing`

```
POST /api/crm/call-recordings/check-existing
Content-Type: application/json
Authorization: Bearer <agent token>

{
  "deviceId": "abc123-device-id",
  "records": [
    {
      "clientRef": "57",
      "phoneNumber": "+15551234567",
      "callStartedAt": "2026-06-08T12:30:00.000Z",
      "durationSeconds": 42,
      "fileSize": 184320,
      "fileHash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }
  ]
}
```

Field notes:
- `clientRef` — opaque identifier chosen by the client (the local Room
  recording row id, as a string).
- `callStartedAt` — ISO-8601 UTC, same format as above.
- `durationSeconds` — may be `null` if unknown.
- `fileSize` — bytes.
- `fileHash` — SHA-256 hex digest of the file content, computed by the app.
  May be `null` if the app couldn't compute it (e.g. unreadable file) — see
  fallback below.

### Response

Same shape as call logs:

```json
{
  "existing": ["55", "56"],
  "missing": ["57"]
}
```

- `existing` → app marks these recordings as synced without re-uploading the
  file.
- `missing` → app uploads via `POST /api/crm/call-recordings/upload` (multipart,
  field name `file`, unchanged) as before.

### Server-side dedup key (recordings)

Primary match:

```
agentId/userId + deviceId + normalizedPhoneNumber + callStartedAt + fileHash
```

Fallback when `fileHash` is `null` (or not present in older records):

```
agentId/userId + deviceId + normalizedPhoneNumber + callStartedAt + fileSize + durationSeconds
```

## 3. Suggested database unique constraints

Even with the check-existing endpoints, the database should enforce
uniqueness as a backstop against duplicate inserts (e.g. retried requests,
client bugs, or "Reset sync history" runs against a backend that doesn't yet
support check-existing):

```sql
CREATE UNIQUE INDEX uq_call_logs_dedup
  ON call_logs (agent_id, device_id, phone_number, call_type, call_started_at);

CREATE UNIQUE INDEX uq_call_recordings_dedup
  ON call_recordings (agent_id, device_id, phone_number, call_started_at, file_hash);
```

**Caveat**: most databases (Postgres, MySQL, SQLite) treat `NULL` as distinct
in unique indexes, so rows with `file_hash IS NULL` are **not** deduplicated
by `uq_call_recordings_dedup` alone. Recommended mitigations (either or both):

1. Add a second unique index covering the no-hash case, filtered to rows
   where `file_hash IS NULL`:
   ```sql
   CREATE UNIQUE INDEX uq_call_recordings_dedup_no_hash
     ON call_recordings (agent_id, device_id, phone_number, call_started_at, file_size, duration_seconds)
     WHERE file_hash IS NULL;
   ```
2. Or have the backend compute `file_hash` itself from the uploaded file when
   the client doesn't provide one, so `uq_call_recordings_dedup` always
   applies.

## 4. Conventions restated for completeness

- `callStartedAt` / `callStartedAt` fields: ISO-8601 UTC,
  `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
- `callType`: lowercase — `incoming | outgoing | missed | rejected | blocked`.
- Recording upload multipart file field name: `file` (unchanged).
- `/api/crm/call-logs/sync` and `/api/crm/call-recordings/upload` request/
  response shapes are unchanged — `check-existing` is purely additive and
  called beforehand.
