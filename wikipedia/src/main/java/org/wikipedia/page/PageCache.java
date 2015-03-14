package org.wikipedia.page;

import org.wikipedia.PageTitle;
import org.wikipedia.Utils;
import org.wikipedia.concurrency.SaneAsyncTask;

import com.jakewharton.disklrucache.DiskLruCache;
import org.json.JSONObject;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Implements a cache of Page objects.
 */
public class PageCache {
    private static final String TAG = "PageCache";
    private static final int DISK_CACHE_VERSION = 1;
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 64; // 64MB
    private static final String DISK_CACHE_SUBDIR = "wp_pagecache";

    private DiskLruCache mDiskLruCache;
    private final Object mDiskCacheLock = new Object();

    public PageCache(Context context) {
        // Initialize disk cache on background thread
        File cacheDir = getDiskCacheDir(context, DISK_CACHE_SUBDIR);
        new InitDiskCacheTask(cacheDir).execute();
    }

    private class InitDiskCacheTask extends SaneAsyncTask<Void> {
        private final File cacheDir;

        public InitDiskCacheTask(File cacheDir) {
            super(SINGLE_THREAD);
            this.cacheDir = cacheDir;
        }

        @Override
        public Void performTask() throws Throwable {
            synchronized (mDiskCacheLock) {
                mDiskLruCache = DiskLruCache.open(cacheDir, DISK_CACHE_VERSION, 1, DISK_CACHE_SIZE);
                mDiskCacheLock.notifyAll(); // Wake any waiting threads
            }
            return null;
        }

        @Override
        public void onCatch(Throwable caught) {
            Log.e(TAG, "Caught " + caught.getMessage(), caught);
            caught.printStackTrace();
        }
    }

    public interface CachePutListener {
        void onPutComplete();
        void onPutError(Throwable e);
    }

    public void put(PageTitle title, Page page, final CachePutListener listener) {
        new AddPageToCacheTask(title, page) {
            @Override
            public void onFinish(Void v) {
                listener.onPutComplete();
            }

            @Override
            public void onCatch(Throwable caught) {
                listener.onPutError(caught);
            }
        }.execute();
    }

    private class AddPageToCacheTask extends SaneAsyncTask<Void> {
        private final PageTitle title;
        private final Page page;

        public AddPageToCacheTask(PageTitle title, Page page) {
            super(SINGLE_THREAD);
            this.title = title;
            this.page = page;
        }

        @Override
        public Void performTask() throws Throwable {
            synchronized (mDiskCacheLock) {
                if (mDiskLruCache == null) {
                    return null;
                }
                DiskLruCache.Editor editor = null;
                try {
                    Log.d(TAG, "Writing to cache: " + title.getDisplayText());
                    String key = title.getIdentifier();
                    editor = mDiskLruCache.edit(key);
                    if (editor == null) {
                        return null;
                    }
                    OutputStream outputStream = new BufferedOutputStream(editor.newOutputStream(0));
                    Utils.writeToStream(outputStream, page.toJSON().toString());
                    mDiskLruCache.flush();
                    editor.commit();
                } catch (IOException e) {
                    if (editor != null) {
                        editor.abort();
                    }
                }
            }
            return null;
        }
    }

    public interface CacheGetListener {
        void onGetComplete(Page page);
        void onGetError(Throwable e);
    }

    public void get(PageTitle title, final CacheGetListener listener) {
        new GetPageFromCacheTask(title) {
            @Override
            public void onFinish(Page page) {
                listener.onGetComplete(page);
            }

            @Override
            public void onCatch(Throwable caught) {
                listener.onGetError(caught);
            }
        }.execute();
    }

    private class GetPageFromCacheTask extends SaneAsyncTask<Page> {
        private final PageTitle title;

        public GetPageFromCacheTask(PageTitle title) {
            super(SINGLE_THREAD);
            this.title = title;
        }

        @Override
        public Page performTask() throws Throwable {
            synchronized (mDiskCacheLock) {
                if (mDiskLruCache == null) {
                    return null;
                }
                String key = title.getIdentifier();
                DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
                if (snapshot == null) {
                    return null;
                }
                try {
                    Log.d(TAG, "Reading from cache: " + title.getDisplayText());
                    InputStream inputStream = new BufferedInputStream(snapshot.getInputStream(0));
                    String jsonStr = Utils.readFile(inputStream);
                    return new Page(new JSONObject(jsonStr));
                } finally {
                    snapshot.close();
                }
            }
        }
    }

    // Creates a unique subdirectory of the designated app cache directory. Tries to use external
    // storage, but if not mounted, falls back on internal storage.
    public static File getDiskCacheDir(Context context, String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external cache dir
        // otherwise use internal cache dir.
        final String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
            && context.getExternalCacheDir() != null) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }
}
