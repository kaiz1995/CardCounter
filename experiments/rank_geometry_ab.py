import json, sys
from pathlib import Path
import cv2, numpy as np
from ai_edge_litert.interpreter import Interpreter
sys.path.insert(0, str(Path(__file__).parent))
from tflite_crop_ab_probe import crop_variant, LABELS

def detect(it, image):
    x=image[:,:,::-1].astype(np.float32)/255.0; it.set_tensor(it.get_input_details()[0]['index'],x[None]); it.invoke()
    out=it.get_tensor(it.get_output_details()[0]['index'])[0]; found=[]
    for i in range(out.shape[1]):
        s=out[4:,i]; cid=int(s.argmax()); conf=float(s[cid])
        if conf>.25 and out[1,i]<.85: found.append((conf,cid,out[:4,i]))
    keep=[]
    for item in sorted(found,reverse=True,key=lambda q:q[0]):
        a=item[2]; ax1,ay1,ax2,ay2=a[0]-a[2]/2,a[1]-a[3]/2,a[0]+a[2]/2,a[1]+a[3]/2
        blocked=False
        for old in keep:
            b=old[2]; inter=max(0,min(ax2,b[0]+b[2]/2)-max(ax1,b[0]-b[2]/2))*max(0,min(ay2,b[1]+b[3]/2)-max(ay1,b[1]-b[3]/2)); union=a[2]*a[3]+b[2]*b[3]-inter
            if union and inter/union>.45: blocked=True; break
        if not blocked: keep.append(item)
    rows=[]
    for conf,cid,b in keep:
        raw=LABELS[cid]; rank='BIG_JOKER' if raw=='D' else 'SMALL_JOKER' if raw=='X' else raw[1:]
        rows.append({'rank':rank,'raw':raw,'conf':round(conf,4),'cx':round(float(b[0]),4),'cy':round(float(b[1]),4)})
    return rows

def run(video,start,duration,out):
    base=Path(__file__).parents[1]; it=Interpreter(model_path=str(base/'app/src/main/assets/poker_yolo11_320.tflite'));it.allocate_tensors(); cap=cv2.VideoCapture(str(video)); rows=[]; t=start; stop=start+duration
    while t<stop:
        cap.set(cv2.CAP_PROP_POS_MSEC,t*1000); ok,fr=cap.read()
        if ok:
            a=detect(it,crop_variant(fr,'LEFT_BOTTOM','C')); b=detect(it,cv2.resize(crop_variant(fr,'LEFT_BOTTOM','baseline'),(320,320)))
            if any(x['rank']=='A' for x in a+b): rows.append({'time_s':round(t,3),'A_letterbox':a,'B_stretch':b})
        t+=.1
    cap.release(); out.write_text(json.dumps({'video':str(video),'start':start,'duration':duration,'rows':rows},ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':
    base=Path(__file__).parents[1]; out=base/'experiments/results/rank_geometry_ab';out.mkdir(parents=True,exist_ok=True)
    run(base/'source/SVID_20260810_143651_1.mp4',697.2,20,out/'143651_697_717.json')
    run(base/'source/SVID_20260810_102658_1.mp4',413,123,out/'102658_413_536.json')
