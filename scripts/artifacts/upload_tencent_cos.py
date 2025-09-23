#!/usr/bin/env python3

# Ref: https://cloud.tencent.com/document/product/436/12269
import sys
import os
from qcloud_cos import CosConfig
from qcloud_cos import CosS3Client
from qcloud_cos.cos_exception import CosServiceError, CosClientError

# Tencent COS Access Key and Secret Key
access_key = os.environ.get('TENCENT_COS_ACCESS_KEY')
secret_key = os.environ.get('TENCENT_COS_SECRET_KEY')
bucket_name = os.environ.get("TENCENT_COS_BUCKET_NAME")
region = os.environ.get("TENCENT_COS_REGION", "ap-beijing")  # Default to Beijing region

if not access_key or not secret_key or not bucket_name:
    print("Error: TENCENT_COS_ACCESS_KEY, TENCENT_COS_SECRET_KEY and TENCENT_COS_BUCKET_NAME must be set.")
    sys.exit(1)


def upload(local_file, remote_name):
    print(f"Uploading {local_file} to Tencent COS as {remote_name}")

    # Configure COS client
    config = CosConfig(Region=region, SecretId=access_key, SecretKey=secret_key)
    client = CosS3Client(config)

    try:
        # Upload file
        response = client.upload_file(
            Bucket=bucket_name,
            LocalFilePath=local_file,
            Key=remote_name,
        )
        print(f"Upload successful. ETag: {response['ETag']}")

        # Verify upload by checking file existence
        try:
            client.head_object(Bucket=bucket_name, Key=remote_name)
            print(f"File verification successful: {remote_name}")
        except CosServiceError as e:
            print(f"Error: Upload verification failed: {e}")
            sys.exit(1)

    except CosServiceError as e:
        print(f"Error: COS service error: {e}")
        sys.exit(1)
    except CosClientError as e:
        print(f"Error: COS client error: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python upload_tencent_cos.py <local_file> <remote_name>")
        sys.exit(1)
    local_file = sys.argv[1]
    remote_name = sys.argv[2]

    # Check if local file exists
    if not os.path.exists(local_file):
        print(f"Error: Local file {local_file} does not exist.")
        sys.exit(1)

    upload(local_file, remote_name)
