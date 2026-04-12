import flask
from flask import request, jsonify
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
import threading

app = flask.Flask(__name__)

print("Loading NLLB-200 model... Please wait.")
tokenizer = AutoTokenizer.from_pretrained("facebook/nllb-200-distilled-600M")
model = AutoModelForSeq2SeqLM.from_pretrained("facebook/nllb-200-distilled-600M")
print("Model loaded successfully!")

@app.route('/translate', methods=['POST'])
def translate():
    data = request.json
    text = data.get("text", "")
    print(f"Received request to translate: {text}")
    
    if not text:
        return jsonify({"translatedText": ""})

    # Translate Korean to Chinese Simplified
    inputs = tokenizer(text, return_tensors="pt")
    translated_tokens = model.generate(
        **inputs, forced_bos_token_id=tokenizer.lang_code_to_id["zho_Hans"], max_length=100
    )
    result = tokenizer.batch_decode(translated_tokens, skip_special_tokens=True)[0]
    print(f"Translation result: {result}")
    
    return jsonify({"translatedText": result})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
