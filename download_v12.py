import requests
import os
import subprocess

def download_file(url, outfile):
    print(f"Downloading {url}...")
    # Using allow_redirects=True for GitHub
    response = requests.get(url, stream=True, allow_redirects=True)
    if response.status_code == 200:
        total_size = int(response.headers.get('content-length', 0))
        print(f"Total size: {total_size / (1024*1024):.2f} MB")
        downloaded = 0
        with open(outfile, 'wb') as f:
            for chunk in response.iter_content(chunk_size=1024*1024):
                f.write(chunk)
                downloaded += len(chunk)
                print(f"Downloaded: {downloaded / (1024*1024):.2f} MB")
        print(f"Successfully downloaded {outfile}")
    else:
        print(f"Failed: {response.status_code}")

url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.35/sherpa-onnx-v1.12.35-android.tar.bz2"
outfile = "sherpa_v12.tar.bz2"

download_file(url, outfile)

# Verification
if os.path.exists(outfile):
    print(f"Final file size: {os.path.getsize(outfile) / (1024*1024):.2f} MB")
