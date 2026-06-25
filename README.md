# SchneaggchatV3server
Schneaggchat server for v3

# How to build
## Server
There are two docker - setups included: The one in the main structure for Localhost execution, and one in the server_docker folder for executing on a remote server (this one pulls the github repository during build). Just clone the project and Use the Dockerfile and docker-compose.yaml to start the server. 

## Localhost
* Install docker desktop (Windows), Install docker (linux) and sudo systemctl start docker
* Open project in Intellij Idea Ultimate (Basic version does not support Docker execution)
* Add run configuration
    * Top Right Center -> Current file dropdown -> Edit Configurations
    * Add new run configuration -> Docker compose
    * Name: Localhost(Title where the "Current File" text is)
    * Select compose file (./docker-compose.yml)
    * Modify dropdown -> Build -> select Always (Always rebuild for the changes to take effect)
    * Press ok
* Ready to build!

## Fast build without docker (Just for compile errors)
* On the right side click on Gradle -> Tasks -> application -> bootRun
* Main Project is now shown in the run config and can be used

## Port
The server will run on port 8080


# Features

# API Endpoints

## Authentication
| Method | Endpoint | Description | Parameters |
| :--- | :--- | :--- | :--- |
| `POST` | `/auth/register` | Register a new user | **Multipart/Form-Data**:<br>`username`: String<br>`password`: String (8+ chars, 1 digit, 1 upper, 1 lower)<br>`email`: String<br>`birthDate`: String<br>`profilepic`: File |
| `POST` | `/auth/login` | Login user | **Body**:<br>`username`: String<br>`password`: String |
| `POST` | `/auth/refresh` | Refresh access token | **Body**:<br>`refreshToken`: String |
| `GET` | `/auth/verify_email` | Verify email address | **Query**:<br>`token`: String |
| `POST` | `/auth/send_delete_email` | Send account deletion email | **Query**:<br>`email`: String |
| `GET` | `/auth/delete_account` | Delete account via token | **Query**:<br>`token`: String |

## Groups
| Method | Endpoint | Description | Parameters |
| :--- | :--- | :--- | :--- |
| `POST` | `/groups/create` | Create a new group | **Multipart/Form-Data**:<br>`name`: String<br>`memberlist[]`: List<String><br>`description`: String<br>`profilepic`: File |
| `POST` | `/groups/sync` | Sync groups for caching | **Body**:<br>List of `{ id: String, timeStamp: String }` |
| `GET` | `/groups/profilepic/{id}` | Get group profile picture | **Path**:<br>`id`: String |

## Messages
| Method | Endpoint | Description | Parameters |
| :--- | :--- | :--- | :--- |
| `POST` | `/messages/send/text` | Send a text message | **Body**:<br>`receiverId`: String<br>`groupMessage`: Boolean<br>`msgType`: String (TEXT, IMAGE)<br>`content`: String<br>`answerId`: String (Optional) |
| `POST` | `/messages/sync` | Sync messages | **Query**:<br>`page`: Int (default 0)<br>`page_size`: Int (default 400)<br>**Body**:<br>List of `{ id: String, timeStamp: String }` |
| `POST` | `/messages/setread` | Mark messages as read | **Query**:<br>`userid`: String<br>`group`: Boolean<br>`timestamp`: Long |
| `POST` | `/messages/edit` | Edit a message | **Body**:<br>`messageId`: String<br>`newContent`: String |
| `DELETE` | `/messages/delete` | Delete a message | **Query**:<br>`messageid`: String |

## Users
| Method | Endpoint | Description | Parameters |
| :--- | :--- | :--- | :--- |
| `POST` | `/users/verificationemail` | Send verification email | *(Auth Token Required)* |
| `POST` | `/users/setfirebasetoken` | Set Firebase token | **Query**:<br>`token`: String |
| `POST` | `/users/changeusername` | Change username | **Body**:<br>New Username (String) |
| `POST` | `/users/changepassword` | Change password | **Body**:<br>`oldPassword`: String<br>`newPassword`: String |
| `POST` | `/users/sync` | Sync users contact data | **Body**:<br>List of `{ id: String, timeStamp: String }` |
| `GET` | `/users/profilepic/{id}` | Get user profile picture | **Path**:<br>`id`: String |
| `POST` | `/users/setprofilepic` | Set user profile picture | **MultiPart**:<br>`file` (implicit in body) |
| `POST` | `/users/changeprofile` | Change user profile details | **Body**:<br>`userId`: String<br>`newDescription`: String (Optional)<br>`newStatus`: String (Optional) |
| `GET` | `/users/availableusers` | Search or list available users | **Query**:<br>`searchterm`: String (Optional) |
| `GET` | `/users/addfriend/{id}` | Send friend request | **Path**:<br>`id`: String |
| `GET` | `/users/denyfriend/{id}` | Deny friend request | **Path**:<br>`id`: String |
| `GET` | `/users/removefriend/{id}` | Remove a friend | **Path**:<br>`id`: String |
| `POST` | `/users/sharelocation` | Set per-friend live-location sharing (full replace) | **Body**:<br>`friendId`: String<br>`share`: Boolean<br>`shareSpeedHeading`: Boolean<br>`snailTrailHours`: Int? (`null`=off, `0`=full 24h, `N`=last N hours) |

> **Note:** The global location master switch is set via `POST /users/changeprofile` with `newLocationShared: Boolean`. Sending/receiving live locations themselves happens over the **WebSocket** — see [Live Location](#live-location) below.

## Live Location

Live location started as a simple HTTP poll that only carried `lat`/`long`. It is now a richer,
privacy-gated feature delivered entirely over the **WebSocket**. This section documents everything
added since then.

### What a location carries

A location update is `lat` + `long` (mandatory) plus optional driving telemetry — all nullable, so a
client that only has coordinates still works:

| Field | Type | Notes |
| :--- | :--- | :--- |
| `lat`, `long` | Double | Mandatory. |
| `speed` | Double? | meters/second, 0–120. |
| `heading` | Double? | degrees, 0–360 (0 = north). |
| `altitude` | Double? | meters above sea level, −500–9000. |
| `batteryLevel` | Int? | percent, 0–100. |

On every update the server also computes the user's **distance traveled in the last 24h** (great-circle
sum of consecutive points, with a small jitter guard), stored alongside the point.

### Sharing model (two gates + per-field control)

A friend sees your location only if **both** gates pass:

1. **Global master switch** — `User.locationShared`, set via `POST /users/changeprofile`
   (`newLocationShared`). If off, you share with nobody.
2. **Per-friend toggle** — `FriendshipSetting.shareLocation` toward that friend, set via
   `POST /users/sharelocation`.

Once a friend may see your location, the individual fields are controlled per friend:

| Field | Control | Default |
| :--- | :--- | :--- |
| coordinates, time | always shared once visible | — |
| **altitude, battery, 24h distance** | always shared once visible (not toggleable) | on |
| **speed + heading** | per-friend `shareSpeedHeading` (one toggle for both) | off |
| **snail trail** | per-friend `snailTrailHours` | off (null) |

`snailTrailHours` semantics: **`null`** = no trail, **`0`** = full retained history (last 24h),
**`N`** = the last N hours. Within that window the trail is sampled at **at most one point per
minute**, and a minute's point is only emitted when the user moved **more than 10 m** from the
previous emitted point (so a stationary user yields ~no trail), read from history the server already
keeps (no extra storage). Each snail-trail point also carries speed/heading, gated by the same
`shareSpeedHeading` toggle.

`POST /users/sharelocation` is a **full replacement** of one friend's settings — send all of
`share`, `shareSpeedHeading`, `snailTrailHours` every time. The current values are echoed back on
`POST /users/sync` in each `FriendUserResponse` (`shareLocation`, `shareSpeedHeading`,
`snailTrailHours`) so the client can render the settings screen.

### WebSocket transport

Connect to **`/ws`** with the access token in the handshake header: `Authorization: Bearer <token>`
(invalid/missing → connection rejected with 403). All frames are JSON with a `_class` discriminator.

**Inbound (client → server)** — push your own current location:

```json
{ "_class": "locationupdate", "lat": 48.2082, "long": 16.3738,
  "speed": 12.5, "heading": 270.0, "altitude": 180.4, "batteryLevel": 73 }
```

Identity is taken from the authenticated socket session — there is no `userId` field and you can only
ever write your own location. Invalid frames (bad coordinates/telemetry) are dropped silently.

**Outbound (server → client):**

| `_class` | When | Payload |
| :--- | :--- | :--- |
| `friendlocationchange` | a friend you can see moved (~every 5s) | `{ "friend": FriendLocationPayload }` — live position only, **no trail** |
| `snailtrailpointadded` | a friend's trail advanced (at most once/min, only while moving) | `{ "userId": "665f1...", "point": SnailTrailPointPayload }` |
| `friendlocationssnapshot` | once, right after you connect (initial load) | `{ "friends": [FriendLocationSnapshot, ...] }` |

The live position and the trail are delivered separately so the every-5s update stays small: the
client moves a friend's marker on every `friendlocationchange`, appends to that friend's trail polyline
on each `snailtrailpointadded`, and seeds both from the snapshot on connect.

`FriendLocationPayload` (live position):

```json
{
  "userId": "665f1...",
  "coordinates": { "lat": 48.21, "long": 16.37 },
  "locationTime": 1750000000000,
  "speed": null,
  "heading": null,
  "altitude": 180.4,
  "batteryLevel": 73,
  "distanceTraveled24h": 15234.7
}
```

`SnailTrailPointPayload`: `{ "coordinates": {lat,long}, "locationTime": Long, "speed": Double?, "heading": Double? }`.

`FriendLocationSnapshot` (one per friend in the snapshot): `{ "position": FriendLocationPayload, "snailTrail": [SnailTrailPointPayload, ...] }`.

Any field the friend hasn't opted to share is `null` (and `snailTrail` is empty if they don't share a
trail). `locationTime` is epoch millis; `userId` is a hex string.

> **Migration note (snail trail delivery):** the snail trail is no longer bundled into every live
> update. The every-5s `friendlocationchange` carries position only; the trail arrives as the full set
> in `friendlocationssnapshot` on connect and then one point at a time via `snailtrailpointadded`.
>
> **Migration note:** the old `POST /users/locations` HTTP endpoint has been **removed**. Clients now
> push via `locationupdate` and receive friends' locations via `friendlocationssnapshot` (on connect)
> + `friendlocationchange` (live). Note `/ws` is not behind the HTTP rate limiter, and live updates are
> delivered to a user's first active connection.

## General
| Method | Endpoint | Description | Parameters |
| :--- | :--- | :--- | :--- |
| `GET` | `/public/test` | Health check | - |
