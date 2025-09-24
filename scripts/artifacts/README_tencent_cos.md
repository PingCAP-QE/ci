# Tencent COS Upload Script

## Overview

This script provides functionality to upload Helm chart files to Tencent Cloud COS (Cloud Object Storage).

## Requirements

- Python 3.10+
- Tencent Cloud COS Python SDK: `cos-python-sdk-v5`

Install dependencies:
```bash
pip install cos-python-sdk-v5
```

## Environment Variables

The script requires the following environment variables:

- `TENCENT_COS_ACCESS_KEY`: Tencent Cloud AccessKey
- `TENCENT_COS_SECRET_KEY`: Tencent Cloud SecretKey
- `TENCENT_COS_BUCKET_NAME`: COS bucket name
- `TENCENT_COS_REGION`: COS region (optional, defaults to ap-beijing)

## Usage

```bash
./upload_tencent_cos.py <local_file> <remote_name>
```

Parameters:
- `local_file`: Local file path
- `remote_name`: Object name in COS

Example:
```bash
export TENCENT_COS_ACCESS_KEY="your_access_key"
export TENCENT_COS_SECRET_KEY="your_secret_key"
export TENCENT_COS_BUCKET_NAME="charts"
export TENCENT_COS_REGION="ap-beijing"

./upload_tencent_cos.py tidb-operator-v1.0.0.tgz tidb-operator-v1.0.0.tgz
```

## Pipeline Integration

In the tidb-operator.groovy pipeline, uploads are now automatically performed to both Qiniu Cloud and Tencent Cloud COS:

1. **charts stage**: Upload basic chart files
2. **charts br-federation stage**: Upload br-federation related chart files
3. **charts index stage**: Upload index files

## Credentials Configuration

The following credentials need to be configured in Jenkins:
- `operator_v1_tencent_cos_access_key`: 腾讯云Access Key
- `operator_v1_tencent_cos_secret_key`: 腾讯云Secret Key
- `operator_v1_tencent_cos_bucket_name`: COS存储桶名称
