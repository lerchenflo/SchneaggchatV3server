"""
API test: fills every field the API accepts, then calls every route of every controller.
Started by run.sh against a fresh server in Docker; standard library only.

Fails on:
- any 5XX answer,
- a setup call that does not answer the way the test needs it (marked with ok=...),
- an ERROR line in the server log, except the push setup errors every local run has,
- a controller route the test never calls (a new endpoint must be added here).

At the end it prints, per collection, the fields that no stored document fills. That list is
information, not a failure: it shows where the test (or the data model) leaves fields empty.

Firebase and APNs are not configured, so pushes are attempted, never delivered. Mail sending
fails the same way and only logs a WARN.
"""
import base64, json, os, re, socket, struct, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request, uuid, zlib

LOG, MONGO_CONTAINER, PORT, PASSWORD, ROOT, SERVER_CONTAINER = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5], sys.argv[6]
BASE = f'http://127.0.0.1:{PORT}'
SRC = os.path.join(ROOT, 'src/main/kotlin/com/lerchenflo/schneaggchatv3server')
NOW = int(time.time() * 1000)
MIN, HOUR, DAY = 60_000, 3_600_000, 86_400_000

# ERROR lines a local run always has: no push keys in the container
ALLOWED_ERROR_TAGS = {
    'init_apns_key_missing', 'init_apns_config_missing', 'init_apns_failed', 'init_firebase_config_missing',
    'send_fcm_unexpected', 'send_apns_failed', 'send_apns_unexpected',
}

# ─── HTTP ─────────────────────────────────────────────────────────────────────

def request(method, path, token=None, body=None, params=None, data=None, ctype=None, text=None, stream=False):
    url = BASE + path + ('?' + urllib.parse.urlencode(params) if params else '')
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if body is not None:
        data, ctype = json.dumps(body).encode(), 'application/json'
    if text is not None:
        data, ctype = text.encode(), 'text/plain'
    if ctype:
        headers['Content-Type'] = ctype
    req = urllib.request.Request(url, method=method, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, ('' if stream else r.read().decode(errors='replace'))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors='replace')
    except Exception as e:
        return 0, f'{type(e).__name__}: {e}'


def multipart(parts):
    """parts: list of (name, value, filename or None, content type or None)."""
    boundary = '----apitest' + uuid.uuid4().hex
    out = b''
    for name, value, filename, ctype in parts:
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'.encode()
        if filename:
            out += f'; filename="{filename}"'.encode()
        out += b'\r\n'
        if ctype:
            out += f'Content-Type: {ctype}\r\n'.encode()
        out += b'\r\n' + (value if isinstance(value, bytes) else str(value).encode()) + b'\r\n'
    out += f'--{boundary}--\r\n'.encode()
    return out, 'multipart/form-data; boundary=' + boundary


def json_part(name, obj):
    return (name, json.dumps(obj), None, 'application/json')


def norm(route):
    return re.sub(r'\{[^}]*\}', '{}', route.rstrip('/') or '/')


called, failures = set(), []
# Every JSON body and socket frame, scanned at the end for name fields that were replaced by user ids
bodies, socket_frames = [], []
NAME_KEYS = {'memberName', 'actorName', 'userName', 'invitedUserNames', 'creatorName', 'authorName', 'updatedByName', 'requestingUserName'}


def call(method, route, token=None, ok=None, vars=None, **kw):
    """Calls a route, records it for the coverage check and fails on 5XX or an unexpected status."""
    path = route
    for k, v in (vars or {}).items():
        path = path.replace('{' + k + '}', urllib.parse.quote(str(v), safe=''))
    code, text = request(method, path, token, **kw)
    called.add((method, norm(route)))
    expected = ok if isinstance(ok, (list, tuple, set)) else ((ok,) if ok is not None else None)
    bad = code >= 500 or code == 0 or (expected is not None and code not in expected)
    label = f'{method} {path}'[:110]
    if bad:
        failures.append(f'{label} -> {code} {text[:200]}')
        print(f'FAIL {label} -> {code} {text[:200]}')
    else:
        print(f'ok   {label} -> {code}')
    if text[:1] in '{[':
        bodies.append((f'{method} {norm(route)}', text))
    try:
        return code, json.loads(text)
    except Exception:
        return code, text


def check(ok, label):
    """Records a content check that is not a status code."""
    if ok:
        print(f'ok   {label}')
    else:
        failures.append(label)
        print(f'FAIL {label}')


def pic_on_sync_row(tok, user_id):
    """profilePicUpdatedAt of user_id's row in the token owner's /users/sync, 'missing' without a row."""
    code, body = call('POST', '/users/sync', tok, body=[], ok=200)
    rows = body.get('updatedUsers', []) if isinstance(body, dict) else []
    return next((r for r in rows if r.get('id') == user_id), {}).get('profilePicUpdatedAt', 'missing')


def pic_on_socket(ws, user_id):
    """profilePicUpdatedAt of the newest socket userchange for user_id, 'missing' when none came."""
    time.sleep(.6)
    rows = [m['user'] for m in ws.messages if m.get('_class') == 'userchange' and (m.get('user') or {}).get('id') == user_id]
    ws.messages.clear()
    return rows[-1].get('profilePicUpdatedAt', 'missing') if rows else 'missing'


def mongo(js):
    o = subprocess.run(['docker', 'exec', MONGO_CONTAINER, 'mongosh', '--quiet', 'db', '--eval', js], capture_output=True, text=True)
    return (o.stdout.strip() + o.stderr.strip())


def jwt_claims(token):
    p = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(p + '=' * (-len(p) % 4)))


def find_all(obj, pred):
    """Every dict in a nested response for which pred(dict) holds."""
    out = []
    if isinstance(obj, dict):
        if pred(obj):
            out.append(obj)
        for v in obj.values():
            out += find_all(v, pred)
    elif isinstance(obj, list):
        for v in obj:
            out += find_all(v, pred)
    return out

# ─── Test media ───────────────────────────────────────────────────────────────

def png(w=48, h=48):
    rows = b''.join(b'\x00' + b''.join(bytes([x * 5 % 256, y * 5 % 256, 160]) for x in range(w)) for y in range(h))

    def chunk(kind, data):
        c = kind + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


PNG = png()
M4A = b'\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00M4A mp42isom\x00\x00\x00\x00' + os.urandom(2048)

# ─── WebSocket (only to fill the live location fields) ───────────────────────

class WS:
    def __init__(s, tok):
        s.s = socket.create_connection(('127.0.0.1', PORT), timeout=5)
        k = base64.b64encode(os.urandom(16)).decode()
        s.s.sendall(f'GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {k}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {tok}\r\n\r\n'.encode())
        b = b''
        while b'\r\n\r\n' not in b:
            b += s.s.recv(1)
        s.status = b.split(b'\r\n')[0].decode()
        s.s.settimeout(None)
        s.messages = []  # JSON of every text frame the server sent
        threading.Thread(target=s.drain, daemon=True).start()

    def drain(s):
        buf = b''
        try:
            while True:
                chunk = s.s.recv(65536)
                if not chunk:
                    break
                buf += chunk
                # Server frames are never masked: opcode, length (7 bit, 16 bit or 64 bit), payload
                while len(buf) >= 2:
                    op, n, i = buf[0] & 0x0f, buf[1] & 0x7f, 2
                    if n == 126:
                        if len(buf) < 4:
                            break
                        n, i = struct.unpack('>H', buf[2:4])[0], 4
                    elif n == 127:
                        if len(buf) < 10:
                            break
                        n, i = struct.unpack('>Q', buf[2:10])[0], 10
                    if len(buf) < i + n:
                        break
                    payload, buf = buf[i:i + n], buf[i + n:]
                    if op == 1:
                        try:
                            s.messages.append(json.loads(payload))
                            socket_frames.append(s.messages[-1])
                        except Exception:
                            pass
        except Exception:
            pass

    def send(s, obj):
        d = json.dumps(obj).encode()
        m = os.urandom(4)
        h = bytes([0x81]) + (bytes([0x80 | len(d)]) if len(d) < 126 else bytes([0x80 | 126]) + struct.pack('>H', len(d))) + m
        s.s.sendall(h + bytes(c ^ m[i % 4] for i, c in enumerate(d)))

    def close(s):
        try:
            m = os.urandom(4)
            d = struct.pack('>H', 1000)
            s.s.sendall(bytes([0x88, 0x80 | len(d)]) + m + bytes(c ^ m[i % 4] for i, c in enumerate(d)))
            time.sleep(.3)
            s.s.close()
        except Exception:
            pass

# ─── Routes and models read from the sources ──────────────────────────────────

def source_routes():
    routes = set()
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith('.kt'):
                continue
            s = open(os.path.join(dirpath, f), encoding='utf-8').read()
            if not re.search(r'^@(Rest)?Controller', s, re.M):
                continue
            m = re.search(r'^@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"', s, re.M)
            prefix = m.group(1) if m else ''
            for mm in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(([^)]*)\))?', s):
                args = (mm.group(2) or '').strip()
                pm = re.match(r'(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', args)
                routes.add((mm.group(1).upper(), norm(prefix + (pm.group(1) if pm else ''))))
    return routes


def enum_entries(path, enum_name):
    s = open(os.path.join(SRC, path), encoding='utf-8').read()
    body = s[s.index('enum class ' + enum_name):]
    end = re.search(r'^}', body, re.M)
    body = body[:end.start()] if end else body
    return re.findall(r'^ {4}([A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)', body, re.M)


def all_enums():
    enums = {}
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if f.endswith('.kt'):
                s = open(os.path.join(dirpath, f), encoding='utf-8').read()
                for m in re.finditer(r'enum class (\w+)[^{]*\{([^}]*)\}', s):
                    enums.setdefault(m.group(1), re.findall(r'^\s*([A-Z][A-Z0-9_]*)\b', m.group(2).split(';')[0], re.M))
    return enums


def camel(key):
    parts = key.lower().split('_')
    return parts[0] + ''.join(p.capitalize() for p in parts[1:])


def location_datas():
    """One LocationData per subtype from LocationData.kt, every attribute filled with a valid value."""
    s = open(os.path.join(SRC, 'schneaggmap/LocationData.kt'), encoding='utf-8').read()
    wire = dict(re.findall(r'JsonSubTypes\.Type\(value = LocationData\.(\w+)::class,\s*name = "(\w+)"\)', s))
    enums = all_enums()
    starts = [(m.start(), m.group(1)) for m in re.finditer(r'^    (?:data )?class (\w+)', s, re.M)]
    out = []
    for i, (pos, cls) in enumerate(starts):
        if cls not in wire:
            continue
        body = s[pos:starts[i + 1][0] if i + 1 < len(starts) else len(s)]
        data = {'_class': wire[cls]}
        for m in re.finditer(r'AttributeDefinition\.(\w+)Def\s*\(([^)]*(?:\([^)]*\)[^)]*)*)\)', body):
            kind, args = m.group(1), m.group(2)
            key = re.search(r'AttributeKey\.(\w+)', args).group(1)
            lo = re.search(r'min = (-?[\d.]+)', args)
            hi = re.search(r'max = (-?[\d.]+)', args)
            if kind == 'Int':
                value = {'_class': 'int', 'value': int(float(lo.group(1))) + 1 if lo else 50}
            elif kind == 'Rating':
                value = {'_class': 'int', 'value': int(hi.group(1)) if hi else 5}
            elif kind in ('Double', 'Distance', 'Price'):
                value = {'_class': 'double', 'value': 4.5}
            elif kind == 'Bool':
                value = {'_class': 'bool', 'value': True}
            elif kind in ('Long', 'DateTime'):
                value = {'_class': 'long', 'value': NOW - MIN}  # recent: also a fresh sighting
            elif kind == 'Enum':
                opts = enums.get(re.search(r'options = (\w+)\.entries', args).group(1), ['UNKNOWN'])
                value = {'_class': 'string', 'value': opts[0]}
            else:  # String, Secret
                value = {'_class': 'string', 'value': f'test {key.lower()}'}
            data[camel(key)] = value
        out.append(data)
    return out

# ─── Start ────────────────────────────────────────────────────────────────────

for _ in range(240):
    if request('GET', '/public/test')[0] == 200:
        break
    time.sleep(1)
else:
    sys.exit('server not up')
print('server up')

# Public pages and endpoints
call('GET', '/public/test', ok=200)
call('GET', '/public/faq', ok=200)
call('GET', '/public/donations', ok=200)
call('GET', '/stats.html', ok=200)
call('GET', '/faq.html', ok=200)
call('GET', '/donations.html', ok=200)
call('GET', '/chefdev.html', ok=200)

# ─── Users ────────────────────────────────────────────────────────────────────
# testaccount (debug seed) becomes alice, the admin; bob, carol and dave are copies of it.
mongo("db.users.updateOne({username:'testaccount'},{$set:{role:'ADMIN'}})")
for name in ['bob', 'carol', 'dave', 'frank']:
    mongo(f"var u=db.users.findOne({{username:'testaccount'}}); u._id=new ObjectId(); u.username='{name}'; u.email='{name}@test.local'; u.role='USER'; db.users.insertOne(u)")


def login(user, device, kind, password=PASSWORD):
    code, b = call('POST', '/auth/login', body={'username': user, 'password': password, 'deviceName': device, 'deviceType': kind}, ok=200)
    return b


A = login('testaccount', 'Pixel 9 - androidId: a1', 'ANDROID')
A2 = login('testaccount', 'iPhone 16 - vendorId: a2', 'IOS')
B = login('bob', 'iPhone 15 - vendorId: b1', 'IOS')
C = login('carol', 'MacBook - macAddress: c1', 'DESKTOP')
D = login('dave', 'Galaxy S24 - androidId: d1', 'ANDROID')
a, b, c, d = (jwt_claims(x['accessToken'])['sub'] for x in (A, B, C, D))
ta, tb, tc, td = A['accessToken'], B['accessToken'], C['accessToken'], D['accessToken']

# Registration with every field, then the new user logs in
code, _ = call('POST', '/auth/register', ok=200, **dict(zip(('data', 'ctype'), multipart([
    ('username', 'erin', None, None), ('password', PASSWORD, None, None), ('email', 'erin@test.local', None, None),
    ('birthDate', '2001-02-03', None, None), ('profilepic', PNG, 'erin.png', 'image/png'),
    ('phoneNumber', '+43 660 1234567', None, None), ('language', 'de', None, None)]))))
E = login('erin', 'Pixel 8 - androidId: e1', 'ANDROID')
e, te = jwt_claims(E['accessToken'])['sub'], E['accessToken']
F = login('frank', 'Pixel 7 - androidId: f1', 'ANDROID')
f, tf = jwt_claims(F['accessToken'])['sub'], F['accessToken']

code, refreshed = call('POST', '/auth/refresh', body={'refreshToken': A2['refreshToken'], 'deviceName': 'iPhone 16 - vendorId: a2', 'deviceType': 'IOS'}, ok=200)
A2 = refreshed if isinstance(refreshed, dict) else A2

# Friendships: alice-bob, alice-carol, bob-carol, alice-dave, bob-frank accepted; erin -> alice pending; carol -> dave denied
for x, y, tx, ty in [(a, b, ta, tb), (a, c, ta, tc), (b, c, tb, tc), (a, d, ta, td), (b, f, tb, tf)]:
    call('POST', '/users/addfriend/{id}', tx, vars={'id': y}, ok=200)
    call('POST', '/users/addfriend/{id}', ty, vars={'id': x}, ok=200)
call('POST', '/users/addfriend/{id}', te, vars={'id': a}, ok=200)
call('POST', '/users/addfriend/{id}', tc, vars={'id': d}, ok=200)
call('POST', '/users/denyfriend/{id}', td, vars={'id': c}, ok=200)

# Every personal setting
settings = {
    'mdFormat': True, 'highlightTodaysMessageTimestamp': True, 'theme': 'dark', 'language': 'de',
    'mergeMapLocations': True, 'mergeMapUsers': True, 'sortMapUsersByPinned': True, 'mapSightingPush': True,
    'mapStyle': 'satellite', 'pinnedChats': [{'chatId': b, 'group': False, 'pinTimePoint': NOW}],
    'quickReactions': ['👍', '😂', '❤️', 'nice'], 'resetQuickReactions': False, 'developerSettings': True,
    'lastContributePopupShown': NOW, 'lastDismissedAnnouncementId': 1, 'profilePicVisibility': 'FRIENDS_AND_GROUPS',
}
for t in (ta, tb, tc, td):
    call('POST', '/users/settings', t, body=settings, ok=200)

call('POST', '/users/changeprofile', ta, body={'userId': a, 'newDescription': None, 'newStatus': 'Am Testen', 'newEmail': None,
                                               'newBirthDate': '1999-05-05', 'newNickName': None, 'newPhoneNumber': '+43 664 1111111'}, ok=200)
call('POST', '/users/changeprofile', ta, body={'userId': b, 'newDescription': 'Bob baut alles', 'newStatus': None, 'newEmail': None,
                                               'newBirthDate': None, 'newNickName': 'Bobby', 'newPhoneNumber': None}, ok=200)
call('POST', '/users/changeprofile', td, body={'userId': d, 'newDescription': None, 'newStatus': 'Neu da', 'newEmail': 'dave2@test.local',
                                               'newBirthDate': '1995-12-24', 'newNickName': None, 'newPhoneNumber': '+49 151 2222222'}, ok=200)
call('POST', '/users/setnotificationtoken', ta, body={'token': 'fcm-alice-pixel', 'isAndroid': True}, ok=200)
call('POST', '/users/setnotificationtoken', tb, body={'token': 'ab' * 32, 'isAndroid': False}, ok=200)
call('POST', '/users/setprofilepic', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([('profilepic', PNG, 'alice.png', 'image/png')]))))
call('GET', '/users/profilepic/{id}', ta, vars={'id': a}, ok=200)
# Profile picture privacy. erin -> alice is pending (erin sent it), and they share no group.
call('GET', '/users/profilepic/{id}', ta, vars={'id': e}, ok=200)  # the receiver sees the sender's picture
call('GET', '/users/profilepic/{id}', te, vars={'id': a}, ok=403)  # the sender does not: alice is friends and groups
call('GET', '/users/profilepic/{id}', te, vars={'id': '0' * 24}, ok=404)
call('POST', '/users/settings', te, body={'profilePicVisibility': 'EVERYONE'}, ok=400)


def alice_pic_for_erin():
    code, body = call('POST', '/users/sync', te, body=[], ok=200)
    rows = body.get('updatedUsers', []) if isinstance(body, dict) else []
    return next((r for r in rows if r.get('id') == a), {})


check(alice_pic_for_erin().get('profilePicUpdatedAt', 0) is None, 'hidden picture: null timestamp on the sync row')
ws_e = WS(te)
time.sleep(.4)
ws_e.messages.clear()
call('POST', '/users/settings', ta, body={'profilePicVisibility': 'PUBLIC'}, ok=200)
check(isinstance(pic_on_socket(ws_e, a), int), 'owner turns public: userchange to the pending sender carries the timestamp')
call('GET', '/users/profilepic/{id}', te, vars={'id': a}, ok=200)
check(isinstance(alice_pic_for_erin().get('profilePicUpdatedAt'), int), 'public picture: timestamp on the sync row')
code, found = call('GET', '/users/availableusers', te, params={'searchterm': 'bo'}, ok=200)
bob_row = next((r for r in found if r.get('id') == b), {}) if isinstance(found, list) else {}
check('profilePicUpdatedAt' in bob_row and bob_row['profilePicUpdatedAt'] is None, 'search result: hidden picture has a null timestamp')
code, found = call('GET', '/users/availableusers', te, ok=200)
check(isinstance(found, list) and found and all('profilePicUpdatedAt' in r for r in found), 'search without a term: every result has the field')
call('POST', '/users/settings', ta, body={'profilePicVisibility': 'FRIENDS_AND_GROUPS'}, ok=200)
check(pic_on_socket(ws_e, a) is None, 'owner back to friends and groups: userchange carries a null timestamp')
ws_e.close()


# User names by id: in-app data carries ids, /users/lookup resolves them
def lookup(tok, ids, ok=200):
    code, body = call('POST', '/users/lookup', tok, body={'userIds': ids}, ok=ok)
    return body if isinstance(body, dict) else {}


def looked_up(body, user_id):
    return next((u for u in body.get('users', []) if u.get('id') == user_id), {})


gone = '0' * 24
found = lookup(te, [a, b, e, gone, a])
check(sorted(u.get('id') for u in found.get('users', [])) == sorted([a, b, e]) and found.get('deletedUserIds') == [gone],
      f'lookup: every id once, unknown id in deletedUserIds: {found}')
check(looked_up(found, a).get('username') == 'testaccount' and looked_up(found, b).get('username') == 'bob', 'lookup: plain usernames')
check('profilePicUpdatedAt' in looked_up(found, a) and looked_up(found, a)['profilePicUpdatedAt'] is None,
      'lookup: pending sender does not see the hidden picture')
check(looked_up(found, b).get('profilePicUpdatedAt', 0) is None, 'lookup: no row, no shared group: hidden picture')
check(isinstance(looked_up(found, e).get('profilePicUpdatedAt'), int), 'lookup: own picture timestamp')
found = lookup(ta, [e, b])
check(isinstance(looked_up(found, e).get('profilePicUpdatedAt'), int), 'lookup: pending receiver sees the sender picture')
check(isinstance(looked_up(found, b).get('profilePicUpdatedAt'), int), 'lookup: a friend sees the picture')
found = lookup(ta, [])
check(found.get('users') == [] and found.get('deletedUserIds') == [], f'lookup: empty list gives empty lists: {found}')
lookup(ta, [a] * 101, ok=400)
lookup(ta, ['nope'], ok=400)
call('POST', '/users/lookup', ta, body={}, ok=400)
call('POST', '/users/verificationemail', te)
call('GET', '/users/availableusers', ta, params={'searchterm': 'er'}, ok=200)
call('POST', '/users/sync', ta, body=[], ok=200)
call('POST', '/users/sync', ta, body=[{'id': b, 'timeStamp': '0'}, {'id': '0' * 24, 'timeStamp': '0'}], ok=200)
for x, tx, y in [(a, ta, b), (b, tb, a), (c, tc, a)]:
    call('POST', '/users/sharelocation', tx, body={'friendId': y, 'share': True, 'shareSpeedHeading': True, 'shareSnailTrail': True}, ok=200)
call('POST', '/users/setwakeglobal', tb, body={'enabled': True}, ok=200)
call('POST', '/users/setwakepermission', tb, body={'friendId': a, 'allowWake': True}, ok=200)

# Live location with every field, over the socket
for tok in (ta, tb):
    ws = WS(tok)
    time.sleep(.4)
    ws.send({'_class': 'locationupdate', 'lat': 47.4125, 'long': 9.7417, 'speed': 13.9, 'heading': 271.5, 'altitude': 431.0, 'batteryLevel': 77})
    time.sleep(.6)
    ws.close()

# ─── Events (also make the group chat) ────────────────────────────────────────
event_a = {'eventId': None, 'type': 'SPORT', 'title': 'Kicken am See', 'description': 'Ball mitnehmen', 'location': {'lat': 47.5, 'long': 9.7},
           'startDate': NOW + 2 * DAY, 'closeDate': NOW + 2 * DAY + 3 * HOUR, 'invitedUsers': [b, c, d], 'visibility': 'INVITED_FRIENDS_ONLY',
           'maxUsers': 10, 'entryFeeCents': 1250, 'groupDeleteDelay': 'ONE_DAY', 'createGroup': True, 'reminderMinutes': [60, 1440], 'membersCanAdd': False}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_a), ('profilepic', PNG, 'event.png', 'image/png')]))))
ev_a, group = ev.get('id'), ev.get('groupId')
check(ev.get('entryFeeCents') == 1250, f'event entry fee stored: {ev.get("entryFeeCents")}')
# Both are required (hard cut): a missing one, a null one or a fee out of range is a 400 and changes nothing
# A null required field fails while the body is read ("Cannot map `null` into type ..."), a missing one too (below)
for why, bad in [('Invalid entry fee', {'entryFeeCents': -1}), ('Invalid entry fee', {'entryFeeCents': 1_000_001}),
                 ('Cannot map `null` into type `long`', {'entryFeeCents': None}), ('Cannot map `null` into type `boolean`', {'membersCanAdd': None})]:
    code, body = call('POST', '/events/upsert', ta, ok=400, **dict(zip(('data', 'ctype'), multipart([json_part('request', {**event_a, 'eventId': ev_a, **bad})]))))
    check(why in str(body), f'event upsert {bad}: rejected because "{why}": {str(body)[:160]}')
for why, key in [("Missing required creator property 'entryFeeCents'", 'entryFeeCents'), ("Missing required creator property 'membersCanAdd'", 'membersCanAdd')]:
    code, body = call('POST', '/events/upsert', ta, ok=400, **dict(zip(('data', 'ctype'), multipart([json_part('request', {k: v for k, v in event_a.items() if k != key})]))))
    check(why in str(body), f'event upsert without {key}: rejected because "{why}": {str(body)[:160]}')
event_b = {'eventId': None, 'type': 'FOOD', 'title': 'Pizza', 'description': 'Offen für alle', 'location': None, 'startDate': NOW + 3 * DAY,
           'closeDate': None, 'invitedUsers': [], 'visibility': 'PUBLIC', 'maxUsers': None, 'entryFeeCents': 0, 'groupDeleteDelay': 'NEVER', 'createGroup': True, 'reminderMinutes': [],
           'membersCanAdd': True}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_b)]))))
ev_b = ev.get('id')
check(ev.get('entryFeeCents') == 0, f'free event: {ev.get("entryFeeCents")}')
# Its 5 minute reminder is due in about 20 s; the scheduler (every 60 s) sends it, checked at the end
event_c = {'eventId': None, 'type': 'BEER', 'title': 'Feierabendbier', 'description': 'Gleich', 'location': {'lat': 47.45, 'long': 9.75},
           'startDate': int(time.time() * 1000) + 5 * MIN + 20_000, 'closeDate': None, 'invitedUsers': [b], 'visibility': 'FRIENDS_ONLY',
           'maxUsers': None, 'entryFeeCents': 0, 'groupDeleteDelay': 'ONE_HOUR', 'createGroup': False, 'reminderMinutes': [5], 'membersCanAdd': False}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_c)]))))
ev_c = ev.get('id')
call('POST', '/events/participation', tb, body={'eventId': ev_c, 'status': 'ACCEPTED'}, ok=200)
# Move start and location: change alerts and system messages
ws_b = WS(tb)
time.sleep(.4)
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', {**event_a, 'eventId': ev_a, 'startDate': NOW + 2 * DAY + HOUR,
                                                                                                  'location': {'lat': 47.6, 'long': 9.8}, 'title': 'Kicken am Strand'})]))))
check(ev.get('entryFeeCents') == 1250, f'edit keeps the entry fee: {ev.get("entryFeeCents")}')
time.sleep(.6)
alerts = [m for m in ws_b.messages if m.get('_class') == 'eventupdatealert']
check(bool(alerts) and all(m.get('actorId') == a for m in alerts), f'socket eventupdatealert carries the actor id: {alerts}')
ws_b.close()
for tok, status in [(tb, 'ACCEPTED'), (tc, 'DISMISSED'), (td, 'SEEN')]:
    call('POST', '/events/participation', tok, body={'eventId': ev_a, 'status': status}, ok=200)
call('POST', '/events/join', td, body={'eventId': ev_b}, ok=200)
call('POST', '/events/sync', ta, body=[], params={'page': 0, 'page_size': 400}, ok=200)
# Detach the chat but keep it, then turn the group on again: the same chat comes back, no second one
event_r = {**event_b, 'title': 'Grillen', 'groupDeleteDelay': 'ONE_DAY'}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_r)]))))
ev_r, group_r = ev.get('id'), ev.get('groupId')
call('POST', '/events/join', tb, body={'eventId': ev_r}, ok=200)
call('DELETE', '/events/delete', ta, params={'eventid': ev_r, 'deletegroup': 'false', 'deleteevent': 'false'}, ok=200)
code, synced = call('POST', '/events/sync', ta, body=[], params={'page': 0, 'page_size': 400}, ok=200)
detached = next((e for e in (synced or {}).get('updatedEvents', []) if e.get('id') == ev_r), {})
check(detached.get('groupId') is None and detached.get('previousGroupId') == group_r, f'detached event remembers its kept chat: {detached}')
# Out of the chat's reach for now, so b can say no - reconnecting the chat makes its members going again
call('POST', '/events/participation', tb, body={'eventId': ev_r, 'status': 'DISMISSED'}, ok=200)
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', {**event_r, 'eventId': ev_r})]))))
check(ev.get('groupId') == group_r and ev.get('previousGroupId') is None, f'createGroup reconnects the detached chat: {ev}')
b_status = next((p.get('status') for p in ev.get('participations', []) if p.get('userId') == b), None)
check(b_status == 'ACCEPTED', f'reconnected chat member counts as going again: {b_status}')

# ─── Groups ───────────────────────────────────────────────────────────────────
mp = multipart([('name', 'Testgruppe', None, None), ('memberlist', f'{b},{c}', None, None), ('description', 'Alles zum Testen', None, None),
                ('profilepic', PNG, 'group.png', 'image/png'), ('membersCanAdd', 'false', None, None)])
code, g2 = call('POST', '/groups/create', ta, data=mp[0], ctype=mp[1], ok=200)
group2 = g2.get('id') if isinstance(g2, dict) else None
check(isinstance(g2, dict) and g2.get('membersCanAdd') is False, f'group create: membersCanAdd in the response: {g2}')
# membersCanAdd is a required part (hard cut)
mp = multipart([('name', 'Ohne Schalter', None, None), ('memberlist', f'{b},{c}', None, None), ('description', '', None, None),
                ('profilepic', PNG, 'group.png', 'image/png')])
code, body = call('POST', '/groups/create', ta, data=mp[0], ctype=mp[1], ok=400)
check('membersCanAdd' in str(body), f'group create without membersCanAdd: rejected for the missing part: {str(body)[:160]}')
# Only the event's creator and people who join are in its group chat, so messages, extensions and
# member changes go to the created group (alice, bob, carol)
group = group2 or group
group2 = group
call('POST', '/groups/sync', ta, body=[], ok=200)
call('POST', '/groups/setprofilepic', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([('groupid', group, None, None), ('profilepic', PNG, 'g.png', 'image/png')]))))
call('GET', '/groups/profilepic/{id}', ta, vars={'id': group}, ok=200)
call('POST', '/groups/settings', ta, body={'groupId': group, 'name': 'Kicker', 'description': 'Neue Beschreibung', 'expiresAt': NOW + 30 * DAY,
                                          'clearExpiresAt': False, 'membersCanAdd': False}, ok=200)
# Picture access through a shared group. A pending request carol -> dave: carol sent it, so dave's
# setting (friends and groups) decides for her, and they share no group yet. Dave, the receiver,
# always sees carol's picture. Adding dave next to carol then touches their friendship row.
for tok, name in [(tc, 'carol'), (td, 'dave')]:  # seeded users have no picture file yet (404)
    call('POST', '/users/setprofilepic', tok, ok=200, **dict(zip(('data', 'ctype'), multipart([('profilepic', PNG, f'{name}.png', 'image/png')]))))
ws_d = WS(td)
time.sleep(.4)
call('POST', '/users/addfriend/{id}', tc, vars={'id': d}, ok=200)
time.sleep(.6)
requests = [m for m in ws_d.messages if m.get('_class') == 'friendrequest']
check(bool(requests) and requests[-1].get('requestingUser') == c, f'socket friendrequest carries the requester id: {requests}')
ws_d.close()
call('GET', '/users/profilepic/{id}', td, vars={'id': c}, ok=200)
call('GET', '/users/profilepic/{id}', tc, vars={'id': d}, ok=403)
check(pic_on_sync_row(tc, d) is None, 'pending sender, no shared group: null timestamp on the sync row')
check(isinstance(pic_on_sync_row(td, c), int), 'pending receiver: timestamp on the sync row')


def carol_dave_row():
    out = mongo(f"""const f = db.friendships.findOne({{$or: [{{userId1: ObjectId('{c}'), userId2: ObjectId('{d}')}}, {{userId1: ObjectId('{d}'), userId2: ObjectId('{c}')}}]}});
print(JSON.stringify({{date: f.updatedAt instanceof Date, keys: Object.keys(f.updatedAt).sort(), sec: Number(f.updatedAt.epochSeconds), nanos: Number(f.updatedAt.nanosecondsOfSecond)}}))""")
    try:
        return json.loads(out.splitlines()[-1])
    except Exception:
        return {'unreadable': out[:200]}


row_before = carol_dave_row()
ws_c = WS(tc)
time.sleep(.4)
ws_c.messages.clear()
call('POST', '/groups/changemembers', ta, body={'action': 'ADD_USER', 'groupMemberId': d, 'groupId': group2}, ok=200)
check(isinstance(pic_on_socket(ws_c, d), int), 'shared group joined: userchange carries the timestamp')
call('GET', '/users/profilepic/{id}', tc, vars={'id': d}, ok=200)
check(isinstance(pic_on_sync_row(tc, d), int), 'shared group: timestamp on the sync row')
check(isinstance(looked_up(lookup(tc, [d]), d).get('profilePicUpdatedAt'), int), 'lookup: shared group shows the picture')
for action, member in [('MAKE_ADMIN', b), ('REMOVE_ADMIN', b), ('MAKE_ADMIN', b), ('REMOVE_USER', d)]:
    call('POST', '/groups/changemembers', ta, body={'action': action, 'groupMemberId': member, 'groupId': group2}, ok=200)
check(pic_on_socket(ws_c, d) is None, 'shared group left: userchange carries a null timestamp')
call('GET', '/users/profilepic/{id}', tc, vars={'id': d}, ok=403)
check(pic_on_sync_row(tc, d) is None, 'shared group left: null timestamp on the sync row')
check(looked_up(lookup(tc, [d]), d).get('profilePicUpdatedAt', 0) is None, 'lookup: shared group left hides the picture')
ws_c.close()

# The touch is a MongoTemplate update: updatedAt must stay the kotlin.time.Instant sub-document,
# never a BSON Date, and it must have moved
row_after = carol_dave_row()
check(row_after.get('date') is False and row_after.get('keys') == ['epochSeconds', 'nanosecondsOfSecond'],
      f'touched friendship row keeps the kotlin.time.Instant shape: {row_after}')
check((row_after.get('sec', 0), row_after.get('nanos', 0)) > (row_before.get('sec', 0), row_before.get('nanos', 0)),
      f'shared group change moved the friendship row: {row_before} -> {row_after}')
call('POST', '/users/denyfriend/{id}', td, vars={'id': c}, ok=200)  # back to no row, as before

# ─── Group rights: settings and "members may add" ─────────────────────────────
# Own group "Rechte": alice admin, bob and carol members. Friends: bob-frank, bob-carol, alice-dave;
# bob-dave, frank-erin are not. Every rejected call is checked to have changed nothing.


def group_of(tok, gid):
    code, synced = call('POST', '/groups/sync', tok, body=[], ok=200)
    return next((g for g in (synced or {}).get('updatedGroups', []) if g.get('id') == gid), {}) if isinstance(synced, dict) else {}


def members_of(gid):
    return {m.get('userid'): m.get('admin') for m in group_of(ta, gid).get('members', [])}


def rejected_for(body, why, label):
    """A rejection must fail for the intended reason, not for some other validation on the way."""
    check(why in str(body), f'{label}: rejected because "{why}": {str(body)[:160]}')


def add_member(tok, gid, uid, ok, why=None, action='ADD_USER'):
    code, body = call('POST', '/groups/changemembers', tok, body={'action': action, 'groupMemberId': uid, 'groupId': gid}, ok=ok)
    if why:
        rejected_for(body, why, f'{action} {uid[-4:]} in {gid[-4:]}')


def group_settings(tok, gid, ok, why=None, **fields):
    code, body = call('POST', '/groups/settings', tok, body={'groupId': gid, **fields}, ok=ok)
    if why:
        rejected_for(body, why, f'settings {sorted(fields)}')


def system_messages(gid, event_type, actor=None):
    actor_q = f", 'systemEvent.actorId': ObjectId('{actor}')" if actor else ''
    out = mongo(f"print(db.messages.countDocuments({{receiverId: ObjectId('{gid}'), 'systemEvent.eventType': '{event_type}'{actor_q}}}))")
    try:
        return int(out.strip().splitlines()[-1])
    except Exception:
        return -1


mp = multipart([('name', 'Rechte', None, None), ('memberlist', f'{b},{c}', None, None), ('description', 'Wer darf was', None, None),
                ('profilepic', PNG, 'rights.png', 'image/png'), ('membersCanAdd', 'false', None, None)])
code, gr = call('POST', '/groups/create', ta, data=mp[0], ctype=mp[1], ok=200)
g_rights = gr.get('id') if isinstance(gr, dict) else None

# Flag off: a member adds nobody, not even a friend
add_member(tb, g_rights, f, 400, why='You are not an admin')
check(f not in members_of(g_rights), 'flag off: a member can not add, frank stays out')

# Name and description: every member. Timer and flag: admins only, sending one rejects the whole request
group_settings(tb, g_rights, 200, name='Rechte2', description='von bob')
g = group_of(ta, g_rights)
check(g.get('name') == 'Rechte2' and g.get('description') == 'von bob', f'member renames and describes: {g.get("name")}, {g.get("description")}')
check(system_messages(g_rights, 'GROUP_NAME_CHANGED', b) == 1 and system_messages(g_rights, 'GROUP_DESCRIPTION_CHANGED', b) == 1,
      'member rename and description: one system message each, actor bob')
for bad in ({'name': 'Gekapert', 'membersCanAdd': True}, {'expiresAt': NOW + DAY}, {'clearExpiresAt': True}, {'membersCanAdd': False}):
    group_settings(tb, g_rights, 400, why='You are not an admin', **bad)
g = group_of(ta, g_rights)
check(g.get('name') == 'Rechte2' and g.get('membersCanAdd') is False and g.get('expiresAt') is None,
      f'member sending admin settings: rejected, nothing changed: {g.get("name")}, {g.get("membersCanAdd")}, {g.get("expiresAt")}')
for tok in (te, tf):  # not in the group
    group_settings(tok, g_rights, 400, why='You are not a member of this group', name='Fremd')
group_settings(ta, 'nope', 400, why='Invalid group ID', name='Fremd')
for why, bad in [('Group name invalid', {'name': 'ab'}), ('Description invalid', {'description': 'x' * 201}),
                 ('Expiry date must be in the future', {'expiresAt': NOW - DAY}),
                 ('Set or clear the delete timer, not both', {'expiresAt': NOW + DAY, 'clearExpiresAt': True}),
                 ('Expiry date must be in the future', {'name': 'Gueltig', 'expiresAt': NOW - DAY})]:
    group_settings(ta, g_rights, 400, why=why, **bad)
check(group_of(ta, g_rights).get('name') == 'Rechte2', 'invalid settings: a valid field in a rejected request is not written either')
group_settings(ta, g_rights, 200, expiresAt=NOW + 30 * DAY)
check(group_of(ta, g_rights).get('expiresAt') == NOW + 30 * DAY, 'admin sets the delete timer')
# The client sends every field, the unchanged ones as explicit null: null is "unchanged", also for a member
NULLS = {'name': None, 'description': None, 'expiresAt': None, 'clearExpiresAt': None, 'membersCanAdd': None}
group_settings(tb, g_rights, 200, **{**NULLS, 'description': 'mit nulls'})
g = group_of(ta, g_rights)
check(g.get('description') == 'mit nulls' and g.get('name') == 'Rechte2' and g.get('expiresAt') == NOW + 30 * DAY and g.get('membersCanAdd') is False,
      f'explicit nulls leave the other settings alone, also for a member: {g.get("name")}, {g.get("expiresAt")}, {g.get("membersCanAdd")}')
group_settings(ta, g_rights, 200, **{**NULLS, 'clearExpiresAt': True})
check(group_of(ta, g_rights).get('expiresAt') is None, 'admin clears the delete timer (clearExpiresAt true, expiresAt null)')
group_settings(tb, g_rights, 200, **{**NULLS, 'description': 'von bob'})
before = (system_messages(g_rights, 'GROUP_NAME_CHANGED'), system_messages(g_rights, 'GROUP_DESCRIPTION_CHANGED'))
group_settings(ta, g_rights, 200, name='Rechte2', description='von bob')  # same values
after = (system_messages(g_rights, 'GROUP_NAME_CHANGED'), system_messages(g_rights, 'GROUP_DESCRIPTION_CHANGED'))
check(before == after and before[0] == 1, f'resending the same name and description: no new system message: {before} -> {after}')

# Flag on: members add their own friends, nothing else
group_settings(ta, g_rights, 200, membersCanAdd=True)
check(group_of(tb, g_rights).get('membersCanAdd') is True, 'admin turns membersCanAdd on, members see it')
add_member(tb, g_rights, d, 400, why='You can only add your friends')   # dave is not bob's friend
add_member(te, g_rights, f, 400, why='You are not a member of this group')   # erin is not in the group
add_member(tb, g_rights, f, 200)   # frank is bob's friend
add_member(tb, g_rights, f, 400, why='User is already in this group')
add_member(tf, g_rights, e, 400, why='You can only add your friends')   # frank and erin are not friends
check(system_messages(g_rights, 'GROUP_MEMBER_ADDED', b) == 1, 'member add: GROUP_MEMBER_ADDED with bob as actor')
for action, member in [('REMOVE_USER', c), ('MAKE_ADMIN', f), ('MAKE_ADMIN', b), ('REMOVE_ADMIN', a)]:
    add_member(tb, g_rights, member, 400, why='You are not an admin', action=action)
code, body = call('DELETE', '/groups/delete', tb, params={'groupid': g_rights}, ok=400)
rejected_for(body, 'You are not an admin', 'member deletes the group')
m = members_of(g_rights)
check(m == {a: True, b: False, c: False, f: False}, f'member add: only frank came in; remove, admin rights and delete stay admin-only: {m}')
add_member(ta, g_rights, d, 200)   # admins unchanged
call('DELETE', '/groups/delete', ta, params={'groupid': g_rights}, ok=200)

# Event group: a member only invites (no group, no participation), the friend joins later himself
event_p = {'eventId': None, 'type': 'SPORT', 'title': 'Laufen', 'description': 'Mit Freunden', 'location': None, 'startDate': NOW + 4 * DAY,
           'closeDate': None, 'invitedUsers': [], 'visibility': 'FRIENDS_ONLY', 'maxUsers': 3, 'entryFeeCents': 500, 'groupDeleteDelay': 'ONE_DAY',
           'createGroup': True, 'reminderMinutes': [], 'membersCanAdd': True}


def upsert_event(tok, body, ok):
    return call('POST', '/events/upsert', tok, ok=ok, **dict(zip(('data', 'ctype'), multipart([json_part('request', body)]))))


def event_for(tok, eid):
    code, synced = call('POST', '/events/sync', tok, body=[], params={'page': 0, 'page_size': 400}, ok=200)
    return next((x for x in (synced or {}).get('updatedEvents', []) if x.get('id') == eid), None) if isinstance(synced, dict) else None


code, ev = upsert_event(ta, event_p, 200)
ev_p, group_p = ev.get('id'), ev.get('groupId')
check(group_of(ta, group_p).get('membersCanAdd') is True, 'event create: membersCanAdd lands on its group')
call('POST', '/events/join', tb, body={'eventId': ev_p}, ok=200)        # bob: the creator's friend
code, body = call('POST', '/events/join', tf, body={'eventId': ev_p}, ok=400)  # frank: neither the creator's friend nor invited
rejected_for(body, 'You can not access this event', 'stranger joins a FRIENDS_ONLY event')
check(event_for(tf, ev_p) is None, 'FRIENDS_ONLY event: not visible to a stranger before the invite')
ws_f = WS(tf)
time.sleep(.4)
add_member(tb, group_p, d, 400, why='You can only add your friends')    # dave is not bob's friend
add_member(tb, group_p, f, 200)    # frank is: invited
time.sleep(.6)
invites = [x for x in ws_f.messages if x.get('_class') == 'eventchange' and x.get('event', {}).get('id') == ev_p]
check(bool(invites) and invites[-1].get('newEntry') is True and invites[-1].get('inviterId') == b,
      f'invitee: eventchange as new entry naming the inviting member: {invites}')
ws_f.close()
check(f not in members_of(group_p), 'member invite in an event group: frank is not put in the group')
fe = event_for(tf, ev_p)
check(fe is not None and f in fe.get('invitedUsers', []) and not any(x.get('userId') == f for x in fe.get('participations', [])),
      f'invited friend of a member sees the FRIENDS_ONLY event, without a participation: {fe}')
check(system_messages(group_p, 'GROUP_MEMBER_ADDED') == 0, 'member invite: no system message')
add_member(tb, group_p, f, 200)    # already invited: no-op
add_member(tb, group_p, a, 200)    # already in the group: no-op
call('POST', '/events/join', tf, body={'eventId': ev_p}, ok=200)
check(f in members_of(group_p), 'the invited friend joins himself')
# Full now (alice, bob, frank = maxUsers 3): an admin can not go over the cap, an invite still works
add_member(ta, group_p, c, 400, why='Event is full')
check(c not in members_of(group_p), 'admin add in a full event group: rejected')
add_member(tb, group_p, c, 200)
ce = event_for(tc, ev_p)
check(ce is not None and c in ce.get('invitedUsers', []) and c not in members_of(group_p), 'invite into a full event: only on the invite list')
# Only the creator sets it, with the event; a member's event edit is rejected and changes nothing
code, body = upsert_event(tb, {**event_p, 'eventId': ev_p, 'membersCanAdd': False}, 400)
rejected_for(body, 'you are not the creator', 'member edits the event')
check(group_of(ta, group_p).get('membersCanAdd') is True, 'member can not change the flag through the event')
upsert_event(ta, {**event_p, 'eventId': ev_p, 'membersCanAdd': False}, 200)
check(group_of(ta, group_p).get('membersCanAdd') is False, 'creator turns the flag off with the event')
add_member(tb, group_p, c, 400, why='You are not an admin')    # flag off: members invite nobody
call('DELETE', '/events/delete', ta, params={'eventid': ev_p, 'deletegroup': 'true', 'deleteevent': 'true'}, ok=200)

# ─── Messages ─────────────────────────────────────────────────────────────────

def text_message(tok, receiver, content, group_msg=False, answer=None):
    code, m = call('POST', '/messages/send/text', tok, body={'messageId': None, 'receiverId': receiver, 'groupMessage': group_msg, 'msgType': 'TEXT',
                                                            'content': content, 'answerId': answer, 'clientMessageId': str(uuid.uuid4())}, ok=200)
    return m.get('messageId') if isinstance(m, dict) else None


m1 = text_message(ta, b, '**Hallo** _Bob_, `code` und @Annotations')
m2 = text_message(tb, a, 'Antwort', answer=m1)
m3 = text_message(ta, group, 'Hallo Gruppe', group_msg=True)
mp = multipart([('image', PNG, 'photo.png', 'image/png'), json_part('request', {'messageId': None, 'receiverId': b, 'groupMessage': False, 'msgType': 'IMAGE',
                                                                               'content': 'Bildtext', 'answerId': m2, 'clientMessageId': str(uuid.uuid4())})])
code, im = call('POST', '/messages/send/image', ta, data=mp[0], ctype=mp[1], ok=200)
mp = multipart([('audio', M4A, 'voice.m4a', 'audio/mp4'), json_part('request', {'messageId': None, 'receiverId': group, 'groupMessage': True, 'msgType': 'AUDIO',
                                                                               'answerId': m3, 'clientMessageId': str(uuid.uuid4())})])
code, au = call('POST', '/messages/send/audio', ta, data=mp[0], ctype=mp[1], ok=200)
call('GET', '/messages/images/{id}', tb, vars={'id': im.get('messageId') if isinstance(im, dict) else '0' * 24}, ok=200)
call('GET', '/messages/audios/{id}', tb, vars={'id': au.get('messageId') if isinstance(au, dict) else '0' * 24}, ok=200)
check(isinstance(im, dict) and im.get('mediaExpired') is False, f"new image message: mediaExpired false: {str(im)[:120]}")

# ─── Chat images: stored as uploaded, 6 MB cap, content checked ──────────────

def raw_get(path, tok):
    """Status, content type and raw bytes (call() decodes the body as text)."""
    req = urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + tok})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.headers.get('Content-Type', ''), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get('Content-Type', ''), e.read()


def jpeg(w, h, app1=b''):
    """A flat grey baseline JPEG built by hand (standard library only): one-symbol Huffman tables,
    every 8x8 block is "DC difference 0, end of block"."""
    def seg(marker, data):
        return bytes([0xFF, marker]) + struct.pack('>H', len(data) + 2) + data
    bits = '00' * (((w + 7) // 8) * ((h + 7) // 8))
    bits += '1' * (-len(bits) % 8)
    scan = bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits), 8)).replace(b'\xff', b'\xff\x00')
    one_code = b'\x01' + b'\x00' * 15 + b'\x00'  # one code of length 1 for symbol 0
    return (b'\xff\xd8' + seg(0xE0, b'JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00') + app1
            + seg(0xDB, b'\x00' + bytes([1] * 64))
            + seg(0xC0, b'\x08' + struct.pack('>HH', h, w) + b'\x01\x01\x11\x00')
            + seg(0xC4, b'\x00' + one_code) + seg(0xC4, b'\x10' + one_code)
            + seg(0xDA, b'\x01\x01\x00\x00\x3f\x00') + scan + b'\xff\xd9')


def exif_app1():
    """APP1 with an EXIF orientation (6 = rotated) and a GPS latitude: the server must keep both."""
    tiff = b'II*\x00' + struct.pack('<I', 8)
    tiff += struct.pack('<H', 2) + struct.pack('<HHII', 0x0112, 3, 1, 6) + struct.pack('<HHII', 0x8825, 4, 1, 38) + struct.pack('<I', 0)
    tiff += struct.pack('<H', 2) + struct.pack('<HHI', 0x0001, 2, 2) + b'N\x00\x00\x00' + struct.pack('<HHII', 0x0002, 5, 3, 68) + struct.pack('<I', 0)
    tiff += struct.pack('<6I', 47, 1, 24, 1, 0, 1)
    data = b'Exif\x00\x00' + tiff
    return b'\xff\xe1' + struct.pack('>H', len(data) + 2) + data


def png_chunk(kind, data):
    c = kind + data
    return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)


def noise_png(w, h):
    """Random pixels barely compress, so the file is about w * h * 3 bytes."""
    rows = b''.join(b'\x00' + os.urandom(w * 3) for _ in range(h))
    return b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + png_chunk(b'IDAT', zlib.compress(rows, 1)) + png_chunk(b'IEND', b'')


def send_image(tok, receiver, data, filename, ctype, ok=200, why=None, caption='Bild'):
    mp = multipart([('image', data, filename, ctype), json_part('request', {'messageId': None, 'receiverId': receiver, 'groupMessage': False, 'msgType': 'IMAGE',
                                                                         'content': caption, 'answerId': None, 'clientMessageId': str(uuid.uuid4())})])
    code, body = call('POST', '/messages/send/image', tok, data=mp[0], ctype=mp[1], ok=ok)
    if why:
        rejected_for(body, why, f'image upload {filename} ({len(data)} bytes)')
    return body.get('messageId') if isinstance(body, dict) else None


def image_back(mid, sent, ctype, label):
    code, got_type, got = raw_get(f'/messages/images/{mid}', tb)
    check(code == 200 and got == sent, f'{label}: stored byte for byte ({code}, {len(got)} of {len(sent)} bytes)')
    check(got_type.startswith(ctype), f'{label}: served as {ctype}: {got_type}')
    return got


MB = 1024 * 1024
JPG_EXIF = jpeg(64, 48, exif_app1())
got = image_back(send_image(ta, b, JPG_EXIF, 'photo.jpg', 'image/jpeg'), JPG_EXIF, 'image/jpeg', 'JPEG with EXIF')
check(b'Exif\x00\x00' in got and b'N\x00\x00\x00' in got, 'JPEG with EXIF: EXIF orientation and GPS kept')
image_back(im.get('messageId') if isinstance(im, dict) else '0' * 24, PNG, 'image/png', 'PNG')
# The type comes from the bytes, not from what the upload claims
image_back(send_image(ta, b, JPG_EXIF, 'claimed.png', 'image/png'), JPG_EXIF, 'image/jpeg', 'JPEG sent as image/png')
BIG = noise_png(1250, 1250)
check(3 * MB < len(BIG) < 6 * MB, f'big test image is between the old 3 MB and the new 6 MB cap: {len(BIG)} bytes')
image_back(send_image(ta, b, BIG, 'big.png', 'image/png'), BIG, 'image/png', 'big image (no downscale)')
# Not trusted: size, real format, pixel count and decodability are checked on the server
send_image(ta, b, b'\x89PNG\r\n\x1a\n' + b'\x00' * (6 * MB), 'huge.png', 'image/png', ok=413, why='File too large')
send_image(ta, b, b'this is not an image' * 50, 'fake.png', 'image/png', ok=400, why='Unsupported image format')
send_image(ta, b, b'RIFF\x00\x00\x00\x00WEBPVP8 ' + b'\x00' * 200, 'pic.webp', 'image/webp', ok=400, why='Unsupported image format')
send_image(ta, b, b'\xff\xd8\xff' + b'\x00' * 500, 'broken.jpg', 'image/jpeg', ok=400, why='Invalid image data')
BOMB = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 12000, 10000, 8, 2, 0, 0, 0))
        + png_chunk(b'IDAT', zlib.compress(b'\x00' * 1000)) + png_chunk(b'IEND', b''))
send_image(ta, b, BOMB, 'bomb.png', 'image/png', ok=400, why='Image has too many pixels')
send_image(ta, b, PNG, 'photo.bmp', 'image/png', ok=400, why='Invalid image')
# Profile pictures keep their 3 MB cap
code, body = call('POST', '/users/setprofilepic', ta, ok=400, **dict(zip(('data', 'ctype'), multipart([('profilepic', BIG, 'big.png', 'image/png')]))))
rejected_for(body, 'New picture is invalid', 'profile picture over 3 MB')

# ─── Media retention: files of image and voice messages go after 60 days ─────
# run.sh starts the job every 2 seconds (MEDIA_RETENTION_CRON); sendDate is moved back in Mongo.

def mongo_ids(ids):
    return '[' + ', '.join(f"ObjectId('{i}')" for i in ids) + ']'


def backdate(ids, days):
    mongo(f"db.messages.updateMany({{_id: {{$in: {mongo_ids(ids)}}}}}, {{$set: {{'sendDate.epochSeconds': {int(time.time()) - days * 86400}}}}})")


def stored_field(mid, field):
    return mongo(f"print(String(db.messages.findOne({{_id: ObjectId('{mid}')}}).{field}))")


def server_files(folder):
    o = subprocess.run(['docker', 'exec', SERVER_CONTAINER, 'ls', folder], capture_output=True, text=True)
    return o.stdout


def synced_message(tok, mid):
    code, body = call('GET', '/messages/sync', tok, params={'since': 0, 'page_size': 400}, ok=200)
    return next((m for m in body.get('updatedMessages', []) if m.get('messageId') == mid), {}) if isinstance(body, dict) else {}


old_img = send_image(ta, b, JPG_EXIF, 'old.jpg', 'image/jpeg', caption='Altes Bild')
mp = multipart([('audio', M4A, 'old.m4a', 'audio/mp4'), json_part('request', {'messageId': None, 'receiverId': group, 'groupMessage': True, 'msgType': 'AUDIO',
                                                                             'answerId': None, 'clientMessageId': str(uuid.uuid4())})])
code, old_au = call('POST', '/messages/send/audio', ta, data=mp[0], ctype=mp[1], ok=200)
old_au = old_au.get('messageId') if isinstance(old_au, dict) else None
young_img = send_image(ta, b, PNG, 'young.png', 'image/png', caption='59 Tage')
deleted_img = send_image(ta, b, PNG, 'deleted.png', 'image/png', caption='gelöscht')
old_txt = text_message(ta, b, 'Alter Text')
call('DELETE', '/messages/delete', ta, params={'messageid': deleted_img}, ok=200)
version_before = synced_message(tb, old_img).get('version', 0)
backdate([old_img, old_au, deleted_img, old_txt], 61)
backdate([young_img], 59)
for _ in range(30):
    if stored_field(old_img, 'mediaExpired') == 'true' and stored_field(old_au, 'mediaExpired') == 'true':
        break
    time.sleep(.5)
check(stored_field(old_img, 'mediaExpired') == 'true', 'retention: 61 day old image marked mediaExpired')
check(stored_field(old_au, 'mediaExpired') == 'true', 'retention: 61 day old voice message marked mediaExpired')
code, body = call('GET', '/messages/images/{id}', tb, vars={'id': old_img}, ok=410)
rejected_for(body, 'Media expired', 'expired image download')
code, body = call('GET', '/messages/audios/{id}', tb, vars={'id': old_au}, ok=410)
rejected_for(body, 'Media expired', 'expired voice download')
images_left, audios_left = server_files('/app/images_messages'), server_files('/app/audio_messages')
check(old_img not in images_left and young_img in images_left, 'retention: old image file deleted, 59 day old one kept')
check(old_au not in audios_left, 'retention: old voice file deleted')
image_back(young_img, PNG, 'image/png', '59 day old image')
check(stored_field(young_img, 'mediaExpired') in ('false', 'undefined'), 'retention: 59 day old image not marked')
check(stored_field(deleted_img, 'mediaExpired') in ('false', 'undefined'), 'retention: deleted message not touched')
check(stored_field(old_txt, 'mediaExpired') in ('false', 'undefined'), 'retention: text message not touched')
expired = synced_message(tb, old_img)
check(expired.get('mediaExpired') is True and expired.get('version', 0) > version_before,
      f"retention: sync delivers mediaExpired with a new version ({version_before} -> {expired.get('version')})")
check(expired.get('content') == 'Altes Bild' and expired.get('deleted') is False, 'retention: caption kept, message not deleted')
call('POST', '/messages/react', tb, body={'messageId': old_img, 'content': '👍'}, ok=200)
call('POST', '/messages/edit', ta, body={'messageId': old_img, 'newContent': 'Altes Bild (bearbeitet)'}, ok=200)
version_after = stored_field(old_img, 'version')
time.sleep(2.5)
check(stored_field(old_img, 'version') == version_after, 'retention: a marked message is not written again by the next run')


def poll(title, visibility, sub=True):
    return {'title': title, 'description': 'Bitte abstimmen', 'maxAnswers': 2, 'customAnswersEnabled': True, 'maxAllowedCustomAnswers': 3,
            'visibility': visibility, 'closeDate': NOW + DAY, 'allowDeleteOptions': True, 'showCheckboxes': True, 'visibleToAll': True,
            'voteOptions': [{'text': 'Ja', 'maxVoters': 5, 'subPoll': {'title': 'Wann?', 'description': 'Tag wählen', 'maxAnswers': 1, 'customAnswersEnabled': True,
                                                                         'maxAllowedCustomAnswers': 2, 'visibility': visibility, 'closeDate': None,
                                                                         'allowDeleteOptions': True, 'showCheckboxes': True, 'visibleToAll': False,
                                                                         'voteOptions': [{'text': 'Morgen'}, {'text': 'Übermorgen'}]} if sub else None},
                            {'text': 'Nein', 'maxVoters': None}]}


polls = {}
for vis in ('PUBLIC', 'PRIVATE', 'ANONYMOUS'):
    code, pm = call('POST', '/messages/send/poll', ta, body={'receiverId': group, 'groupMessage': True, 'msgType': 'POLL', 'answerId': None,
                                                             'poll': poll(f'Umfrage {vis}', vis), 'clientMessageId': str(uuid.uuid4())}, ok=200)
    polls[vis] = pm if isinstance(pm, dict) else {}
pmsg = polls['PUBLIC']
options = {o['text']: o['id'] for o in find_all(pmsg, lambda x: 'id' in x and 'text' in x and isinstance(x.get('text'), str))}
pid = pmsg.get('messageId')
call('POST', '/messages/pollvote', tb, body={'messageId': pid, 'id': options.get('Ja'), 'text': None, 'maxAllowedAnswers': None, 'selected': True}, ok=200)
call('POST', '/messages/pollvote', tb, body={'messageId': pid, 'id': options.get('Morgen'), 'text': None, 'maxAllowedAnswers': None, 'selected': True}, ok=200)
code, voted = call('POST', '/messages/pollvote', tc, body={'messageId': pid, 'id': None, 'text': 'Vielleicht', 'maxAllowedAnswers': 2, 'selected': True}, ok=200)
call('POST', '/messages/pollvote', tb, body={'messageId': pid, 'id': None, 'text': 'Sonntag', 'maxAllowedAnswers': 1, 'selected': True, 'parentOptionId': options.get('Ja')}, ok=200)
call('POST', '/messages/pollvote', tb, body={'messageId': pid, 'id': options.get('Ja'), 'text': None, 'maxAllowedAnswers': None, 'selected': False}, ok=200)
custom = [o['id'] for o in find_all(voted, lambda x: x.get('text') == 'Vielleicht' and 'id' in x)]
call('POST', '/messages/polloption/delete', ta, body={'messageId': pid, 'optionId': custom[0] if custom else '0' * 24}, ok=200)
edited = poll('Umfrage PUBLIC (geändert)', 'PUBLIC', sub=False)
edited['voteOptions'] = [{'text': 'Ja, sicher', 'maxVoters': 6, 'id': options.get('Ja')}, {'text': 'Nein', 'id': options.get('Nein')}, {'text': 'Neu'}]
call('POST', '/messages/edit/poll', ta, body={'messageId': pid, 'poll': edited, 'deletedOptionIds': []}, ok=200)
call('POST', '/messages/edit', ta, body={'messageId': m1, 'newContent': 'Hallo Bob (bearbeitet)'}, ok=200)
call('POST', '/messages/react', tb, body={'messageId': m1, 'content': '👍'}, ok=200)
call('POST', '/messages/react', tc, body={'messageId': m3, 'content': 'super'}, ok=200)
call('POST', '/messages/setread', tb, params={'userid': a, 'group': 'false', 'timestamp': int(time.time() * 1000)}, ok=200)
call('POST', '/messages/setread', tb, params={'userid': group, 'group': 'true', 'timestamp': int(time.time() * 1000)}, ok=200)
call('GET', '/messages/sync', ta, params={'since': 0, 'page_size': 400}, ok=200)
call('POST', '/wake/send', ta, body={'targetId': b, 'isGroup': False, 'reason': 'Aufstehen!'})
call('POST', '/wake/send', ta, body={'targetId': group, 'isGroup': True, 'reason': 'Alle aufstehen'})

# ─── Map: one entry per location type, every attribute filled ────────────────
map_ids = []
for i, data in enumerate(location_datas()):
    code, me = call('POST', '/map/upsert', tb, body={'entryId': None, 'name': f'Ort {data["_class"]}', 'description': f'Test für {data["_class"]}',
                                                     'coordinates': {'lat': 47.40 + i * 0.001, 'long': 9.70 + i * 0.001}, 'locationData': [data]}, ok=200)
    if isinstance(me, dict):
        map_ids.append(me['id'])
code, me = call('POST', '/map/upsert', tc, body={'entryId': map_ids[0], 'name': 'Ort geändert', 'description': 'von Carol bearbeitet',
                                                 'coordinates': {'lat': 47.41, 'long': 9.71}, 'locationData': [location_datas()[0], location_datas()[-1]]}, ok=200)
call('POST', '/map/sync', ta, body=[], params={'page': 0, 'page_size': 400}, ok=200)

# ─── Group extensions ─────────────────────────────────────────────────────────
ext = {}
for etype, settings_ in [('SHARED_TEXT', {'_class': 'none'}), ('SPLIT', {'_class': 'split', 'currency': 'EUR'}),
                         ('QUOTES', {'_class': 'none'}), ('ROUTE_PLANNER', {'_class': 'route', 'transportMode': 'car', 'startDate': NOW + DAY})]:
    code, x = call('POST', '/groupextensions/upsert', ta, body={'extensionId': None, 'ownerType': 'GROUP', 'ownerId': group, 'type': etype,
                                                               'title': etype.title(), 'settings': settings_}, ok=200)
    ext[etype] = x.get('id') if isinstance(x, dict) else None
call('POST', '/groupextensions/upsert', ta, body={'extensionId': ext['QUOTES'], 'ownerType': 'GROUP', 'ownerId': group, 'type': 'QUOTES',
                                                 'title': 'Zitate der Woche', 'settings': {'_class': 'none'}}, ok=200)
items = [
    (ext['SHARED_TEXT'], {'_class': 'shared_text', 'text': 'Einkaufsliste: Brot, Käse', 'writers': []}),
    (ext['SPLIT'], {'_class': 'split_expense', 'title': 'Pizza', 'amountCents': 2400, 'paidBy': a,
                    'shares': {a: {'weight': 1, 'fixedCents': 0}, b: {'weight': 2, 'fixedCents': 100}}, 'date': NOW, 'isSettlement': False, 'emoji': '🍕'}),
    (ext['SPLIT'], {'_class': 'split_expense', 'title': 'Zurückgezahlt', 'amountCents': 900, 'paidBy': b,
                    'shares': {a: {'weight': 1, 'fixedCents': 0}}, 'date': NOW, 'isSettlement': True, 'emoji': None}),
    (ext['QUOTES'], {'_class': 'quote', 'text': 'Des goht scho', 'saidByUserId': b, 'saidByName': None, 'saidAt': NOW - DAY, 'context': 'beim Grillen'}),
    (ext['QUOTES'], {'_class': 'quote', 'text': 'Früher war alles besser', 'saidByUserId': None, 'saidByName': 'Opa', 'saidAt': None, 'context': None}),
    (ext['ROUTE_PLANNER'], {'_class': 'route_point', 'coordinates': {'lat': 47.5, 'long': 9.7}, 'name': 'Start', 'description': 'Parkplatz',
                            'sortIndex': 0, 'kind': 'STOP', 'plannedAt': NOW + DAY, 'mapEntryId': map_ids[1] if len(map_ids) > 1 else None, 'transportMode': None}),
    (ext['ROUTE_PLANNER'], {'_class': 'route_point', 'coordinates': {'lat': 47.6, 'long': 9.9}, 'name': 'Hütte', 'description': 'Übernachtung',
                            'sortIndex': 1, 'kind': 'ACCOMMODATION', 'plannedAt': NOW + DAY + 5 * HOUR, 'mapEntryId': None, 'transportMode': 'CAR'}),
]
item_ids = []
for ext_id, data in items:
    code, x = call('POST', '/groupextensions/items/upsert', tb, body={'extensionId': ext_id, 'itemId': None, 'data': data}, ok=200)
    item_ids += [(ext_id, i['id']) for i in (x.get('items', []) if isinstance(x, dict) else [])]
    if ext_id == ext['ROUTE_PLANNER'] and isinstance(x, dict):
        route_points = [i['id'] for i in x.get('items', []) if i.get('data', {}).get('_class') == 'route_point']

# Route planner phase 2: unnamed drive-through, transfer, alternative path with its own point
def route_item(tok, data, ok=200):
    code, x = call('POST', '/groupextensions/items/upsert', tok, body={'extensionId': ext['ROUTE_PLANNER'], 'itemId': None, 'data': data}, ok=ok)
    return x if isinstance(x, dict) else {}
x = route_item(tb, {'_class': 'route_point', 'coordinates': {'lat': 47.55, 'long': 9.8}, 'name': '   ', 'description': '', 'sortIndex': 2,
                    'kind': 'WAYPOINT', 'plannedAt': None, 'mapEntryId': None, 'transportMode': None, 'continueTransportMode': 'CAR', 'pathId': None})
waypoint = (x.get('items') or [{}])[-1].get('data', {})
check('name' in waypoint and waypoint['name'] is None and waypoint.get('continueTransportMode') is None,
      'blank WAYPOINT name is stored as null, continueTransportMode cleared off TRANSFER')
route_item(tb, {'_class': 'route_point', 'coordinates': {'lat': 47.58, 'long': 9.85}, 'name': 'Bahnhof', 'description': 'Umstieg', 'sortIndex': 3,
                'kind': 'TRANSFER', 'plannedAt': NOW + DAY + HOUR, 'mapEntryId': None, 'transportMode': 'CAR', 'continueTransportMode': 'FOOT', 'pathId': None})
route_item(tb, {'_class': 'route_point', 'coordinates': {'lat': 47.58, 'long': 9.85}, 'sortIndex': 4, 'kind': 'STOP'}, ok=400)  # name required
x = route_item(tc, {'_class': 'route_path', 'name': 'Über den Pass', 'description': 'die schöne Strecke', 'transportMode': 'MOTORBIKE',
                    'fromItemId': route_points[0] if route_points else '0' * 24, 'toItemId': route_points[-1] if route_points else '0' * 24,
                    'memberIds': [b, c]})
path_id = next((i['id'] for i in x.get('items', []) if i.get('data', {}).get('_class') == 'route_path'), '0' * 24)
route_item(tc, {'_class': 'route_point', 'coordinates': {'lat': 47.62, 'long': 9.95}, 'name': 'Passhöhe', 'description': '', 'sortIndex': 0,
                'kind': 'POI', 'plannedAt': None, 'mapEntryId': None, 'transportMode': 'MOTORBIKE', 'continueTransportMode': None, 'pathId': path_id})
route_item(tc, {'_class': 'route_path', 'name': 'Falsch', 'fromItemId': path_id, 'toItemId': path_id}, ok=400)  # from/to must be route points
route_item(tc, {'_class': 'route_point', 'coordinates': {'lat': 47.6, 'long': 9.9}, 'name': 'X', 'sortIndex': 0,
                'pathId': route_points[0] if route_points else '0' * 24}, ok=400)  # pathId must be a route path
call('POST', '/groupextensions/items/upsert', tc, body={'extensionId': ext['SHARED_TEXT'], 'itemId': item_ids[0][1] if item_ids else None,
                                                       'data': {'_class': 'shared_text', 'text': 'Einkaufsliste: Brot, Käse, Bier', 'writers': []}}, ok=200)
quote_items = [i for x, i in item_ids if x == ext['QUOTES']]
call('DELETE', '/groupextensions/items/delete', ta, params={'extensionid': ext['QUOTES'], 'itemid': quote_items[-1] if quote_items else '0' * 24}, ok=200)
call('POST', '/groupextensions/sync', ta, body=[], params={'page': 0, 'page_size': 100}, ok=200)

# ─── Feedback ─────────────────────────────────────────────────────────────────
code, fs = call('POST', '/feedback/sync', ta, body=[], params={'page': 0, 'page_size': 400}, ok=200)
seeded_rows = [x for x in (fs.get('updatedEntries', []) if isinstance(fs, dict) else []) if x.get('isSeeded')]
seeded = [x['id'] for x in seeded_rows]
check(all(isinstance(x.get('seedKey'), str) and x['seedKey'] for x in seeded_rows), 'seeded feedback entries carry their seedKey')
code, fe = call('POST', '/feedback/create', tb, body={'type': 'FEATURE', 'title': 'Dunkler Kartenmodus', 'description': 'Die Karte auch dunkel',
                                                      'tags': ['MAP', 'UI'], 'location': 'Karte', 'premium': True, 'appVersion': '3.0.19', 'platform': 'iOS'}, ok=200)
code, fb = call('POST', '/feedback/create', tc, body={'type': 'BUG', 'title': 'Chat lädt nicht', 'description': 'Nach dem Start leer',
                                                      'tags': ['CHAT', 'PERFORMANCE'], 'location': None, 'premium': False, 'appVersion': '3.0.18', 'platform': 'Desktop'}, ok=200)
check(isinstance(fe, dict) and 'seedKey' in fe and fe['seedKey'] is None, 'user-created feedback entry has seedKey null')
code, fn = call('POST', '/feedback/create', td, body={'type': 'FEATURE', 'title': 'Ohne Beschreibung', 'description': '   ', 'tags': ['UI']}, ok=200)
check(isinstance(fn, dict) and fn.get('description') == '', 'feedback entry without description: blank stored and sent back as ""')
code, fn2 = call('POST', '/feedback/create', td, body={'type': 'BUG', 'title': 'Auch ohne', 'tags': ['CHAT']}, ok=200)
check(isinstance(fn2, dict) and fn2.get('description') == '', 'feedback entry with missing description sent back as ""')
fe, fb = (x.get('id') if isinstance(x, dict) else None for x in (fe, fb))
for tok, vote in [(ta, 'UP'), (tc, 'DOWN'), (td, 'NOT_NEEDED'), (te, 'UP'), (te, 'NONE')]:
    call('POST', '/feedback/vote', tok, body={'entryId': fe, 'vote': vote}, ok=200)
if seeded:
    call('POST', '/feedback/vote', tb, body={'entryId': seeded[0], 'vote': 'DIDNT_KNOW'}, ok=200)
call('POST', '/feedback/vote', ta, body={'entryId': fb, 'vote': 'UP'}, ok=200)
code, cm = call('POST', '/feedback/comment', tc, body={'entryId': fe, 'text': 'Ja bitte', 'appVersion': '3.0.18', 'platform': 'Desktop'}, ok=200)
call('POST', '/feedback/comment', ta, body={'entryId': fe, 'text': 'Kommt im nächsten Update', 'appVersion': '3.0.19', 'platform': 'Android'}, ok=200)
call('POST', '/feedback/status', ta, body={'entryId': fe, 'status': 'PLANNED'}, ok=200)
call('POST', '/feedback/status', ta, body={'entryId': fb, 'status': 'CONFIRMED'}, ok=200)
code, detail = call('GET', '/feedback/details', tb, params={'entryid': fe}, ok=200)
detail = detail if isinstance(detail, dict) else {}
check((detail.get('entry') or {}).get('creatorId') == b, 'feedback entry carries the creator id')
check(all(x.get('authorId') for x in detail.get('comments', [])), 'feedback comments carry the author id')
before = detail.get('entry') or {}
check(before.get('canEdit') is True and before.get('editedAt') is None, 'feedback creator can edit, entry not edited yet')

# Edits: the creator and admins edit entries, never a seeded one; authors edit their own comments
edit_body = {'entryId': fe, 'type': 'FEATURE', 'title': 'Dunkler Kartenmodus', 'description': 'Die Karte auch dunkel, mit Nachtfarben',
             'tags': ['MAP', 'UI'], 'location': ' Karte '}
code, edited = call('POST', '/feedback/edit', tb, body=edit_body, ok=200)
edited = edited if isinstance(edited, dict) else {}
check(edited.get('editedAt') and edited.get('updatedAt', 0) > before.get('updatedAt', 0) and edited.get('activityAt') == before.get('activityAt'),
      'feedback edit sets editedAt and moves updatedAt, not activityAt')
check(edited.get('isPremium') is True, 'feedback edit without premium keeps the stored premium')
code, again = call('POST', '/feedback/edit', tb, body=edit_body, ok=200)
check(isinstance(again, dict) and again.get('editedAt') == edited.get('editedAt') and again.get('updatedAt') == edited.get('updatedAt'),
      'feedback edit without a change writes nothing')
code, unprem = call('POST', '/feedback/edit', tb, body={**edit_body, 'premium': False}, ok=200)
unprem = unprem if isinstance(unprem, dict) else {}
check(unprem.get('isPremium') is False and unprem.get('updatedAt', 0) > edited.get('updatedAt', 0)
      and unprem.get('editedAt', 0) > edited.get('editedAt', 0) and unprem.get('activityAt') == before.get('activityAt'),
      'feedback premium change alone is an edit: moves updatedAt and editedAt, not activityAt')
code, unprem2 = call('POST', '/feedback/edit', tb, body={**edit_body, 'premium': False}, ok=200)
check(isinstance(unprem2, dict) and unprem2.get('editedAt') == unprem.get('editedAt'), 'feedback edit with the stored premium writes nothing')
call('POST', '/feedback/edit', tc, body=edit_body, ok=404)
call('POST', '/feedback/edit', tb, body={**edit_body, 'title': 'ab'}, ok=400)
call('POST', '/feedback/edit', tb, body={**edit_body, 'entryId': 'nope'}, ok=400)
if seeded:
    call('POST', '/feedback/edit', ta, body={**edit_body, 'entryId': seeded[0]}, ok=403)
code, as_bug = call('POST', '/feedback/edit', ta, body={**edit_body, 'type': 'BUG', 'premium': True}, ok=200)
as_bug = as_bug if isinstance(as_bug, dict) else {}
check(as_bug.get('status') == 'OPEN' and as_bug.get('isPremium') is False and as_bug.get('notNeededCount') == 0
      and as_bug.get('upvotes') == edited.get('upvotes') and as_bug.get('downvotes') == edited.get('downvotes'),
      'feedback type change by an admin: start status, no premium, up/down votes kept')
code, as_feat = call('POST', '/feedback/edit', ta, body={**edit_body, 'premium': True}, ok=200)
check(isinstance(as_feat, dict) and as_feat.get('type') == 'FEATURE' and as_feat.get('isPremium') is True, 'feedback switch back to a feature sets premium again when sent')
as_feat = as_feat if isinstance(as_feat, dict) else {}
code, nodesc = call('POST', '/feedback/edit', ta, body={**edit_body, 'description': ''}, ok=200)
nodesc = nodesc if isinstance(nodesc, dict) else {}
check(nodesc.get('description') == '' and nodesc.get('editedAt', 0) > as_feat.get('editedAt', 0) and nodesc.get('activityAt') == as_feat.get('activityAt'),
      'feedback edit to an empty description is an edit: moves editedAt, not activityAt')
code, nodesc2 = call('POST', '/feedback/edit', ta, body={**edit_body, 'description': None}, ok=200)
check(isinstance(nodesc2, dict) and nodesc2.get('description') == '' and nodesc2.get('editedAt') == nodesc.get('editedAt'),
      'feedback edit from empty to missing description writes nothing')
code, ce = call('POST', '/feedback/editcomment', tc, body={'commentId': cm.get('id') if isinstance(cm, dict) else '0' * 24, 'text': ' Ja bitte, gern '}, ok=200)
check(isinstance(ce, dict) and ce.get('text') == 'Ja bitte, gern' and ce.get('editedAt') and ce.get('canEdit') is True, 'feedback comment edit by the author')
call('POST', '/feedback/editcomment', ta, body={'commentId': cm.get('id') if isinstance(cm, dict) else '0' * 24, 'text': 'Admin'}, ok=404)
call('POST', '/feedback/editcomment', tc, body={'commentId': '0' * 24, 'text': 'Weg'}, ok=404)
code, detail = call('GET', '/feedback/details', ta, params={'entryid': fe}, ok=200)
comments = detail.get('comments', []) if isinstance(detail, dict) else []
check(any(x.get('editedAt') and x.get('canEdit') is False for x in comments) and any(x.get('canEdit') is True for x in comments),
      'feedback details: comment editedAt, canEdit only on own comments')
call('DELETE', '/feedback/deletecomment', tc, params={'commentid': cm.get('id') if isinstance(cm, dict) else '0' * 24}, ok=200)
call('POST', '/feedback/sync', tb, body=[{'id': fe, 'timeStamp': '0'}], params={'page': 0, 'page_size': 400}, ok=200)

# ─── Announcements ────────────────────────────────────────────────────────────
texts = {lang: {'title': f'Neu ({lang})', 'body': f'Text ({lang})'} for lang in ('german', 'austrian', 'english', 'italian')}
code, an = call('POST', '/announcements/admin/create', ta, body={'id': None, **texts, 'expiresAt': NOW + 7 * DAY}, ok=200)
an_id = an.get('id') if isinstance(an, dict) else 1
call('POST', '/announcements/admin/update', ta, body={'id': an_id, **texts, 'german': {'title': 'Neu (geändert)', 'body': 'Mehr Text'}, 'expiresAt': NOW + 8 * DAY}, ok=200)
code, listed = call('GET', '/announcements/admin/list', ta, ok=200)
check(isinstance(listed, list) and any(x.get('creatorId') == a for x in listed), 'admin announcement list carries the creator id')
code, latest = call('GET', '/announcements/latest', tb, ok=200)
shown = (latest or {}).get('announcement') if isinstance(latest, dict) else None
check(shown is None or shown.get('creatorId', 'missing') is None, 'latest announcement hides the creator')

# ─── Games ────────────────────────────────────────────────────────────────────
games = enum_entries('games/model/Game.kt', 'Game')
difficulties = enum_entries('games/model/Difficulty.kt', 'Difficulty') if os.path.exists(os.path.join(SRC, 'games/model/Difficulty.kt')) else ['MEDIUM']
periods = enum_entries('games/model/LeaderboardPeriod.kt', 'LeaderboardPeriod')
for game in games:
    call('POST', '/games/upsert', tb, body={'gameId': game, 'difficulty': 'MEDIUM', 'score': 120, 'timeMillis': 61_000})
    call('POST', '/games/upsertbatch', ta, body={'gameId': game, 'difficulty': 'HIGH', 'scores': [{'userId': a, 'score': 30, 'timeMillis': 0}, {'userId': b, 'score': 25, 'timeMillis': 0}]})
    for period in periods:
        call('GET', '/games/highscores', ta, params={'gameid': game, 'difficulty': difficulties[0], 'period': period}, ok=200)
for period in periods:
    call('GET', '/games/globalranking', ta, params={'period': period}, ok=200)
call('GET', '/recap', ta)
call('GET', '/recap', ta, params={'year': 2026})

# ─── Admin panel API ──────────────────────────────────────────────────────────
call('GET', '/chefdev/api/users', ta, ok=200)
call('GET', '/chefdev/api/friends-tree', ta, ok=200)
call('GET', '/chefdev/api/connected-users', ta, ok=200)
call('GET', '/chefdev/api/connected-users/stream', ta, ok=200, stream=True)
call('GET', '/chefdev/api/logs/types', ta, ok=200)
call('GET', '/chefdev/api/logs', ta, ok=200)
call('GET', '/chefdev/api/logs', ta, params={'logType': 'USER_LOGIN', 'sort': 'USER', 'ascending': 'true', 'page': 0, 'pageSize': 10}, ok=200)
for path in ('overview', 'endpoints', 'metrics'):
    call('GET', f'/chefdev/api/monitoring/{path}', ta, params={'range': 'HOUR_1'}, ok=200)
call('GET', '/chefdev/api/monitoring/errors', ta, params={'range': 'HOUR_1', 'levels': 'WARN,ERROR'}, ok=200)
code, logs = call('GET', '/chefdev/api/monitoring/logs', ta, params={'range': 'HOUR_1', 'levels': 'INFO,WARN', 'page': 0, 'pageSize': 20}, ok=200)
call('GET', '/chefdev/api/monitoring/logs', ta, params={'range': 'HOURS_24', 'kind': 'REQUEST', 'levels': '', 'tag': 'auth', 'user': a, 'q': 'login'})
request_ids = [x['requestId'] for x in find_all(logs, lambda x: x.get('requestId'))]
call('GET', '/chefdev/api/monitoring/trace/{requestId}', ta, vars={'requestId': request_ids[0] if request_ids else 'none'}, ok=200)
call('GET', '/chefdev/api/scores/games', ta, ok=200)
code, scores = call('GET', '/chefdev/api/scores', ta, params={'game': games[0], 'difficulty': 'MEDIUM', 'userId': b, 'sort': 'DATE', 'ascending': 'false'}, ok=200)
code, scores = call('GET', '/chefdev/api/scores', ta, ok=200)
score_ids = [x['id'] for x in find_all(scores, lambda x: 'id' in x and 'score' in x)]
call('PUT', '/chefdev/api/scores/{id}', ta, vars={'id': score_ids[0] if score_ids else '0' * 24}, body={'score': 99, 'timeMillis': 1000}, ok=200)
call('DELETE', '/chefdev/api/scores/{id}', ta, vars={'id': score_ids[-1] if score_ids else '0' * 24}, ok=200)
code, don = call('POST', '/chefdev/api/donations', ta, body={'name': 'Anna', 'amountCents': 1500, 'donatedAt': NOW - DAY, 'message': 'Weiter so!'}, ok=200)
don = don.get('id') if isinstance(don, dict) else '0' * 24
call('PUT', '/chefdev/api/donations/{id}', ta, vars={'id': don}, body={'name': 'Anna M.', 'amountCents': 2000, 'donatedAt': NOW - DAY, 'message': None}, ok=200)
call('GET', '/chefdev/api/donations', ta, ok=200)
faq_text = {'question': 'Testfrage?', 'answer': 'Testantwort.'}
call('GET', '/chefdev/api/faq/categories', ta, ok=200)
code, faq = call('POST', '/chefdev/api/faq', ta, body={'category': 'GENERAL', 'sortOrder': 9, 'german': faq_text, 'austrian': faq_text, 'english': faq_text}, ok=200)
faq = faq.get('id') if isinstance(faq, dict) else '0' * 24
call('PUT', '/chefdev/api/faq/{id}', ta, vars={'id': faq}, body={'category': 'MAP', 'sortOrder': 3, 'german': faq_text, 'austrian': None, 'english': None}, ok=200)
call('GET', '/chefdev/api/faq', ta, ok=200)
call('GET', '/chefdev/api/map/changelog/editors', ta, ok=200)
call('GET', '/chefdev/api/map/changelog', ta, params={'editedBy': c, 'page': 0, 'pageSize': 20}, ok=200)
code, changelog = call('GET', '/chefdev/api/map/changelog', ta, ok=200)
versions = [x['id'] for x in (changelog.get('entries', []) if isinstance(changelog, dict) else [])]
call('POST', '/chefdev/api/map/changelog/{versionId}/revert', ta, vars={'versionId': versions[0] if versions else '0' * 24}, ok=200)
call('POST', '/chefdev/api/map/revert-user', ta, body={'userId': c, 'fromMillis': NOW - HOUR, 'toMillis': int(time.time() * 1000)}, ok=200)
call('POST', '/chefdev/api/map/restore-to-timestamp', ta, body={'timestampMillis': int(time.time() * 1000) - 5_000}, ok=200)
call('POST', '/chefdev/api/server/update', ta, ok=200)
call('DELETE', '/chefdev/api/server/update', ta, ok=200)

# ─── Sessions and account actions ─────────────────────────────────────────────
code, sessions = call('GET', '/sessions', ta, ok=200)
other = [x['id'] for x in (sessions if isinstance(sessions, list) else []) if x.get('id') != jwt_claims(ta).get('sid')]
call('POST', '/auth/logout', A2['accessToken'], body={'refreshToken': A2['refreshToken'], 'notificationToken': 'cd' * 32, 'isAndroid': False, 'allDevices': False}, ok=200)
A3 = login('testaccount', 'Old tablet - androidId: a3', 'ANDROID')
other = [x['id'] for x in (call('GET', '/sessions', ta, ok=200)[1] or []) if isinstance(x, dict) and x.get('id') == jwt_claims(A3['accessToken']).get('sid')]
call('DELETE', '/sessions/{sessionId}', ta, vars={'sessionId': other[0] if other else '0' * 24}, ok=200)
call('POST', '/chefdev/api/users/{id}/logout', ta, vars={'id': e}, ok=200)
call('POST', '/users/changeusername', tc, text='caroline', ok=200)
call('POST', '/users/changepassword', td, body={'oldPassword': PASSWORD, 'newPassword': 'NewPass1234!'}, ok=200)
call('POST', '/auth/send_reset_email', params={'email': 'carol@test.local'}, ok=200)
call('POST', '/auth/send_delete_email', params={'email': 'carol@test.local'}, ok=200)
call('GET', '/auth/reset_password', params={'token': 'not-a-token'})
call('POST', '/auth/confirm_reset_password', params={'token': 'not-a-token', 'newPassword': 'NewPass1234!'})
call('GET', '/auth/verify_email', params={'token': 'not-a-token'})
call('GET', '/auth/delete_account', params={'token': 'not-a-token'})
call('POST', '/auth/confirm_delete_account', params={'confirmToken': 'not-a-token'})

# ─── Clean up through the API (the delete routes) ─────────────────────────────
call('DELETE', '/messages/delete', tb, params={'messageid': m2}, ok=200)
call('DELETE', '/groupextensions/delete', ta, params={'extensionid': ext['QUOTES']}, ok=200)
call('DELETE', '/map/delete', tb, params={'entryid': map_ids[-1]}, ok=200)
call('DELETE', '/feedback/delete', tc, params={'entryid': fb}, ok=200)
call('DELETE', '/announcements/admin/delete', ta, params={'id': an_id}, ok=200)
call('DELETE', '/chefdev/api/donations/{id}', ta, vars={'id': don}, ok=200)
call('DELETE', '/chefdev/api/faq/{id}', ta, vars={'id': faq}, ok=200)
call('DELETE', '/events/delete', ta, params={'eventid': ev_b, 'deletegroup': 'false', 'deleteevent': 'true'}, ok=200)
call('DELETE', '/groups/delete', ta, params={'groupid': group2}, ok=200)
call('POST', '/users/removefriend/{id}', ta, vars={'id': d}, ok=200)

# ─── Checks ───────────────────────────────────────────────────────────────────
print('\n== Event reminder')
for _ in range(150):
    sent = mongo(f"print(db.events.findOne({{_id: ObjectId('{ev_c}')}}).sentReminders.length)") if ev_c else '0'
    if sent.strip() not in ('', '0'):
        break
    time.sleep(1)
if sent.strip() in ('', '0'):
    failures.append('event reminder was not sent by the scheduler within 150 s')
    print('FAIL event reminder was not sent by the scheduler within 150 s')
else:
    print(f'ok   event reminder sent ({sent.strip()} entry)')
time.sleep(1.5)
print('\n== Name fields replaced by user ids')


def name_keys(obj):
    if isinstance(obj, dict):
        return {k for k in obj if k in NAME_KEYS} | {x for v in obj.values() for x in name_keys(v)}
    if isinstance(obj, list):
        return {x for v in obj for x in name_keys(v)}
    return set()


leaks = set()
for route, text in bodies:
    if route.split()[1].startswith('/chefdev'):  # the admin panel is not in-app data
        continue
    try:
        leaks |= {(route, k) for k in name_keys(json.loads(text))}
    except Exception:
        pass
leaks |= {('socket ' + str(m.get('_class')), k) for m in socket_frames for k in name_keys(m)}
for route, key in sorted(leaks):
    failures.append(f'name field {key} in {route}')
    print(f'FAIL name field {key} in {route}')
print(f'{len(bodies)} bodies and {len(socket_frames)} socket frames scanned, {len(leaks)} name fields')
print('\n== Coverage')
missing = sorted(source_routes() - called)
for m, r in missing:
    failures.append(f'route never called: {m} {r}')
    print(f'FAIL route never called: {m} {r}')
print(f'{len(called)} routes called, {len(missing)} missing')

print('\n== Server log')
log = open(LOG, errors='ignore').read().splitlines()
errors = []
for i, line in enumerate(log):
    if re.search(r'\bERROR\b', line):
        tag = re.search(r'\[([a-z0-9_]+)\]', line)
        if tag and tag.group(1) in ALLOWED_ERROR_TAGS:
            continue
        errors.append(re.sub(r'\x1b\[[0-9;]*m', '', line)[:240])
for line in errors:
    failures.append('server ERROR: ' + line)
    print('FAIL ' + line)
print(f'{len(errors)} unexpected ERROR lines')

print('\n== Fields no document fills (information)')
print(mongo("""
const skip = new Set(['admin','config','local']);
for (const name of db.getCollectionNames().sort()) {
  const docs = db.getCollection(name).find().limit(500).toArray();
  if (!docs.length) { print(name + ': (empty)'); continue; }
  const seen = {}, filled = {};
  const walk = (o, p) => {
    for (const [k, v] of Object.entries(o)) {
      const key = p ? p + '.' + k : k;
      if (key === '_id' || key === '_class') continue;
      seen[key] = true;
      const empty = v === null || v === undefined || (Array.isArray(v) && v.length === 0) || (typeof v === 'object' && !Array.isArray(v) && !(v instanceof Date) && !(v instanceof ObjectId) && Object.keys(v).length === 0);
      if (!empty) filled[key] = true;
      if (v && typeof v === 'object' && !Array.isArray(v) && !(v instanceof ObjectId) && !(v instanceof Date) && p.split('.').length < 2) walk(v, key);
    }
  };
  docs.forEach(d => walk(d, ''));
  const never = Object.keys(seen).filter(k => !filled[k]);
  print(name + ' (' + docs.length + '): ' + (never.length ? never.join(', ') : 'all filled'));
}
"""))

print(f'\nRESULT {"FAIL" if failures else "PASS"}: {len(failures)} problems')
sys.exit(1 if failures else 0)
