import requests
import os

def download_file(url, outfile):
    print(f"Downloading {url} to {outfile}...")
    headers = {'User-Agent': 'Mozilla/5.0'}
    # Special fix for HF resolve URLs: adding allow_redirects=True (default true)
    response = requests.get(url, headers=headers, stream=True)
    if response.status_code == 200:
        with open(outfile, 'wb') as f:
            for chunk in response.iter_content(chunk_size=8192):
                f.write(chunk)
        print(f"Successfully downloaded {outfile}")
    else:
        print(f"Failed to download {url}: Status {response.status_code}")
        # Try raw URL fallback
        raw_url = url.replace("/resolve/main/", "/raw/main/")
        print(f"Trying fallback: {raw_url}")
        response = requests.get(raw_url, headers=headers, stream=True)
        if response.status_code == 200:
            with open(outfile, 'wb') as f:
                for chunk in response.iter_content(chunk_size=8192):
                    f.write(chunk)
            print(f"Successfully downloaded {outfile} via fallback")

# Paths
base_dir = r"D:\gitweb\ChinaVoiceAIOnDevice"
jni_libs_dir = os.path.join(base_dir, "app", "src", "main", "jniLibs", "arm64-v8a")
cpp_capi_dir = os.path.join(base_dir, "app", "src", "main", "cpp", "sherpa-onnx", "c-api")

if not os.path.exists(jni_libs_dir):
    os.makedirs(jni_libs_dir)
if not os.path.exists(cpp_capi_dir):
    os.makedirs(cpp_capi_dir)

# File URLs
files = {
    "c-api.h": ("https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/c-api/c-api.h", cpp_capi_dir),
    "libsherpa-onnx-c-api.so": ("https://huggingface.co/csukuangfj/sherpa-onnx-nllb-200-600M/resolve/main/libsherpa-onnx-c-api.so", jni_libs_dir),
    "libsherpa-onnx-jni.so": ("https://huggingface.co/csukuangfj/sherpa-onnx-nllb-200-600M/resolve/main/libsherpa-onnx-jni.so", jni_libs_dir),
    "libonnxruntime.so": ("https://huggingface.co/csukuangfj/sherpa-onnx-nllb-200-600M/resolve/main/libonnxruntime.so", jni_libs_dir)
}

for filename, (url, target_dir) in files.items():
    target_path = os.path.join(target_dir, filename)
    download_file(url, target_path)
