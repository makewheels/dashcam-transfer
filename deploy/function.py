"""Deploy a small HTTP control plane. Credentials must already be injected from Infisical."""
import base64
import os
import tempfile
import hashlib
import oss2
from pathlib import Path

from alibabacloud_fc20230330.client import Client
from alibabacloud_fc20230330 import models
from alibabacloud_tea_openapi.models import Config

from package_server import package

RUNTIME_KEYS = ("DASHCAM_MONGO_URI", "DASHCAM_DB_NAME", "DASHCAM_APP_TOKEN", "OSS_ACCESS_KEY_ID",
                "OSS_ACCESS_KEY_SECRET", "OSS_BUCKET", "OSS_ENDPOINT")


def deploy(create=False, upload_code=True):
    region = os.environ.get("OSS_REGION", "cn-beijing")
    config = Config(access_key_id=os.environ["ALIBABA_CLOUD_ACCESS_KEY_ID"],
                    access_key_secret=os.environ["ALIBABA_CLOUD_ACCESS_KEY_SECRET"], endpoint=f"fcv3.{region}.aliyuncs.com")
    client = Client(config)
    name = os.environ["DASHCAM_FUNCTION_NAME"]
    variables = {key: os.environ[key] for key in RUNTIME_KEYS}
    variables["DASHCAM_MONGO_CA_PEM"] = base64.b64decode(os.environ["DASHCAM_MONGO_CA_BASE64"]).decode()
    variables["PYTHONUNBUFFERED"] = "1"
    with tempfile.TemporaryDirectory(prefix="dashcam-fc-deploy-") as temp:
        storage = oss2.Bucket(oss2.Auth(os.environ["ALIBABA_CLOUD_ACCESS_KEY_ID"], os.environ["ALIBABA_CLOUD_ACCESS_KEY_SECRET"]),
                              os.environ["OSS_ENDPOINT"], os.environ["OSS_BUCKET"])
        key = "functions/" + name + "/server.zip"
        if upload_code:
            archive = package(Path(temp) / "function.zip")
            storage.put_object_from_file(key, str(archive))
        code = models.InputCodeLocation(oss_bucket_name=os.environ["OSS_BUCKET"], oss_object_name=key)
        runtime = models.CustomRuntimeConfig(command=["/var/fc/lang/python3.10/bin/python3"],
                                            args=["-m", "gunicorn", "--bind", "0.0.0.0:9000", "--workers", "1", "--threads", "4", "--timeout", "0", "app:create_app()"], port=9000)
        if create:
            client.create_function(models.CreateFunctionRequest(body=models.CreateFunctionInput(
                function_name=name, description="行车视频转存控制接口", runtime="custom.debian10", handler="app.handler",
                code=code, custom_runtime_config=runtime, environment_variables=variables,
                internet_access=True, memory_size=512, disk_size=512, cpu=0.35, timeout=60, instance_concurrency=4)))
            result = client.create_trigger(name, models.CreateTriggerRequest(body=models.CreateTriggerInput(
                trigger_name="http", trigger_type="http", trigger_config='{"authType":"anonymous","methods":["GET","POST"]}')))
            return result.body.to_map()
        client.update_function(name, models.UpdateFunctionRequest(body=models.UpdateFunctionInput(
            code=code, custom_runtime_config=runtime, environment_variables=variables)))
        return {"function": name, "updated": True}


if __name__ == "__main__":
    import sys
    try:
        result = deploy(create="--create" in sys.argv)
        # HTTP trigger metadata contains no credential values.
        print(result)
    except Exception as error:
        raise SystemExit("Deployment failed: " + type(error).__name__ + "; details hidden")
