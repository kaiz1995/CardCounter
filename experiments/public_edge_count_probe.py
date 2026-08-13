import csv, json
from pathlib import Path
import cv2, numpy as np

W, H = 1280, 578
ZONES = {"TOP":(.539,.602,.022),"LEFT_TOP":(.148,.266,.156),"LEFT_BOTTOM":(.141,.281,.429),"RIGHT_TOP":(.750,.859,.156),"RIGHT_BOTTOM":(.750,.859,.429)}

def crop(frame, zone):
    l, r, y = ZONES[zone]; left, right = round(W*l), round(W*r); size = right-left+1
    top = max(0, min(round(H*y-size*43/100), H-size))
    return frame[top:top+size, left:right+1]

def edge_count(crop_image):
    gray = cv2.cvtColor(crop_image, cv2.COLOR_BGR2GRAY)
    white = (gray > 190).mean(axis=0)
    runs=[]; start=None
    for x, value in enumerate(np.r_[white > .20, False]):
        if value and start is None: start=x
        if not value and start is not None:
            if x-start >= 2: runs.append((start, x-1))
            start=None
    # Public cards overlap; white runs are not independent card count.
    return len(runs), runs

def main():
    base=Path(__file__).parents[1]; cap=cv2.VideoCapture(str(base/'source/SVID_20260810_102658_1.mp4'))
    rows=[]
    with (base/'experiments/results/truth_102658_516_536.csv').open(encoding='utf-8') as f:
        for event in csv.DictReader(f):
            if event['zone']=='SELF': continue
            cap.set(cv2.CAP_PROP_POS_MSEC, float(event['time_s'])*1000); ok, frame=cap.read()
            count,runs=edge_count(crop(frame,event['zone'])) if ok else (0,[])
            truth=sum(int(t.split('x',1)[0]) if 'x' in t else 1 for t in event['cards'].split('+'))
            rows.append({**event,'edge_count':count,'truth_count':truth,'runs':runs})
    cap.release(); exact=sum(r['edge_count']==r['truth_count'] for r in rows)
    out={'rule':'gray>190 column runs >=2px, white fraction>.20','exact':exact,'total':len(rows),'rows':rows}
    (base/'experiments/results/public_edge_count_102658.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'exact':exact,'total':len(rows)}))
if __name__=='__main__': main()
