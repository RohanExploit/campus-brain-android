import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import os
import shutil
import glob
from pathlib import Path
import subprocess
import zipfile
import random

def main():
    raw_dir = Path(f"{PROJECT_ROOT}/data/tenants/tenant_1/raw")
    # Clean the raw dir so we don't have the synthetic data mixed in
    # if raw_dir.exists():
    #     shutil.rmtree(raw_dir)
    raw_dir.mkdir(parents=True, exist_ok=True)

    print("1. Copying real office documents from Dataset/...")
    dataset_dir = Path(f"{PROJECT_ROOT}/Dataset")
    extensions = ("*.docx", "*.xlsx", "*.pptx", "*.pdf")
    real_docs = []
    for ext in extensions:
        real_docs.extend(glob.glob(str(dataset_dir / ext)))

    for doc in real_docs:
        shutil.copy(doc, raw_dir / os.path.basename(doc))
        print(f"Copied {os.path.basename(doc)}")

    print("\n2. Downloading FUNSD from Hugging Face...")
    try:
        from datasets import load_dataset
        ds = load_dataset("nielsr/funsd", split="train") # test is also there, train is ~149 images
        for i, item in enumerate(ds):
            img = item["image"]
            img_path = raw_dir / f"funsd_train_{i}.png"
            img.save(img_path)
        print(f"Saved {len(ds)} FUNSD images to {raw_dir}")
    except Exception as e:
        print(f"Failed to load FUNSD: {e}")

    print("\n3. Downloading Kaggle PDFs...")
    # Attempting to use Kaggle CLI
    try:
        # Run kaggle dataset download
        print("Running kaggle datasets download...")
        subprocess.run(["kaggle", "datasets", "download", "-d", "manisha717/dataset-of-pdf-files", "-p", str(raw_dir)], check=True, capture_output=True)
        zip_path = raw_dir / "dataset-of-pdf-files.zip"
        if zip_path.exists():
            print("Extracting Kaggle ZIP...")
            extract_dir = raw_dir / "kaggle_temp"
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_dir)

            # Get all pdfs
            all_pdfs = glob.glob(str(extract_dir / "**" / "*.pdf"), recursive=True)
            print(f"Found {len(all_pdfs)} Kaggle PDFs.")

            # Sample 20
            sampled = random.sample(all_pdfs, min(20, len(all_pdfs)))
            for pdf in sampled:
                shutil.copy(pdf, raw_dir / f"kaggle_{os.path.basename(pdf)}")
                print(f"Sampled {os.path.basename(pdf)}")

            # Clean up zip and temp
            os.remove(zip_path)
            shutil.rmtree(extract_dir)
    except subprocess.CalledProcessError as e:
        print(f"Kaggle download failed (Authentication required?): {e.stderr.decode()}")
        print("Skipping Kaggle dataset.")
    except Exception as e:
        print(f"Error handling Kaggle download: {e}")

if __name__ == "__main__":
    main()
