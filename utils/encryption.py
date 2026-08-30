import sys
import logging
from pathlib import Path
from cryptography.fernet import Fernet
sys.path.append(str(Path(__file__).resolve().parent.parent))

from config import ENCRYPTION_KEY_PATH

def get_or_create_key() -> bytes:
    key_file = Path(ENCRYPTION_KEY_PATH)
    if key_file.exists():
        with open(key_file, "rb") as f:
            return f.read().strip()
    else:
        logging.info("Generating new encryption key...")
        key = Fernet.generate_key()
        with open(key_file, "wb") as f:
            f.write(key)
        return key

_CIPHER = Fernet(get_or_create_key())

def encrypt_file(filepath: Path) -> bool:
    """Encrypts a file in place."""
    try:
        with open(filepath, "rb") as f:
            data = f.read()

        encrypted_data = _CIPHER.encrypt(data)

        with open(filepath, "wb") as f:
            f.write(encrypted_data)
        return True
    except Exception as e:
        logging.error(f"Failed to encrypt {filepath}: {e}")
        return False

def decrypt_file(filepath: Path, output_path: Path = None) -> bool:
    """Decrypts a file. If output_path is provided, writes decrypted data there,
    otherwise decrypts in place."""
    try:
        with open(filepath, "rb") as f:
            encrypted_data = f.read()

        decrypted_data = _CIPHER.decrypt(encrypted_data)

        out_file = output_path if output_path else filepath
        with open(out_file, "wb") as f:
            f.write(decrypted_data)
        return True
    except Exception as e:
        logging.error(f"Failed to decrypt {filepath}: {e}")
        return False
