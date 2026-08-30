import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import camelot
from pathlib import Path
import json

def extract_with_camelot():
    input_file = f"{PROJECT_ROOT}/Results Dataset/cse 5 reg.pdf"
    print(f"Running camelot on {input_file} (flavor='stream', first 5 pages for testing)")
    # Just run on first 5 pages to see if it works without OOM
    tables = camelot.read_pdf(input_file, flavor='stream', pages='1-5')
    print(f"Total tables extracted on first 5 pages: {tables.n}")

    out_dir = Path(f"{PROJECT_ROOT}/Results Dataset/camelot_out")
    out_dir.mkdir(exist_ok=True, parents=True)

    all_data = []
    for i, t in enumerate(tables):
        df = t.df
        csv_path = out_dir / f"table_{i}.csv"
        df.to_csv(csv_path, index=False)
        # also collect some sample data
        all_data.append(df.head(10).to_dict(orient='records'))

    with open(out_dir / "samples.json", "w") as f:
        json.dump(all_data, f, indent=2)

    print("Saved samples and CSVs to", out_dir)

if __name__ == '__main__':
    extract_with_camelot()
