package com.makewheels.dashcam;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class Store extends SQLiteOpenHelper {
    Store(Context c) { super(c, "transfers.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY,batch TEXT,source TEXT,tree TEXT,name TEXT,size INTEGER,modified INTEGER,dest TEXT,offset INTEGER DEFAULT 0,state TEXT DEFAULT 'waiting',sha TEXT,crc TEXT,date TEXT,error TEXT DEFAULT '',source_deleted INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE batches(id TEXT PRIMARY KEY,tree TEXT,delete_source INTEGER,ready INTEGER DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b) { throw new IllegalStateException("Migration required"); }
    synchronized void update(String id, ContentValues v) { getWritableDatabase().update("files",v,"id=?",new String[]{id}); }
    synchronized void state(String id,String state,String error) { ContentValues v=new ContentValues();v.put("state",state);v.put("error",error);update(id,v); }
    synchronized List<Item> files(String query,String...args) {
        List<Item> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("files",null,query,args,null,null,"rowid ASC")) { while(c.moveToNext())out.add(new Item(c)); }
        return out;
    }
    static final class Item {
        String id,batch,source,tree,name,dest,state,sha,crc,date,error;
        long size,offset,modified;boolean sourceDeleted;
        Item(Cursor c) {
            id=s(c,"id");batch=s(c,"batch");source=s(c,"source");tree=s(c,"tree");name=s(c,"name");dest=s(c,"dest");state=s(c,"state");sha=s(c,"sha");crc=s(c,"crc");date=s(c,"date");error=s(c,"error");
            size=l(c,"size");offset=l(c,"offset");modified=l(c,"modified");sourceDeleted=l(c,"source_deleted")!=0;
        }
        static String s(Cursor c,String key){return c.getString(c.getColumnIndexOrThrow(key));}
        static long l(Cursor c,String key){return c.getLong(c.getColumnIndexOrThrow(key));}
    }
}
