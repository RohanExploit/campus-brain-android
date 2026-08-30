import shutil
from pathlib import Path
from utils.encryption import encrypt_file, decrypt_file

def run_encryption():
    original_file = Path("test_enc_original.txt")
    test_file = Path("test_enc_test.txt")
    dec_file = Path("test_enc_decrypted.txt")

    # create original
    with open(original_file, "wb") as f:
        f.write(b"Hello world, this is a secret encryption test.\n" * 10)

    # copy original to test_file so we can encrypt test_file in-place
    shutil.copy(original_file, test_file)

    # encrypt in-place
    encrypt_file(test_file)

    # decrypt to dec_file
    decrypt_file(test_file, dec_file)

if __name__ == "__main__":
    run_encryption()
