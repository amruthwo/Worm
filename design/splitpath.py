# Splits a compound SVG path (M/m/C/c/L/l/Z/z) into absolute subpaths with bounding boxes.
import re, sys, json
def subpaths(d):
    toks=re.findall(r'[MmCcLlZz]|-?\d*\.?\d+(?:e-?\d+)?',d)
    i=0; cmd=None; x=y=0; sx=sy=0; out=[]; cur=None
    def num():
        nonlocal i; v=float(toks[i]); i+=1; return v
    while i<len(toks):
        t=toks[i]
        if t.isalpha(): cmd=t; i+=1
        if cmd in 'Zz':
            cur['d'].append('Z'); x,y=sx,sy; cmd=None; continue
        if cmd in 'Mm':
            nx,ny=num(),num()
            if cmd=='m': nx+=x; ny+=y
            x,y=nx,ny; sx,sy=x,y
            cur={'d':[f'M{x:.2f},{y:.2f}'],'pts':[(x,y)]}; out.append(cur)
            cmd='l' if cmd=='m' else 'L'
        elif cmd in 'Ll':
            nx,ny=num(),num()
            if cmd=='l': nx+=x; ny+=y
            x,y=nx,ny; cur['d'].append(f'L{x:.2f},{y:.2f}'); cur['pts'].append((x,y))
        elif cmd in 'Cc':
            p=[num() for _ in range(6)]
            if cmd=='c': p=[p[0]+x,p[1]+y,p[2]+x,p[3]+y,p[4]+x,p[5]+y]
            x,y=p[4],p[5]
            cur['d'].append('C'+' '.join(f'{p[k]:.2f},{p[k+1]:.2f}' for k in (0,2,4))); cur['pts']+= [(p[0],p[1]),(p[2],p[3]),(x,y)]
    res=[]
    for c in out:
        xs=[p[0] for p in c['pts']]; ys=[p[1] for p in c['pts']]
        res.append({'d':''.join(c['d']),'bbox':[min(xs),min(ys),max(xs),max(ys)]})
    return res
if __name__=='__main__':
    s=open(sys.argv[1]).read()
    d=re.search(r' d="([^"]*)"',s).group(1)
    sp=subpaths(d)
    json.dump(sp,open(sys.argv[2],'w'))
    for i,p in enumerate(sp): print(i,[round(v) for v in p['bbox']])
