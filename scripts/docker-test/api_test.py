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

LOG, MONGO_CONTAINER, PORT, PASSWORD, ROOT = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
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
    try:
        return code, json.loads(text)
    except Exception:
        return code, text


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
        threading.Thread(target=s.drain, daemon=True).start()

    def drain(s):
        try:
            while s.s.recv(65536):
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
for name in ['bob', 'carol', 'dave']:
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

code, refreshed = call('POST', '/auth/refresh', body={'refreshToken': A2['refreshToken'], 'deviceName': 'iPhone 16 - vendorId: a2', 'deviceType': 'IOS'}, ok=200)
A2 = refreshed if isinstance(refreshed, dict) else A2

# Friendships: alice-bob, alice-carol, bob-carol, alice-dave accepted; erin -> alice pending; carol -> dave denied
for x, y, tx, ty in [(a, b, ta, tb), (a, c, ta, tc), (b, c, tb, tc), (a, d, ta, td)]:
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
    'lastContributePopupShown': NOW, 'lastDismissedAnnouncementId': 1,
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
call('GET', '/users/profilepic/{id}', ta, vars={'id': e})
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
           'maxUsers': 10, 'groupDeleteDelay': 'ONE_DAY', 'createGroup': True, 'reminderMinutes': [60, 1440]}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_a), ('profilepic', PNG, 'event.png', 'image/png')]))))
ev_a, group = ev.get('id'), ev.get('groupId')
event_b = {'eventId': None, 'type': 'FOOD', 'title': 'Pizza', 'description': 'Offen für alle', 'location': None, 'startDate': NOW + 3 * DAY,
           'closeDate': None, 'invitedUsers': [], 'visibility': 'PUBLIC', 'maxUsers': None, 'groupDeleteDelay': 'NEVER', 'createGroup': True, 'reminderMinutes': []}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_b)]))))
ev_b = ev.get('id')
# Its 5 minute reminder is due in about 20 s; the scheduler (every 60 s) sends it, checked at the end
event_c = {'eventId': None, 'type': 'BEER', 'title': 'Feierabendbier', 'description': 'Gleich', 'location': {'lat': 47.45, 'long': 9.75},
           'startDate': int(time.time() * 1000) + 5 * MIN + 20_000, 'closeDate': None, 'invitedUsers': [b], 'visibility': 'FRIENDS_ONLY',
           'maxUsers': None, 'groupDeleteDelay': 'ONE_HOUR', 'createGroup': False, 'reminderMinutes': [5]}
code, ev = call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', event_c)]))))
ev_c = ev.get('id')
call('POST', '/events/participation', tb, body={'eventId': ev_c, 'status': 'ACCEPTED'}, ok=200)
# Move start and location: change alerts and system messages
call('POST', '/events/upsert', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([json_part('request', {**event_a, 'eventId': ev_a, 'startDate': NOW + 2 * DAY + HOUR,
                                                                                                  'location': {'lat': 47.6, 'long': 9.8}, 'title': 'Kicken am Strand'})]))))
for tok, status in [(tb, 'ACCEPTED'), (tc, 'DISMISSED'), (td, 'SEEN')]:
    call('POST', '/events/participation', tok, body={'eventId': ev_a, 'status': status}, ok=200)
call('POST', '/events/join', td, body={'eventId': ev_b}, ok=200)
call('POST', '/events/sync', ta, body=[], params={'page': 0, 'page_size': 400}, ok=200)

# ─── Groups ───────────────────────────────────────────────────────────────────
mp = multipart([('name', 'Testgruppe', None, None), ('memberlist', f'{b},{c}', None, None), ('description', 'Alles zum Testen', None, None),
                ('profilepic', PNG, 'group.png', 'image/png')])
code, g2 = call('POST', '/groups/create', ta, data=mp[0], ctype=mp[1], ok=200)
group2 = g2.get('id') if isinstance(g2, dict) else None
# Only the event's creator and people who join are in its group chat, so messages, extensions and
# member changes go to the created group (alice, bob, carol)
group = group2 or group
group2 = group
call('POST', '/groups/sync', ta, body=[], ok=200)
call('POST', '/groups/setprofilepic', ta, ok=200, **dict(zip(('data', 'ctype'), multipart([('groupid', group, None, None), ('profilepic', PNG, 'g.png', 'image/png')]))))
call('GET', '/groups/profilepic/{id}', ta, vars={'id': group}, ok=200)
call('POST', '/groups/setdescription', ta, params={'groupid': group}, text='Neue Beschreibung', ok=200)
call('POST', '/groups/setGroupName', ta, params={'groupid': group}, text='Kicker', ok=200)
call('POST', '/groups/setexpiry', ta, body={'groupId': group2, 'expiresAt': NOW + 30 * DAY}, ok=200)
for action, member in [('ADD_USER', d), ('MAKE_ADMIN', b), ('REMOVE_ADMIN', b), ('MAKE_ADMIN', b), ('REMOVE_USER', d)]:
    call('POST', '/groups/changemembers', ta, body={'action': action, 'groupMemberId': member, 'groupId': group2}, ok=200)

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
                            'sortIndex': 0, 'kind': 'STOP', 'plannedAt': NOW + DAY, 'mapEntryId': map_ids[1] if len(map_ids) > 1 else None}),
    (ext['ROUTE_PLANNER'], {'_class': 'route_point', 'coordinates': {'lat': 47.6, 'long': 9.9}, 'name': 'Hütte', 'description': 'Übernachtung',
                            'sortIndex': 1, 'kind': 'ACCOMMODATION', 'plannedAt': NOW + DAY + 5 * HOUR, 'mapEntryId': None}),
]
item_ids = []
for ext_id, data in items:
    code, x = call('POST', '/groupextensions/items/upsert', tb, body={'extensionId': ext_id, 'itemId': None, 'data': data}, ok=200)
    item_ids += [(ext_id, i['id']) for i in (x.get('items', []) if isinstance(x, dict) else [])]
call('POST', '/groupextensions/items/upsert', tc, body={'extensionId': ext['SHARED_TEXT'], 'itemId': item_ids[0][1] if item_ids else None,
                                                       'data': {'_class': 'shared_text', 'text': 'Einkaufsliste: Brot, Käse, Bier', 'writers': []}}, ok=200)
quote_items = [i for x, i in item_ids if x == ext['QUOTES']]
call('DELETE', '/groupextensions/items/delete', ta, params={'extensionid': ext['QUOTES'], 'itemid': quote_items[-1] if quote_items else '0' * 24}, ok=200)
call('POST', '/groupextensions/sync', ta, body=[], params={'page': 0, 'page_size': 100}, ok=200)

# ─── Feedback ─────────────────────────────────────────────────────────────────
code, fs = call('POST', '/feedback/sync', ta, body=[], params={'page': 0, 'page_size': 400}, ok=200)
seeded = [x['id'] for x in (fs.get('updatedEntries', []) if isinstance(fs, dict) else []) if x.get('isSeeded')]
code, fe = call('POST', '/feedback/create', tb, body={'type': 'FEATURE', 'title': 'Dunkler Kartenmodus', 'description': 'Die Karte auch dunkel',
                                                      'tags': ['MAP', 'UI'], 'location': 'Karte', 'premium': True, 'appVersion': '3.0.19', 'platform': 'iOS'}, ok=200)
code, fb = call('POST', '/feedback/create', tc, body={'type': 'BUG', 'title': 'Chat lädt nicht', 'description': 'Nach dem Start leer',
                                                      'tags': ['CHAT', 'PERFORMANCE'], 'location': None, 'premium': False, 'appVersion': '3.0.18', 'platform': 'Desktop'}, ok=200)
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
call('GET', '/feedback/details', tb, params={'entryid': fe}, ok=200)
call('GET', '/feedback/list', tb, params={'type': 'FEATURE'}, ok=200)
call('DELETE', '/feedback/deletecomment', tc, params={'commentid': cm.get('id') if isinstance(cm, dict) else '0' * 24}, ok=200)
call('POST', '/feedback/sync', tb, body=[{'id': fe, 'timeStamp': '0'}], params={'page': 0, 'page_size': 400}, ok=200)

# ─── Announcements ────────────────────────────────────────────────────────────
texts = {lang: {'title': f'Neu ({lang})', 'body': f'Text ({lang})'} for lang in ('german', 'austrian', 'english', 'italian')}
code, an = call('POST', '/announcements/admin/create', ta, body={'id': None, **texts, 'expiresAt': NOW + 7 * DAY}, ok=200)
an_id = an.get('id') if isinstance(an, dict) else 1
call('POST', '/announcements/admin/update', ta, body={'id': an_id, **texts, 'german': {'title': 'Neu (geändert)', 'body': 'Mehr Text'}, 'expiresAt': NOW + 8 * DAY}, ok=200)
call('GET', '/announcements/admin/list', ta, ok=200)
call('GET', '/announcements/latest', tb, ok=200)

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
