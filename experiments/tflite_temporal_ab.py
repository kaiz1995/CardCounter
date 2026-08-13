import csv, json
from pathlib import Path
import cv2
from ai_edge_litert.interpreter import Interpreter
from tflite_crop_ab_probe import crop_variant, ZONES
from offline_event_capture import green_gate, visible
from tflite_truth_probe import LABELS
from tflite_truth_probe import parse_truth_cards

EXTERNAL = ("TOP", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM")

def detect_image(it, image):
    import numpy as np
    x=image[:,:,::-1].astype(np.float32)/255.; it.set_tensor(it.get_input_details()[0]['index'],x[None]); it.invoke()
    out=it.get_tensor(it.get_output_details()[0]['index'])[0]; found=[]
    for i in range(out.shape[1]):
        s=out[4:,i]; cid=int(s.argmax()); conf=float(s[cid]); cy=float(out[1,i])
        if conf>.25 and cy<.85: found.append((conf,cid,out[:4,i]))
    keep=[]
    for item in sorted(found,reverse=True):
        a=item[2]; ax1,ay1,ax2,ay2=a[0]-a[2]/2,a[1]-a[3]/2,a[0]+a[2]/2,a[1]+a[3]/2
        blocked=False
        for old in keep:
            b=old[2]; bx1,by1,bx2,by2=b[0]-b[2]/2,b[1]-b[3]/2,b[0]+b[2]/2,b[1]+b[3]/2
            inter=max(0,min(ax2,bx2)-max(ax1,bx1))*max(0,min(ay2,by2)-max(ay1,by1)); union=a[2]*a[3]+b[2]*b[3]-inter
            if union and inter/union>.45: blocked=True; break
        if not blocked: keep.append(item)
    counts={}
    for _,cid,_ in keep:
        raw=LABELS[cid]; rank='BIG_JOKER' if raw=='D' else 'SMALL_JOKER' if raw=='X' else raw[1:]; counts[rank]=counts.get(rank,0)+1
    return counts

def run(video, variant):
    it = Interpreter(model_path=str(Path(__file__).parents[1] / 'app/src/main/assets/poker_yolo11_320.tflite')); it.allocate_tensors()
    cap=cv2.VideoCapture(str(video)); cap.set(cv2.CAP_PROP_POS_MSEC,516000); target, stop=516.0,536.0
    st={z:{'candidate':None,'streak':0,'empty':0,'accepted':None} for z in EXTERNAL}; events=[]
    while target<stop:
        ok,fr=cap.read()
        if not ok: break
        ms=cap.get(cv2.CAP_PROP_POS_MSEC); ts=ms/1000 if ms>0 else target
        if ts<target: continue
        table=green_gate(fr)
        for z in EXTERNAL:
            s=st[z]
            white,total=visible(fr,ZONES[z]); is_visible=table and white*100>=total*10
            if not is_visible:
                s['candidate'],s['streak']=None,0; s['empty']=min(6,s['empty']+1)
                if s['empty']>=6: s['accepted']=None
                continue
            s['empty']=0
            pred=detect_image(it,crop_variant(fr,z,variant))
            if not pred:
                s['candidate'],s['streak']=None,0; continue
            if pred==s['candidate']: s['streak']+=1
            else: s['candidate'],s['streak']=pred,1
            if s['streak']>=2 and pred!=s['accepted']:
                events.append({'time_s':round(ts,6),'zone':z,'cards':pred}); s['accepted']=pred
        while target<=ts: target+=.05
    cap.release(); return events

def score(events, truth):
    rows=[{**e,'time_s':float(e['time_s']),'counts':parse_truth_cards(e['cards'])} for e in csv.DictReader(open(truth,encoding='utf-8')) if e['zone'] in EXTERNAL]; used=set(); matches=[]
    for p in events:
        c=[(abs(p['time_s']-t['time_s']),i,t) for i,t in enumerate(rows) if i not in used and t['zone']==p['zone'] and p['cards']==t['counts'] and abs(p['time_s']-t['time_s'])<=.3]
        if c:
            _,i,t=min(c); used.add(i); matches.append({'event':p,'truth':t,'cards_match':True})
    tp=len(matches); fp=len(events)-tp; fn=len(rows)-tp
    return {'predicted':len(events),'tp':tp,'fp':fp,'fn':fn,'precision':tp/(tp+fp) if tp+fp else 0,'recall':tp/(tp+fn) if tp+fn else 0,'events':events,'matches':matches}

def main():
    base=Path(__file__).parents[1]; video=base/'source/SVID_20260810_102658_1.mp4'; truth=base/'experiments/results/truth_102658_516_536.csv'
    result={v:score(run(video,v),truth) for v in ('baseline','C')}
    assert (result['baseline']['predicted'],result['baseline']['tp'],result['baseline']['fp'],result['baseline']['fn']) == (36,33,3,2), result['baseline']
    (base/'experiments/results/tflite_temporal_ab_102658.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({v:{k:result[v][k] for k in ('predicted','tp','fp','fn','precision','recall')} for v in result},ensure_ascii=False))
if __name__=='__main__': main()
