package com.makewheels.dashcam;
import java.security.*;
import java.io.*;

final class Digests {
    private static final long[] TABLE = new long[256];
    static { for(int i=0;i<256;i++){long n=i;for(int k=0;k<8;k++)n=(n>>>1)^((n&1)!=0?0xC96C5795D7870F42L:0);TABLE[i]=n;} }
    interface Tick { void onBytes(long bytes) throws Exception; }
    static String[] hash(InputStream stream,Tick tick) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");long crc=-1,done=0;byte[] b=new byte[1024*1024];int n;
        while((n=stream.read(b))!=-1){sha.update(b,0,n);for(int i=0;i<n;i++)crc=TABLE[((int)crc^b[i])&255]^(crc>>>8);done+=n;tick.onBytes(done);}
        return new String[]{hex(sha.digest()),Long.toUnsignedString(crc^-1L),Long.toString(done)};
    }
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
}
