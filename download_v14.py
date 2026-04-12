import requests
import os
import subprocess

def download_file(url, outfile):
    print(f"Downloading {url}...")
    response = requests.get(url, stream=True)
    if response.status_code == 200:
        with open(outfile, 'wb') as f:
            for chunk in response.iter_content(chunk_size=1024*1024): # 1MB chunk
                f.write(chunk)
                print(".", end="", flush=True)
        print(f"\nSuccessfully downloaded {outfile}")
    else:
        print(f"Failed: {response.status_code}")

url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.14.0/sherpa-onnx-1.14.0-android.tar.bz2"
outfile = "sherpa_v14.tar.bz2"

if not os.path.exists(outfile):
    download_file(url, outfile)

print("Extracting arm64-v8a libs...")
# Extracting specifically from the archive
# Structure is: sherpa-onnx-1.14.0-android/jniLibs/arm64-v8a/
try:
    subprocess.run(["tar", "-xf", outfile, "--strip-components=3", "sherpa-onnx-1.14.0-android/jniLibs/arm64-v8a/"], check=True)
    print("Extraction successful.")
    
    # Move files to the project jniLibs
    target_dir = r"D:\gitweb\ChinaVoiceAIOnDevice\app\src\main\jniLibs\arm64-v8a"
    files_to_move = ["libsherpa-onnx-c-api.so", "libsherpa-onnx-jni.so", "libonnxruntime.so"]
    
    for f in files_to_move:
        if os.path.exists(f):
            dest = os.path.join(target_dir, f)
            print(f"Installing {f} to {dest}")
            if os.path.exists(dest): os.remove(dest)
            os.rename(f, dest)
    print("Installation complete.")
except Exception as e:
    print(f"Error during extraction/installation: {e}")
