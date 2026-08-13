"""Blind C-geometry temporal aggregation; truth is read only for final scoring."""
import argparse, csv, json
from pathlib import Path
import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from offline_event_capture import ZONES, green_gate, visible
from tflite_crop_ab_probe import crop_variant
from tflite_truth_probe import LABELS, parse_truth_cards

EXTERNAL = ("TOP", "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM")


def detect_detail(it, image):
    x = image[:, :, ::-1].astype(np.float32) / 255.0
    it.set_tensor(it.get_input_details()[0]["index"], x[None]); it.invoke()
    out = it.get_tensor(it.get_output_details()[0]["index"])[0]; found=[]
    for i in range(out.shape[1]):
        scores = out[4:, i]; cid=int(scores.argmax()); conf=float(scores[cid]); cy=float(out[1, i])
        if conf > .25 and cy < .85: found.append((conf, cid, out[:4, i]))
    keep=[]
    for item in sorted(found, reverse=True):
        a=item[2]; ax1,ay1,ax2,ay2=a[0]-a[2]/2,a[1]-a[3]/2,a[0]+a[2]/2,a[1]+a[3]/2
        blocked=False
        for old in keep:
            b=old[2]; bx1,by1,bx2,by2=b[0]-b[2]/2,b[1]-b[3]/2,b[0]+b[2]/2,b[1]+b[3]/2
            inter=max(0,min(ax2,bx2)-max(ax1,bx1))*max(0,min(ay2,by2)-max(ay1,by1)); union=a[2]*a[3]+b[2]*b[3]-inter
            if union and inter/union>.45: blocked=True; break
        if not blocked: keep.append(item)
    counts={}
    for _,cid,_ in keep:
        raw=LABELS[cid]; rank="BIG_JOKER" if raw=="D" else "SMALL_JOKER" if raw=="X" else raw[1:]
        counts[rank]=counts.get(rank,0)+1
    return counts, min((x[0] for x in keep), default=0.0)


def total(cards): return sum(cards.values())


def joker_only(cards):
    return {k:v for k,v in cards.items() if k in ("BIG_JOKER", "SMALL_JOKER")}


def merge(base, jokers):
    out=dict(base)
    for k,v in jokers.items(): out[k]=out.get(k,0)+v
    return out


def run(video, start=516.0, duration=20.0):
    it=Interpreter(model_path=str(Path(__file__).parents[1]/"app/src/main/assets/poker_yolo11_320.tflite")); it.allocate_tensors()
    cap=cv2.VideoCapture(str(video)); cap.set(cv2.CAP_PROP_POS_MSEC,start*1000); target,stop=start,start+duration
    state={z:{"samples":[],"pending":None,"accepted":None,"empty":0} for z in EXTERNAL}; events=[]

    def emit(z, cards, ts):
        if cards and cards != state[z]["accepted"]:
            events.append({"time_s":round(ts,6),"zone":z,"cards":cards}); state[z]["accepted"]=cards

    def finish_batch(z, batch, ts):
        if not batch: return
        groups={}
        for cards,conf in batch:
            if cards: groups.setdefault(tuple(sorted(cards.items())), []).append((cards,conf))
        if not groups: return
        winner=max(groups.values(), key=lambda xs:(len(xs), min(x[1] for x in xs)))
        cards,conf=winner[0]; count=len(winner)
        if count < 2 and not (total(cards)>=3 and conf>=.60): return
        pending=state[z]["pending"]
        if total(cards)>=3:
            if pending and ts-pending["time_s"]<=.3 and cards != pending["cards"]:
                emit(z, merge(cards, joker_only(pending["cards"])), ts); state[z]["pending"]=None
            elif pending and cards == pending["cards"]:
                return
            elif pending:
                emit(z, pending["cards"], pending["time_s"]); state[z]["pending"]={"cards":cards,"time_s":ts}
            else:
                state[z]["pending"]={"cards":cards,"time_s":ts}
        else:
            if pending and ts-pending["time_s"]<=.3 and cards != pending["cards"]:
                emit(z, merge(cards, joker_only(pending["cards"])), ts); state[z]["pending"]=None
            elif pending and ts-pending["time_s"]>.3:
                emit(z,pending["cards"],pending["time_s"]); state[z]["pending"]=None; emit(z,cards,ts)
            elif not pending:
                emit(z,cards,ts)

    while target<stop:
        ok,fr=cap.read()
        if not ok: break
        ms=cap.get(cv2.CAP_PROP_POS_MSEC); ts=ms/1000 if ms>0 else target
        if ts<target: continue
        table=green_gate(fr)
        for z in EXTERNAL:
            s=state[z]
            white,tot=visible(fr,ZONES[z]); vis=table and white*100>=tot*10
            if not vis:
                s["samples"].clear(); s["empty"]=min(6,s["empty"]+1)
                if s["empty"]>=6:
                    if s["pending"]: emit(z,s["pending"]["cards"],s["pending"]["time_s"]); s["pending"]=None
                    s["accepted"]=None
                continue
            s["empty"]=0
            s["samples"].append(detect_detail(it,crop_variant(fr,z,"C")))
            if len(s["samples"])>=3:
                batch=s["samples"][:3]; del s["samples"][:3]; finish_batch(z,batch,ts)
        while target<=ts: target+=.05
    cap.release()
    for z,s in state.items():
        if s["pending"]: emit(z,s["pending"]["cards"],s["pending"]["time_s"])
    return events


def score(events, truth_path):
    truth=[{**e,"time_s":float(e["time_s"]),"counts":parse_truth_cards(e["cards"])} for e in csv.DictReader(open(truth_path,encoding="utf-8")) if e["zone"] in EXTERNAL]
    used=set(); matches=[]
    for p in events:
        c=[(abs(p["time_s"]-t["time_s"]),i,t) for i,t in enumerate(truth) if i not in used and t["zone"]==p["zone"] and abs(p["time_s"]-t["time_s"])<=.3]
        if c:
            _,i,t=min(c); used.add(i); matches.append({"event":p,"truth":t,"cards_match":p["cards"]==t["counts"]})
    tp=sum(m["cards_match"] for m in matches); fp=len(events)-tp; fn=len(truth)-len(used)
    return {"predicted":len(events),"tp":tp,"fp":fp,"fn":fn,"precision":tp/(tp+fp) if tp+fp else 0,"recall":tp/(tp+fn) if tp+fn else 0,"events":events,"matches":matches}


def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--video',required=True); ap.add_argument('--start',type=float,required=True); ap.add_argument('--duration',type=float,required=True); ap.add_argument('--truth',required=True); ap.add_argument('--output',required=True); args=ap.parse_args()
    result=score(run(Path(args.video),args.start,args.duration),Path(args.truth))
    Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:result[k] for k in ("predicted","tp","fp","fn","precision","recall")},ensure_ascii=False))

if __name__=="__main__": main()
