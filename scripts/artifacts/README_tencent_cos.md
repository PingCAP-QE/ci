# 腾讯云COS上传脚本

## 功能说明

本脚本提供了将Helm chart文件上传到腾讯云COS（对象存储）的功能，作为现有七牛云上传功能的补充。

## 依赖要求

- Python 3.6+
- 腾讯云COS Python SDK: `cos-python-sdk-v5`

安装依赖：
```bash
pip install cos-python-sdk-v5
```

## 环境变量配置

脚本需要以下环境变量：

- `TENCENT_COS_ACCESS_KEY`: 腾讯云AccessKey
- `TENCENT_COS_SECRET_KEY`: 腾讯云SecretKey  
- `TENCENT_COS_BUCKET_NAME`: COS存储桶名称
- `TENCENT_COS_REGION`: COS地域（可选，默认为ap-beijing）

## 使用方法

```bash
./upload_tencent_cos.py <local_file> <remote_name>
```

参数说明：
- `local_file`: 本地文件路径
- `remote_name`: 在COS中的对象名称

示例：
```bash
export TENCENT_COS_ACCESS_KEY="your_access_key"
export TENCENT_COS_SECRET_KEY="your_secret_key"
export TENCENT_COS_BUCKET_NAME="charts"
export TENCENT_COS_REGION="ap-beijing"

./upload_tencent_cos.py tidb-operator-v1.0.0.tgz tidb-operator-v1.0.0.tgz
```

## Pipeline集成

在tidb-operator.groovy pipeline中，现在会自动同时上传到七牛云和腾讯云COS：

1. **charts阶段**: 上传基础chart文件
2. **charts br-federation阶段**: 上传br-federation相关chart文件  
3. **charts index阶段**: 上传索引文件

## 凭据配置

在Jenkins中需要配置以下凭据：
- `tencent_cos_access_key`: 腾讯云Access Key
- `tencent_cos_secret_key`: 腾讯云Secret Key

## 错误处理

脚本包含以下错误处理机制：
- 环境变量检查
- 本地文件存在性检查
- 上传后验证
- 详细的错误信息输出
