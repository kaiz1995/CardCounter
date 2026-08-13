import csv, json
from pathlib import Path
import cv2, numpy as np
from ai_edge_litert.interpreter import Interpreter

W, H, SIZE = 1280, 578, 320
ZONES = {
    "TOP": (.539, .602, .022), "LEFT_TOP": (.148, .266, .156),
    "LEFT_BOTTOM": (.141, .281, .429), "RIGHT_TOP": (.750, .859, .156),
    "RIGHT_BOTTOM": (.750, .859, .429), "SELF": (.438, .563, .358),
}
LABELS = ["RA","R2","R3","R4","R5","R6","R7","R8","R9","R10","RJ","RQ","RK",
          "BA","B2","B3","B4","B5","B6","B7","B8","B9","B10","BJ","BQ","BK","D","X"]
RANKS = {x: x for x in ("A","2","3","4","5","6","7","8","9","10","J","Q","K")}

def parse_truth_cards(text):
    result = {}
    for token in text.split('+'):
        if 'x' in token:
            count, rank = token.split('x', 1)
            result[rank] = result.get(rank, 0) + int(count)
        else:
            result[token] = result.get(token, 0) + 1
    return result

def crop(frame, zone):
    if frame.shape[:2] != (H, W):
        raise ValueError(f"expected {W}x{H}, got {frame.shape[1]}x{frame.shape[0]}")
    l, r, y = ZONES[zone]; left, right = round(W*l), round(W*r); size = right-left+1
    top = max(0, min(round(H*y-size*43/100), frame.shape[0]-size))
    return cv2.resize(frame[top:top+size, left:right+1], (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)

def iou(a,b):
    ax1, ay1, ax2, ay2 = a[0]-a[2]/2, a[1]-a[3]/2, a[0]+a[2]/2, a[1]+a[3]/2
    bx1, by1, bx2, by2 = b[0]-b[2]/2, b[1]-b[3]/2, b[0]+b[2]/2, b[1]+b[3]/2
    inter=max(0,min(ax2,bx2)-max(ax1,bx1))*max(0,min(ay2,by2)-max(ay1,by1)); union=a[2]*a[3]+b[2]*b[3]-inter
    return inter/union if union else 0

def detect(it, frame, zone):
    x = crop(frame, zone)[:, :, ::-1].astype(np.float32)/255.0
    it.set_tensor(it.get_input_details()[0]['index'], x[None]); it.invoke()
    out = it.get_tensor(it.get_output_details()[0]['index'])[0]
    ds, n = out.shape; found=[]
    for i in range(n):
        scores=out[4:,i]; cid=int(scores.argmax()); conf=float(scores[cid])
        if conf > .25 and out[1, i] < .85: found.append((conf, cid, out[:4,i]))
    keep=[]
    for c in sorted(found, key=lambda item: item[0], reverse=True):
        if all(iou(c[2], k[2]) <= .45 for k in keep): keep.append(c)
    counts={}
    for conf,cid,_ in keep:
        raw=LABELS[cid]; rank='BIG_JOKER' if raw=='D' else 'SMALL_JOKER' if raw=='X' else raw[1:]
        counts[rank]=counts.get(rank,0)+1
    boxes=[]
    for conf,cid,b in keep:
        raw=LABELS[cid]; rank='BIG_JOKER' if raw=='D' else 'SMALL_JOKER' if raw=='X' else raw[1:]
        boxes.append({'rank':rank,'conf':round(float(conf),4),'cx':round(float(b[0]),4),'cy':round(float(b[1]),4),'w':round(float(b[2]),4),'h':round(float(b[3]),4)})
    return counts, len(keep), max((c[0] for c in keep), default=0.0), boxes

def main():
    base=Path(__file__).parents[1]; truth=base/'experiments/results/truth_102658_516_536.csv'
    it=Interpreter(model_path=str(base/'app/src/main/assets/poker_yolo11_320.tflite')); it.allocate_tensors()
    cap=cv2.VideoCapture(str(base/'source/SVID_20260810_102658_1.mp4')); rows=[]
    for e in csv.DictReader(truth.open(encoding='utf-8')):
        cap.set(cv2.CAP_PROP_POS_MSEC,float(e['time_s'])*1000); ok,fr=cap.read()
        pred,n,conf,boxes=detect(it,fr,e['zone']) if ok else ({},0,0,[])
        truth_cards = parse_truth_cards(e['cards'])
        rows.append({**e,'predicted':json.dumps(pred,ensure_ascii=False),'truth_counts':truth_cards,
                     'exact_match':pred == truth_cards,'detections':n,'max_confidence':round(conf,4),'boxes':boxes})
    cap.release(); out=base/'experiments/results/tflite_truth_probe_102658.json'
    summary={'exact':sum(r['exact_match'] for r in rows),'total':len(rows)}
    out.write_text(json.dumps({'summary':summary,'rows':rows},ensure_ascii=False,indent=2),encoding='utf-8'); print(json.dumps(summary,ensure_ascii=False))
if __name__=='__main__': main()
