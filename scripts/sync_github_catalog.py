#!/usr/bin/env python3
from __future__ import annotations
import json, os, re, sys, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
OWNER=os.environ.get('GITHUB_OWNER','mazharmnzoor4227-beep').strip(); TOKEN=os.environ.get('GH_TOKEN',os.environ.get('GITHUB_TOKEN','')).strip(); OUTPUT=Path(os.environ.get('CATALOG_OUTPUT','catalog/catalog.json')); API='https://api.github.com'
def request_bytes(url:str,use_token:bool=True)->bytes:
    h={'Accept':'application/vnd.github+json','User-Agent':'apk-app-store-catalog-sync','X-GitHub-Api-Version':'2022-11-28'}
    if TOKEN and use_token:h['Authorization']=f'Bearer {TOKEN}'
    with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=45) as r:return r.read()
def request_json(url:str)->Any:return json.loads(request_bytes(url,True).decode('utf-8'))
def paged(path:str)->list[Any]:
    out=[]; page=1
    while True:
        sep='&' if '?' in path else '?'; data=request_json(f'{API}{path}{sep}per_page=100&page={page}')
        if not isinstance(data,list):return out
        out.extend(data)
        if len(data)<100:return out
        page+=1
def load_asset(a:dict[str,Any])->Any:
    u=str(a.get('browser_download_url') or '')
    if not u:raise ValueError('manifest URL missing')
    return json.loads(request_bytes(u,False).decode('utf-8'))
def valid(raw:dict[str,Any])->dict[str,Any]|None:
    package=str(raw.get('package_name') or '').strip(); slug=str(raw.get('slug') or '').strip(); name=str(raw.get('name') or '').strip(); dl=str(raw.get('download_url') or '').strip()
    try:vc=int(raw.get('version_code') or 0)
    except Exception:vc=0
    if not package or not slug or not name or vc<=0 or not dl.startswith('https://github.com/'):return None
    return {'slug':slug,'package_name':package,'name':name,'short_description':str(raw.get('short_description') or ''),'description':str(raw.get('description') or ''),'category':str(raw.get('category') or 'Apps'),'icon_url':str(raw.get('icon_url') or ''),'featured':bool(raw.get('featured',False)),'is_paid':False,'price_pkr':0,'owned':True,'purchase_status':'free','version_code':vc,'version_name':str(raw.get('version_name') or vc),'min_sdk':int(raw.get('min_sdk') or 21),'download_url':dl,'file_size':int(raw.get('file_size') or 0),'sha256':str(raw.get('sha256') or '').lower(),'changelog':str(raw.get('changelog') or ''),'screenshots':[str(x) for x in (raw.get('screenshots') or []) if str(x).startswith('https://')],'source_repo':str(raw.get('source_repo') or ''),'source_release_tag':str(raw.get('source_release_tag') or ''),'published_at':str(raw.get('published_at') or ''),'commit_sha':str(raw.get('commit_sha') or ''),'signing_sha256':str(raw.get('signing_sha256') or '').lower()}
def apps_from_release(repo:str,release:dict[str,Any])->list[dict[str,Any]]:
    out=[]; assets=release.get('assets') or []; manifests=[a for a in assets if re.fullmatch(r'store-manifest(?:-[A-Za-z0-9._-]+)?\.json',str(a.get('name') or ''))]
    for a in manifests:
        try:p=load_asset(a)
        except Exception as e:print(f'warning: {repo}/{a.get("name")}: {e}',file=sys.stderr); continue
        for entry in (p if isinstance(p,list) else [p]):
            if not isinstance(entry,dict):continue
            x=dict(entry); x['source_repo']=repo; x['source_release_tag']=str(release.get('tag_name') or ''); x['published_at']=str(release.get('published_at') or release.get('created_at') or ''); app=valid(x)
            if app:out.append(app)
    return out
def rank(a:dict[str,Any])->tuple[int,str]:return (int(a.get('version_code') or 0),str(a.get('published_at') or ''))
def old_catalog()->dict[str,Any]:
    try:return json.loads(OUTPUT.read_text(encoding='utf-8')) if OUTPUT.exists() else {}
    except Exception:return {}
def main()->int:
    repos=paged(f'/users/{urllib.parse.quote(OWNER)}/repos?type=owner&sort=updated'); best={}
    for repo in repos:
        if not isinstance(repo,dict) or repo.get('private') or repo.get('archived') or repo.get('disabled'):continue
        full=str(repo.get('full_name') or '')
        if not full:continue
        try:rels=paged(f'/repos/{full}/releases')
        except Exception as e:print(f'warning: releases failed for {full}: {e}',file=sys.stderr); continue
        for rel in rels:
            if not isinstance(rel,dict) or rel.get('draft'):continue
            for app in apps_from_release(full,rel):
                k=app['package_name'].lower(); prev=best.get(k)
                if prev is None or rank(app)>rank(prev):best[k]=app
    apps=list(best.values()); apps.sort(key=lambda x:(not bool(x.get('featured')),str(x.get('name') or '').lower())); old=old_catalog(); old_apps=old.get('apps') if isinstance(old.get('apps'),list) else []; generated=str(old.get('generated_at') or '')
    if old_apps!=apps or not generated:generated=datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
    out={'schema_version':1,'owner':OWNER,'generated_at':generated,'app_count':len(apps),'apps':apps}; OUTPUT.parent.mkdir(parents=True,exist_ok=True); OUTPUT.write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n',encoding='utf-8'); print(f'Wrote {OUTPUT} with {len(apps)} app(s).'); return 0
if __name__=='__main__':raise SystemExit(main())
