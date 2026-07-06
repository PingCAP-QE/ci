
#!/usr/bin/env python3

# Ref: https://developer.qiniu.com/kodo/1242/python
import sys
import os
from qiniu import Auth, BucketManager

# Qiniu Access Key and Secret Key
access_key = os.environ.get('QINIU_ACCESS_KEY')
secret_key = os.environ.get('QINIU_SECRET_KEY')

def delete_object(bucket_name, object_key):
    if not access_key or not secret_key:
        print("Error: QINIU_ACCESS_KEY and QINIU_SECRET_KEY must be set.")
        return

    # Construct authentication object
    q = Auth(access_key, secret_key)

    # Construct bucket manager object
    bucket_manager = BucketManager(q)

    # Delete the object from the bucket
    try:
        ret, info = bucket_manager.delete(bucket_name, object_key)
        print(f"Deleted object: {object_key}")
    except Exception as e:
        print(f"Error deleting object: {e}")

if __name__ == "__main__":
    import os

    bucket_name = os.getenv("QINIU_BUCKET_NAME")

    if not bucket_name:
        print("Error: QINIU_BUCKET_NAME must be set.")
        sys.exit(1)

    if len(sys.argv) != 2:
        print("Usage: python delete_object.py <object_key>")
        sys.exit(1)

    object_key = sys.argv[1]
    delete_object(bucket_name, object_key)
