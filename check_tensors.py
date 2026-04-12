import onnxruntime as ort
from transformers import AutoTokenizer

tokenizer = AutoTokenizer.from_pretrained("facebook/nllb-200-distilled-600M")

print(f"zho_Hans ID: {tokenizer.convert_tokens_to_ids('zho_Hans')}")
print(f"BOS: {tokenizer.bos_token_id}, EOS: {tokenizer.eos_token_id}, PAD: {tokenizer.pad_token_id}")

# we just need to know the basic IDs. For tensors we can just check the python side or use generic names like "input_ids", "attention_mask", "encoder_hidden_states", "encoder_attention_mask".
