package com.yalantis.ucrop.task;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yalantis.ucrop.callback.BitmapLoadCallback;
import com.yalantis.ucrop.model.ExifInfo;
import com.yalantis.ucrop.util.BitmapLoadUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

///[去掉OkHttp]
//import okhttp3.OkHttpClient;
//import okhttp3.Request;
//import okhttp3.Response;
//import okio.BufferedSource;
//import okio.Okio;
//import okio.Sink;

/**
 * Creates and returns a Bitmap for a given Uri(String url).
 * inSampleSize is calculated based on requiredWidth property. However can be adjusted if OOM occurs.
 * If any EXIF config is found - bitmap is transformed properly.
 */
public class BitmapLoadTask extends AsyncTask<Void, Void, BitmapLoadTask.BitmapWorkerResult> {

    private static final String TAG = "BitmapWorkerTask";

    private static final int MAX_BITMAP_SIZE = 10 * 1024 * 1024;   // 10 MB

    private final Context mContext;
    private Uri mInputUri;
    private String mOutputFilePath;
    private final int mRequiredWidth;
    private final int mRequiredHeight;

    private final BitmapLoadCallback mBitmapLoadCallback;

    public static class BitmapWorkerResult {

        Bitmap mBitmapResult;
        ExifInfo mExifInfo;
        Exception mBitmapWorkerException;

        public BitmapWorkerResult(@NonNull Bitmap bitmapResult, @NonNull ExifInfo exifInfo) {
            mBitmapResult = bitmapResult;
            mExifInfo = exifInfo;
        }

        public BitmapWorkerResult(@NonNull Exception bitmapWorkerException) {
            mBitmapWorkerException = bitmapWorkerException;
        }

    }

    public BitmapLoadTask(@NonNull Context context,
                          @NonNull Uri inputUri, @Nullable String filePath,
                          int requiredWidth, int requiredHeight,
                          BitmapLoadCallback loadCallback) {
        mContext = context;
        mInputUri = inputUri;
        mOutputFilePath = filePath;
        mRequiredWidth = requiredWidth;
        mRequiredHeight = requiredHeight;
        mBitmapLoadCallback = loadCallback;
    }

    @Override
    @NonNull
    protected BitmapWorkerResult doInBackground(Void... params) {
        if (mInputUri == null) {
            return new BitmapWorkerResult(new NullPointerException("Input Uri cannot be null"));
        }

        try {
            processInputUri();
        } catch (NullPointerException | IOException e) {
            return new BitmapWorkerResult(e);
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream stream0 = null;
        try {
            stream0 = mContext.getContentResolver().openInputStream(mInputUri);
            BitmapFactory.decodeStream(stream0, null, options);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.e(TAG, "doInBackground: ImageDecoder.createSource: ", e);
            return new BitmapWorkerResult(new IllegalArgumentException("Bitmap could not be decoded from the Uri: [" + mInputUri + "]", e));
        } finally {
            BitmapLoadUtils.close(stream0);
        }

        options.inSampleSize = BitmapLoadUtils.calculateInSampleSize(options, mRequiredWidth, mRequiredHeight);
        options.inJustDecodeBounds = false;

        Bitmap decodeSampledBitmap = null;

        boolean decodeAttemptSuccess = false;
        while (!decodeAttemptSuccess) {
            try {
                InputStream stream = mContext.getContentResolver().openInputStream(mInputUri);
                try {
                    decodeSampledBitmap = BitmapFactory.decodeStream(stream, null, options);
                    if (options.outWidth == -1 || options.outHeight == -1) {
                        return new BitmapWorkerResult(new IllegalArgumentException("Bounds for bitmap could not be retrieved from the Uri: [" + mInputUri + "]"));
                    }
                } finally {
                    BitmapLoadUtils.close(stream);
                }
                if (checkSize(decodeSampledBitmap, options)) continue;
                decodeAttemptSuccess = true;
            } catch (OutOfMemoryError error) {
                Log.e(TAG, "doInBackground: BitmapFactory.decodeFileDescriptor: ", error);
                options.inSampleSize *= 2;
            } catch (IOException e) {
                Log.e(TAG, "doInBackground: ImageDecoder.createSource: ", e);
                return new BitmapWorkerResult(new IllegalArgumentException("Bitmap could not be decoded from the Uri: [" + mInputUri + "]", e));
            }
        }

        if (decodeSampledBitmap == null) {
            return new BitmapWorkerResult(new IllegalArgumentException("Bitmap could not be decoded from the Uri: [" + mInputUri + "]"));
        }

        int exifOrientation = BitmapLoadUtils.getExifOrientation(mContext, mInputUri);
        int exifDegrees = BitmapLoadUtils.exifToDegrees(exifOrientation);
        int exifTranslation = BitmapLoadUtils.exifToTranslation(exifOrientation);

        ExifInfo exifInfo = new ExifInfo(exifOrientation, exifDegrees, exifTranslation);

        Matrix matrix = new Matrix();
        if (exifDegrees != 0) {
            matrix.preRotate(exifDegrees);
        }
        if (exifTranslation != 1) {
            matrix.postScale(exifTranslation, 1);
        }
        if (!matrix.isIdentity()) {
            return new BitmapWorkerResult(BitmapLoadUtils.transformBitmap(decodeSampledBitmap, matrix), exifInfo);
        }

        return new BitmapWorkerResult(decodeSampledBitmap, exifInfo);
    }

    private void processInputUri() throws NullPointerException, IOException {
        String inputUriScheme = mInputUri.getScheme();
        Log.d(TAG, "Uri scheme: " + inputUriScheme);
        if ("http".equals(inputUriScheme) || "https".equals(inputUriScheme)) {
            try {
                downloadFile(mInputUri, Uri.fromFile(new File(mOutputFilePath)));
            } catch (NullPointerException | IOException e) {
                Log.e(TAG, "Downloading failed", e);
                throw e;
            }
        } else if ("content".equals(inputUriScheme)) {
            try {
                copyFile(mInputUri, Uri.fromFile(new File(mOutputFilePath)));
            } catch (NullPointerException | IOException e) {
                Log.e(TAG, "Copying failed", e);
                throw e;
            }
            ///[FIX#BitmapLoadTask#processInputUri()]mInputUri.getScheme()为空即为file
        } else if (TextUtils.isEmpty(inputUriScheme)) {
            mInputUri = Uri.fromFile(new File(mInputUri.toString()));
        } else if (!"file".equals(inputUriScheme) && !TextUtils.isEmpty(inputUriScheme)) {
            Log.e(TAG, "Invalid Uri scheme " + inputUriScheme);
            throw new IllegalArgumentException("Invalid Uri scheme" + inputUriScheme);
        }
    }

    private void copyFile(@NonNull Uri inputUri, @Nullable Uri outputUri) throws NullPointerException, IOException {
        Log.d(TAG, "copyFile");

        if (outputUri == null) {
            throw new NullPointerException("Output Uri is null - cannot copy image");
        }

        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = mContext.getContentResolver().openInputStream(inputUri);
            outputStream = new FileOutputStream(new File(outputUri.getPath()));
            if (inputStream == null) {
                throw new NullPointerException("InputStream for given input Uri is null");
            }

            byte buffer[] = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        } finally {
            BitmapLoadUtils.close(outputStream);
            BitmapLoadUtils.close(inputStream);

            // swap uris, because input image was copied to the output destination
            // (cropped image will override it later)
            mInputUri = Uri.fromFile(new File(mOutputFilePath));
        }
    }

    ///[去掉OkHttp]
//    private void downloadFile(@NonNull Uri inputUri, @Nullable Uri outputUri) throws NullPointerException, IOException {
//        Log.d(TAG, "downloadFile");
//
//        if (outputUri == null) {
//            throw new NullPointerException("Output Uri is null - cannot download image");
//        }
//
//        OkHttpClient client = new OkHttpClient();
//
//        BufferedSource source = null;
//        Sink sink = null;
//        Response response = null;
//        try {
//            Request request = new Request.Builder()
//                    .url(inputUri.toString())
//                    .build();
//            response = client.newCall(request).execute();
//            source = response.body().source();
//
//            OutputStream outputStream = mContext.getContentResolver().openOutputStream(outputUri);
//            if (outputStream != null) {
//                sink = Okio.sink(outputStream);
//                source.readAll(sink);
//            } else {
//                throw new NullPointerException("OutputStream for given output Uri is null");
//            }
//        } finally {
//            BitmapLoadUtils.close(source);
//            BitmapLoadUtils.close(sink);
//            if (response != null) {
//                BitmapLoadUtils.close(response.body());
//            }
//            client.dispatcher().cancelAll();
//
//            // swap uris, because input image was downloaded to the output destination
//            // (cropped image will override it later)
//            mInputUri = Uri.fromFile(new File(mOutputFilePath));
//        }
//    }

    private void downloadFile(@NonNull Uri inputUri, @Nullable Uri outputUri) throws NullPointerException, IOException {
        Log.d(TAG, "downloadFile");

        if (outputUri == null) {
            throw new NullPointerException("Output Uri is null - cannot download image");
        }

        OutputStream outputStream = mContext.getContentResolver().openOutputStream(outputUri);
        if (outputStream == null) {
            throw new NullPointerException("OutputStream for given output Uri is null");
        }

        URL url;
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        try {
            url = new URL(inputUri.toString());
            connection = (HttpURLConnection) url.openConnection();

            // 设定请求的方法为"GET"
            connection.setRequestMethod("GET");
//            connection.setConnectTimeout(timeout);
//            connection.setReadTimeout(timeout);

            // User-Agent  IE9的标识
            connection.setRequestProperty("Charset", "UTF-8");
//            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0;");
//            connection.setRequestProperty("Accept-Language", "zh-CN");
//            connection.setRequestProperty("Connection", "Keep-Alive");

            /*
             * 当我们要获取我们请求的http地址访问的数据时就是使用connection.getInputStream().read()方式时我们就需要setDoInput(true)，
             * 根据api文档我们可知doInput默认就是为true。我们可以不用手动设置了，如果不需要读取输入流的话那就setDoInput(false)。
             * 当我们要采用非get请求给一个http网络地址传参 就是使用connection.getOutputStream().write() 方法时我们就需要setDoOutput(true), 默认是false
             */
            // 设置是否从httpUrlConnection读入，默认情况下是true;
            connection.setDoInput(true);
            // 设置是否向httpUrlConnection输出，如果是post请求，参数要放在http正文内，因此需要设为true, 默认是false;
            //connection.setDoOutput(true);//Android  4.0 GET时候 用这句会变成POST  报错java.io.FileNotFoundException

            connection.connect();

            //获得结果码
            if (connection.getResponseCode() == 200) {
                ///获得网络连接connection的输入流对象
                inputStream = connection.getInputStream();//会隐式调用connect()

                int len;
                byte[] buffer = new byte[1024];
                while ((len = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                }
            } else {
                throw new IOException(connection.getResponseCode() + ":" + connection.getResponseMessage());
            }
        } finally {
            if(connection != null){
                connection.disconnect();
            }

            ///关闭流Closeable
            BitmapLoadUtils.closeIO(inputStream, outputStream);

            // swap uris, because input image was downloaded to the output destination
            // (cropped image will override it later)
            mInputUri = Uri.fromFile(new File(mOutputFilePath));
        }
    }

    @Override
    protected void onPostExecute(@NonNull BitmapWorkerResult result) {
        if (result.mBitmapWorkerException == null) {
            mBitmapLoadCallback.onBitmapLoaded(result.mBitmapResult, result.mExifInfo, mInputUri.getPath(), mOutputFilePath);
        } else {
            mBitmapLoadCallback.onFailure(result.mBitmapWorkerException);
        }
    }

    private boolean checkSize(Bitmap bitmap, BitmapFactory.Options options) {
        int bitmapSize = bitmap != null ? bitmap.getByteCount() : 0;
        if (bitmapSize > MAX_BITMAP_SIZE) {
            options.inSampleSize *= 2;
            return true;
        }
        return false;
    }
}