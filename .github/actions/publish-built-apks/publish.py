#!/usr/bin/env python3
from __future__ import annotations
import hashlib, json, os, re, shutil, subprocess, sys, tempfile, time, urllib.parse, zipfile
from pathlib import Path
from typing import Any

REPO=os.environ.get('GITHUB_REPOSITORY','').strip(); SHA=os.environ.get('SOURCE_SHA',os.environ.get('GITHUB_SHA','')).strip(); TOKEN=os.environ.get('GH_TOKEN','').strip(); CURRENT_RUN_ID=str(os.environ.get('GITHUB_RUN_ID','')).strip(); WAIT_SECONDS=max(0,int(os.environ.get('WAIT_SECONDS','900') or '900')); POLL_SECONDS=25
if not REPO or not SHA or not TOKEN: print('Missing GitHub context/token.',file=sys.stderr); sys.exit(2)
ENV=dict(os.environ); ENV['GH_TOKEN']=TOKEN

def run(cmd:list[str],check:bool=True,capture:bool=True)->subprocess.CompletedProcess[str]:
    print('+',' '.join(cmd)); return subprocess.run(cmd,env=ENV,check=check,text=True,capture_output=capture)
def gh_json(endpoint:str)->Any: return json.loads(run(['gh','api',endpoint]).stdout)
def tree_paths()->set[str]:
    try:return {str(x.get('path','')) for x in gh_json(f'repos/{REPO}/git/trees/{SHA}?recursive=1').get('tree',[])}
    except Exception:return set()
def is_android(paths:set[str])->bool:
    return not paths or any(p.endswith(('AndroidManifest.xml','build.gradle','build.gradle.kts','gradlew')) for p in paths)
def successful_runs()->list[dict[str,Any]]:
    data=gh_json(f'repos/{REPO}/actions/runs?head_sha={urllib.parse.quote(SHA)}&status=completed&per_page=100'); out=[]
    for item in data.get('workflow_runs',[]):
        if str(item.get('id'))==CURRENT_RUN_ID or item.get('head_sha')!=SHA or item.get('conclusion')!='success': continue
        out.append(item)
    return sorted(out,key=lambda x:x.get('updated_at',''),reverse=True)
def artifacts(run_id:int)->list[dict[str,Any]]:
    return [a for a in gh_json(f'repos/{REPO}/actions/runs/{run_id}/artifacts?per_page=100').get('artifacts',[]) if not a.get('expired')]
def wait_runs()->list[dict[str,Any]]:
    deadline=time.time()+WAIT_SECONDS; fallback=[]
    while True:
        candidates=[]; likely=[]
        for wr in successful_runs():
            arts=artifacts(int(wr['id']))
            if not arts: continue
            wr=dict(wr); wr['_artifacts']=arts; candidates.append(wr)
            if any(any(k in str(a.get('name','')).lower() for k in ('apk','android')) for a in arts): likely.append(wr)
        if likely:return likely
        if candidates:fallback=candidates
        if time.time()>=deadline:return fallback
        remaining=int(deadline-time.time()); print(f'No APK artifact yet for {SHA[:7]}; waiting ({remaining}s left)...'); time.sleep(min(POLL_SECONDS,max(1,remaining)))
def download_runs(runs:list[dict[str,Any]],dest:Path)->None:
    for wr in runs:
        target=dest/f"run-{wr['id']}"; target.mkdir(parents=True,exist_ok=True); cp=run(['gh','run','download',str(wr['id']),'--repo',REPO,'--dir',str(target)],check=False)
        if cp.returncode!=0: print(cp.stderr)
def version_key(path:Path)->tuple[int,...]:
    nums=re.findall(r'\d+',path.parent.name); return tuple(int(x) for x in nums) if nums else (0,)
def tool(name:str)->str|None:
    home=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if home:
        c=list((Path(home)/'build-tools').glob(f'*/{name}'))
        if c:c.sort(key=version_key); return str(c[-1])
    return shutil.which(name)
AAPT=tool('aapt'); APKSIGNER=tool('apksigner')
def signed(apk:Path)->tuple[bool,str]:
    if not APKSIGNER:return True,''
    cp=run([APKSIGNER,'verify','--print-certs',str(apk)],check=False)
    if cp.returncode!=0:return False,''
    m=re.search(r'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)',cp.stdout); return True,(m.group(1).lower() if m else '')
def parse(apk:Path)->dict[str,Any]:
    if not AAPT: raise RuntimeError('aapt not found')
    cp=run([AAPT,'dump','badging',str(apk)],check=False)
    if cp.returncode!=0: raise RuntimeError(cp.stderr.strip() or 'aapt failed')
    t=cp.stdout; m=re.search(r"^package:\s+name='([^']+)'.*?versionCode='([^']+)'.*?versionName='([^']*)'",t,re.M)
    if not m: raise RuntimeError('package metadata missing')
    label=re.search(r"^application-label:'([^']*)'",t,re.M) or re.search(r"^application:\s+label='([^']*)'",t,re.M); sdk=re.search(r"^sdkVersion:'(\d+)'",t,re.M); icon=re.search(r"^application:\s+.*?icon='([^']+)'",t,re.M); ok,cert=signed(apk)
    return {'apk':apk,'package_name':m.group(1),'version_code':int(m.group(2)),'version_name':m.group(3) or m.group(2),'name':(label.group(1).strip() if label else '') or m.group(1).split('.')[-1],'min_sdk':int(sdk.group(1)) if sdk else 21,'icon_path':icon.group(1) if icon else '','signed':ok,'signing_sha256':cert,'size':apk.stat().st_size}
def score(i:dict[str,Any])->tuple[int,int,int,int]:
    n=i['apk'].name.lower(); return (1 if i['signed'] else 0,2 if 'universal' in n else (1 if 'release' in n else 0),1 if 'debug' not in n else 0,int(i['size']))
def slug(v:str)->str:return (re.sub(r'[^a-z0-9._-]+','-',v.lower().strip()).strip('-')[:80] or 'app')
def safe(v:str)->str:return (re.sub(r'[^A-Za-z0-9._-]+','-',v).strip('-')[:120] or 'app')
def digest(p:Path)->str:
    h=hashlib.sha256(); f=p.open('rb')
    with f:
        while True:
            b=f.read(1024*1024)
            if not b:break
            h.update(b)
    return h.hexdigest()
def icon_extract(apk:Path,ip:str,dst:Path)->Path|None:
    if Path(ip).suffix.lower() not in {'.png','.webp','.jpg','.jpeg'}:return None
    try:
        with zipfile.ZipFile(apk) as z: dst.write_bytes(z.read(ip))
        return dst
    except Exception:return None
def category(repo_name:str,name:str)->str:
    v=f'{repo_name} {name}'.lower()
    if any(x in v for x in ('game','runner','bike','spider')):return 'Games'
    if any(x in v for x in ('music','audio','sonify','player')):return 'Music & Audio'
    if any(x in v for x in ('photo','video','camera','media','downloader')):return 'Photo & Video'
    return 'Tools' if any(x in v for x in ('vpn','network','wifi','ai','agent','assistant','code','apk','studio','builder')) else 'Apps'
def release_exists(tag:str)->bool:return run(['gh','release','view',tag,'--repo',REPO],check=False).returncode==0
def main()->int:
    if not is_android(tree_paths()): print('No Android project markers found.'); return 0
    runs=wait_runs()
    if not runs: print('No successful artifacts found.'); return 0
    root=Path(tempfile.mkdtemp(prefix='apk-store-')); downloads=root/'downloads'; prepared=root/'prepared'; downloads.mkdir(); prepared.mkdir()
    try:
        download_runs(runs,downloads); apks=sorted({p.resolve() for p in downloads.rglob('*.apk') if p.is_file()})
        parsed=[]
        for a in apks:
            try:
                i=parse(a)
                if i['signed']: parsed.append(i)
            except Exception as e: print(f'Skipping {a}: {e}')
        best={}
        for i in parsed:
            p=i['package_name']; best[p]=i if p not in best or score(i)>score(best[p]) else best[p]
        if not best: print('No signed APKs found.'); return 0
        meta=gh_json(f'repos/{REPO}'); repo_name=str(meta.get('name') or REPO.split('/')[-1]); desc=str(meta.get('description') or '').strip()
        try: change=str(gh_json(f'repos/{REPO}/commits/{SHA}').get('commit',{}).get('message','')).strip()[:1500]
        except Exception: change=''
        count=0
        for package,i in sorted(best.items()):
            name=i['name'] or repo_name; vc=i['version_code']; vn=i['version_name']; s=slug(package); tag=safe(f'store-{s}-v{vc}-{SHA[:7]}'); apk_name=safe(f'{s}-{vn}.apk'); apk=prepared/apk_name; shutil.copy2(i['apk'],apk)
            ic=None; ip=i.get('icon_path',''); suf=Path(ip).suffix.lower()
            if suf in {'.png','.webp','.jpg','.jpeg'}: ic=icon_extract(i['apk'],ip,prepared/safe(f'{s}-icon{suf}'))
            qtag=urllib.parse.quote(tag,safe=''); dl=f'https://github.com/{REPO}/releases/download/{qtag}/{urllib.parse.quote(apk.name,safe="")}' ; iu=f'https://github.com/{REPO}/releases/download/{qtag}/{urllib.parse.quote(ic.name,safe="")}' if ic else ''
            manifest={'slug':s,'package_name':package,'name':name,'short_description':desc or f'{name} from {repo_name}.','description':desc or f'Automatically published from {REPO}.','category':category(repo_name,name),'icon_url':iu,'featured':False,'is_paid':False,'price_pkr':0,'owned':True,'purchase_status':'free','version_code':vc,'version_name':vn,'min_sdk':i['min_sdk'],'download_url':dl,'file_size':apk.stat().st_size,'sha256':digest(apk),'changelog':change,'screenshots':[],'source_repo':REPO,'commit_sha':SHA,'signing_sha256':i.get('signing_sha256','')}
            mf=prepared/safe(f'store-manifest-{s}.json'); mf.write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
            if not release_exists(tag):
                cp=run(['gh','release','create',tag,'--repo',REPO,'--target',SHA,'--title',f'{name} {vn}','--notes',f'Automated APK App Store release.\n\nSource commit: {SHA}\nPackage: {package}\nVersion code: {vc}\n'],check=False)
                if cp.returncode!=0 and not release_exists(tag): raise RuntimeError(cp.stderr)
            files=[apk,mf]+([ic] if ic else []); cp=run(['gh','release','upload',tag,'--repo',REPO,'--clobber']+[str(x) for x in files],check=False)
            if cp.returncode!=0: raise RuntimeError(cp.stderr)
            print(f'Published {name} {vn} -> {tag}'); count+=1
        print(f'Published {count} app package(s).'); return 0
    finally: shutil.rmtree(root,ignore_errors=True)
if __name__=='__main__': raise SystemExit(main())
