import argparse,json,csv
from pathlib import Path
import cv2,numpy as np

W,H=1280,578; LEFT,RIGHT=round(W*.141),round(W*.281); RANKY=round(H*.429)
ROOT=Path(__file__).parents[1]

def ink(img):
    b,g,r=[x.astype(np.int32) for x in cv2.split(img)]; return ((r*30+g*59+b*11)//100<180).astype(np.uint8)
def load_templates():
    names=[('two','rank_two.png',.55),('king','rank_king.png',.55),('queen','rank_queen.png',.55),('jack','rank_jack.png',.55),('ten','rank_ten.png',.55),('ten','rank_ten_black.png',.55),('nine','rank_nine.png',.55),('eight','rank_eight.png',.55),('seven','rank_seven.png',.55),('six','rank_six.png',.55),('five','rank_five.png',.55),('five','rank_five_alt.png',.55),('four','rank_four.png',.55),('three','rank_three.png',.55),('ace','rank_ace.png',.50),('six','rank_six_play.png',.55),('five','rank_five_play.png',.55),('four','rank_four_play.png',.55),('ten','rank_ten_play.png',.55),('queen','rank_queen_play.png',.55),('eight','rank_eight_play.png',.55),('seven','rank_seven_play.png',.55),('seven','rank_seven_play_alt.png',.55)]
    out=[]
    for rank,name,thr in names:
        p=ROOT/'app/src/main/res/drawable-nodpi'/name
        if p.exists():
            im=cv2.imread(str(p),cv2.IMREAD_COLOR); out.append((rank,thr,ink(im)))
    return out
T=load_templates()
def score(scene,t,x,y):
    h,w=t.shape; s=scene[y:y+h,x:x+w]; u=(s|t).sum(); return float((s&t).sum()/u) if u else 0
def detect_template(scene):
    matches=[]
    for y in range(RANKY-4,RANKY+5):
        row=[]
        for x in range(LEFT,RIGHT+1):
            best=None
            for rank,thr,t in T:
                if y+t.shape[0]>H or x+t.shape[1]>W: continue
                v=score(scene,t,x,y)
                if v>=thr and (best is None or v>best[1]): best=(rank,v,x)
            if best: row.append(best)
        sel=[]
        for m in sorted(row,key=lambda q:q[1],reverse=True):
            if all(abs(m[2]-q[2])>10 for q in sel): sel.append(m)
        matches.append(sel)
    row=max(matches,key=lambda r:sum(x[1] for x in r),default=[])
    return [{'rank':r,'score':round(s,4),'x':x} for r,s,x in row]
def self_check():
    ace=next(t for r,_,t in T if r=='ace'); sc=np.zeros((H,W),np.uint8); sc[RANKY:RANKY+ace.shape[0],LEFT:LEFT+ace.shape[1]]=ace
    direct=score(sc,ace,LEFT,RANKY); result=detect_template(sc)
    print(json.dumps({'ace_direct_score':direct,'best':result[:3]}))
    return direct==1.0 and any(m['rank']=='ace' for m in result)
def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--self-check',action='store_true'); ap.add_argument('--duration',type=float,default=20); ap.add_argument('--video'); ap.add_argument('--start',type=float,default=697.2); ap.add_argument('--output'); ap.add_argument('--truth'); a=ap.parse_args()
    if a.self_check: print(json.dumps({'self_check':self_check()})); return
    from tflite_crop_ab_probe import crop_variant,detect_image
    from ai_edge_litert.interpreter import Interpreter
    video=Path(a.video) if a.video else ROOT/'source/SVID_20260810_143651_1.mp4'; cap=cv2.VideoCapture(str(video)); it=Interpreter(model_path=str(ROOT/'app/src/main/assets/poker_yolo11_320.tflite'));it.allocate_tensors(); rows=[]
    times=[float(r['time_s'])+d for r in csv.DictReader(open(a.truth,encoding='utf-8')) if r['zone']=='LEFT_BOTTOM' for d in np.arange(-.3,.301,.05)] if a.truth else np.arange(a.start,a.start+a.duration,.05)
    for t in times:
        cap.set(cv2.CAP_PROP_POS_MSEC,t*1000); ok,fr=cap.read()
        if ok:
            fr=cv2.resize(fr,(W,H)); tm=detect_template(ink(fr));
            y=detect_image(it,crop_variant(fr,'LEFT_BOTTOM','C')); rows.append({'time_s':round(t,3),'template':tm,'yolo':y,'false_ace':any(x['rank']=='ace' for x in tm)})
    cap.release(); out=Path(a.output) if a.output else ROOT/'experiments/results/template_ace_audit_143651.json'; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps({'video':str(video),'start':a.start,'duration':a.duration,'rows':rows},ensure_ascii=False,indent=2)); print(json.dumps({'rows':len(rows)}))
if __name__=='__main__': main()
