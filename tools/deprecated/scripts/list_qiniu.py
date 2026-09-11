#!/usr/bin/env python3

# Ref: https://developer.qiniu.com/kodo/1242/python
import sys
import os
from qiniu import Auth, BucketManager

# Qiniu Access Key and Secret Key
access_key = os.environ.get('QINIU_ACCESS_KEY')
secret_key = os.environ.get('QINIU_SECRET_KEY')

def list_objects(bucket_name, prefix):
    if not access_key or not secret_key:
        print("Error: QINIU_ACCESS_KEY and QINIU_SECRET_KEY must be set.")
        return

    # Construct authentication object
    q = Auth(access_key, secret_key)

    # Construct bucket manager object
    bucket_manager = BucketManager(q)

    # List objects with the specified prefix
    try:
        marker = None
        while True:
            ret, eof, info = bucket_manager.list(bucket_name, prefix=prefix, marker=marker, limit=100, delimiter=None)
            for item in ret['items']:
                key = item['key']
                is_folder = key.endswith('/')
                print(f"{key} - {'Folder' if is_folder else 'File'}")
            if eof: break
            marker = ret['marker']
    except Exception as e:
        print(f"Error listing objects: {e}")

if __name__ == "__main__":
    import os

    bucket_name = os.getenv("QINIU_BUCKET_NAME")

    if not bucket_name:
        print("Error: QINIU_BUCKET_NAME must be set.")
        sys.exit(1)

    if len(sys.argv) != 2:
        print("Usage: python list_objects.py <prefix>")
        sys.exit(1)

    prefix = sys.argv[1]
    list_objects(bucket_name, prefix)
