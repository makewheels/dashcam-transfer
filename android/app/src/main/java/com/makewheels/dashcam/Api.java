package com.makewheels.dashcam;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class Api {
    static JSONObject call(String method,String path,JSONObject data) throws Exception {
        if(BuildConfig.API_URL.isEmpty()||BuildConfig.APP_TOKEN.isEmpty())throw new IOException("尚未配置服务端");
        HttpURLConnection c=(HttpURLConnection)new URL(BuildConfig.API_URL+path).openConnection();
        c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(60000);
        c.setRequestProperty("Authorization","Bearer "+BuildConfig.APP_TOKEN);
        try {
            if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(data.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();InputStream input=code<400?c.getInputStream():c.getErrorStream();
            if(input==null)throw new IOException("服务端返回 "+code);
            String body;try(input){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>2*1024*1024)throw new IOException("响应过大");}body=bytes.toString("UTF-8");}
            JSONObject result=new JSONObject(body);
            if(code>=400)throw new IOException(code+" · "+result.optString("error","请求失败"));
            return result;
        } finally { c.disconnect(); }
    }
}
