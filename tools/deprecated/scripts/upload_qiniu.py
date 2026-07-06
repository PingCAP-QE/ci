#!/usr/bin/env python3

# Ref: https://developer.qiniu.com/kodo/1242/python
import sys
import os
from qiniu import Auth, put_file, etag, urlsafe_base64_encode
import qiniu.config

# Qiniu Access Key and Secret Key
access_key = os.environ.get('QINIU_ACCESS_KEY')
secret_key = os.environ.get('QINIU_SECRET_KEY')
bucket_name = os.environ.get("QINIU_BUCKET_NAME")
if not access_key or not secret_key or not bucket_name:
    print("Error: QINIU_ACCESS_KEY and QINIU_SECRET_KEY and QINIU_BUCKET_NAME must be set.")
    sys.exit(1)

def upload(local_file, remote_name, ttl=3600):
    print(f"Uploading {local_file} as {remote_name} with TTL {ttl}")
    q = Auth(access_key, secret_key)
    token = q.upload_token(bucket_name, remote_name, ttl)
    try:
        ret, info = put_file(token, remote_name, local_file)
        print(info)
        if ret['key'] != remote_name or ret['hash'] != etag(local_file):
            print("Error: Upload failed.")
            sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python upload.py <local_file> <remote_name>")
        sys.exit(1)
    local_file = sys.argv[1]
    remote_name = sys.argv[2]
    upload(local_file, remote_name)
