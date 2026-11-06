"""
End-to-end test of notification delivery against a running server, with real sockets and a real
database. Started by run.sh, standard library only.

Firebase and APNs are not configured here, so a push can not go out. A push attempt fails with the
log tag send_fcm_unexpected, and counting that tag shows whether the server tried to push.
"""
import base64, json, os, re, socket, struct, subprocess, sys, threading, time, urllib.error, urllib.request, uuid

LOG, MONGO_CONTAINER, PORT, PASSWORD = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
BASE = f'http://127.0.0.1:{PORT}'

def http(m,p,tok=None,body=None):
    r=urllib.request.Request(BASE+p,method=m,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+tok} if tok else {})})
    try:
        with urllib.request.urlopen(r,timeout=15) as x: return x.status,x.read().decode()
    except urllib.error.HTTPError as e: return e.code,e.read().decode()
def mongo(js):
    o=subprocess.run(['docker','exec',MONGO_CONTAINER,'mongosh','--quiet','db','--eval',js],capture_output=True,text=True)
    return o.stdout.strip()+o.stderr.strip()
def fcm_attempts():
    return len(re.findall(r'send_fcm_unexpected',open(LOG,errors='ignore').read()))
class WS:
    def __init__(s,tok):
        s.msgs=[];s.closed=False;s.s=socket.create_connection(('127.0.0.1',PORT),timeout=5)
        k=base64.b64encode(os.urandom(16)).decode()
        s.s.sendall(f'GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {k}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {tok}\r\n\r\n'.encode())
        b=b''
        while b'\r\n\r\n' not in b: b+=s.s.recv(1)
        s.status=b.split(b'\r\n')[0].decode()
        s.s.settimeout(None); threading.Thread(target=s.rx,daemon=True).start()
    def rx(s):
        try:
            while True:
                h=s.rd(2); op=h[0]&15; n=h[1]&127
                if n==126: n=struct.unpack('>H',s.rd(2))[0]
                elif n==127: n=struct.unpack('>Q',s.rd(8))[0]
                d=s.rd(n)
                if op==1: s.msgs.append(json.loads(d))
                elif op==9: s.frame(10,d)
                elif op==8: s.closeCode=struct.unpack('>H',d[:2])[0] if len(d)>=2 else None; s.closed=True; return
        except Exception: s.closed=True
    def rd(s,n):
        b=b''
        while len(b)<n:
            x=s.s.recv(n-len(b))
            if not x: raise IOError
            b+=x
        return b
    def frame(s,op,d):
        m=os.urandom(4); h=bytes([0x80|op])+(bytes([0x80|len(d)]) if len(d)<126 else bytes([0x80|126])+struct.pack('>H',len(d)))+m
        s.s.sendall(h+bytes(c^m[i%4] for i,c in enumerate(d)))
    def send(s,o): s.frame(1,json.dumps(o).encode())
    def close(s):
        try: s.frame(8,struct.pack('>H',1000))
        except Exception: pass
        time.sleep(.4); s.s.close()
    def of(s,cls): return [m for m in s.msgs if m.get('_class')==cls]
res=[]
def check(name,ok,info=''):
    res.append(ok); print(('PASS ' if ok else 'FAIL ')+name+(' | '+str(info) if info else ''))
for i in range(180):
    try:
        if http('GET','/public/test')[0]==200: break
    except Exception: pass
    time.sleep(1)
else: sys.exit('server not up')
print('server up')
pw=PASSWORD
mongo("var u=db.users.findOne({username:'testaccount'}); u._id=new ObjectId(); u.username='pushbob'; u.email='bob@test.local'; db.users.insertOne(u); print('ok')")
def login(user,dev,typ):
    c,b=http('POST','/auth/login',None,{'username':user,'password':pw,'deviceName':dev,'deviceType':typ})
    assert c==200,(c,b); return json.loads(b)
def sid(tok):
    p=tok.split('.')[1]; return json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4)))
ph=login('testaccount','Pixel - androidId: a1','ANDROID'); de=login('testaccount','Mac - macAddress: m1','DESKTOP'); bo=login('pushbob','Bob - androidId: b1','ANDROID')
pc=sid(ph['accessToken']); dc=sid(de['accessToken']); alice=pc['sub']; bob=sid(bo['accessToken'])['sub']
rows=mongo("print(JSON.stringify(db.refreshTokens.find({},{deviceName:1}).toArray().map(r=>r._id.toString())))")
check('A access token carries sid of its session row', pc.get('sid') in rows and dc.get('sid') in rows and pc['sid']!=dc['sid'], pc.get('sid'))
c,b=http('POST','/users/setnotificationtoken',ph['accessToken'],{'token':'fcm-phone-1','isAndroid':True})
row=mongo(f"var r=db.refreshTokens.findOne({{_id:ObjectId('{pc['sid']}')}}); print(r.pushToken+'|'+r.pushProvider+'|'+db.firebasetokens.countDocuments({{token:'fcm-phone-1'}}))")
check('B token sits on the device row and in legacy',c==200 and row=='fcm-phone-1|FCM|1',(c,row))
wd=WS(de['accessToken']); time.sleep(.6); n0=len(wd.msgs)
wp=WS(ph['accessToken']); time.sleep(.8)
check('C sockets connect',' 101 ' in wd.status and ' 101 ' in wp.status,(wd.status,wp.status))
check('D connect snapshot only reaches new session',len(wp.of('friendonlinestatussnapshot'))==1 and len(wd.of('friendonlinestatussnapshot'))==1 and len(wd.msgs)==n0,(n0,len(wd.msgs)))
a0=fcm_attempts()
c,b=http('POST','/users/addfriend/'+alice,bo['accessToken']); time.sleep(1.2)
check('E friend request reaches both sockets, no push',c==200 and wp.of('friendrequest') and wd.of('friendrequest') and fcm_attempts()==a0,(c,b[:80],fcm_attempts()-a0))
c,b=http('POST','/users/addfriend/'+bob,ph['accessToken']); time.sleep(.5)
def send(text):
    c,b=http('POST','/messages/send/text',bo['accessToken'],{'messageId':None,'receiverId':alice,'groupMessage':False,'msgType':'TEXT','content':text,'answerId':None,'clientMessageId':str(uuid.uuid4())})
    assert c==200,(c,b); return json.loads(b)['messageId']
a0=fcm_attempts(); m=send('one'); time.sleep(.3)
wp.send({'_class':'messageack','messageId':m}); time.sleep(3.6)
check('F phone acks in time, no push',fcm_attempts()==a0,fcm_attempts()-a0)
a0=fcm_attempts(); m=send('two'); time.sleep(1.2); early=fcm_attempts()-a0; time.sleep(2.8)
check('G phone does not ack, push after 3 s',early==0 and fcm_attempts()==a0+1,(early,fcm_attempts()-a0))
wp.close(); time.sleep(.6)
a0=fcm_attempts(); m=send('three'); wd.send({'_class':'messageack','messageId':m}); time.sleep(1.2)
check('H desktop open and acking, phone without socket gets push at once',fcm_attempts()==a0+1,fcm_attempts()-a0)
check('I desktop got the live message',len(wd.of('messagechange'))>=3,len(wd.of('messagechange')))
a0=fcm_attempts(); wp=WS(ph['accessToken']); time.sleep(.5); m=send('four'); time.sleep(.4); wp.close(); time.sleep(.8)
check('J socket closing before ack releases push at once',fcm_attempts()==a0+1,fcm_attempts()-a0)
wp=WS(ph['accessToken']); time.sleep(.5)
c,b=http('DELETE','/sessions/'+pc['sid'],de['accessToken']); time.sleep(.8)
st=mongo(f"print(db.refreshTokens.countDocuments({{_id:ObjectId('{pc['sid']}')}})+'|'+db.firebasetokens.countDocuments({{token:'fcm-phone-1'}}))")
check('K ending a device removes row and token, closes its socket',c==200 and st=='0|0' and wp.closed,(c,st,wp.closed))
check('L desktop socket survives',not wd.closed)
wx=WS(ph['accessToken']); time.sleep(.8)
check('M ended device can not reconnect',wx.closed,(wx.status,getattr(wx,'closeCode',None)))
c,b=http('POST','/users/setnotificationtoken',ph['accessToken'],{'token':'fcm-phone-1','isAndroid':True})
check('N ended device can not register a token',c==404,(c,b[:60]))
a0=fcm_attempts(); send('five'); time.sleep(3.6)
check('O ended device gets no push',fcm_attempts()==a0,fcm_attempts()-a0)
ph2=login('testaccount','Pixel - androidId: a1','ANDROID'); http('POST','/users/setnotificationtoken',ph2['accessToken'],{'token':'fcm-shared','isAndroid':True})
http('POST','/users/setnotificationtoken',bo['accessToken'],{'token':'fcm-shared','isAndroid':True})
st=mongo("print(db.refreshTokens.countDocuments({pushToken:'fcm-shared'})+'|'+db.firebasetokens.countDocuments({token:'fcm-shared'}))")
check('P account switch moves token, one owner only',st=='1|1',st)
r=json.loads(http('POST','/auth/refresh',None,{'refreshToken':bo['refreshToken'],'deviceName':'Bob - androidId: b1','deviceType':'ANDROID'})[1])
st=mongo("print(db.refreshTokens.countDocuments({pushToken:'fcm-shared'}))")
check('Q refresh keeps sid and push token',sid(r['accessToken']).get('sid')==sid(bo['accessToken']).get('sid') and st=='1',st)
wd.close()
print('RESULT',sum(res),'/',len(res))
sys.exit(0 if all(res) else 1)
