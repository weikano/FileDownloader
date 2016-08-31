/*
 * Copyright (c) 2015 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.liulishuo.filedownloader.services;

import android.database.sqlite.SQLiteFullException;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.IThreadPoolMonitor;
import com.liulishuo.filedownloader.exception.MimeTypeMismatchException;
import com.liulishuo.filedownloader.exception.FileDownloadGiveUpRetryException;
import com.liulishuo.filedownloader.exception.FileDownloadHttpException;
import com.liulishuo.filedownloader.exception.FileDownloadOutOfSpaceException;
import com.liulishuo.filedownloader.message.MessageSnapshotFlow;
import com.liulishuo.filedownloader.message.MessageSnapshotTaker;
import com.liulishuo.filedownloader.model.FileDownloadHeader;
import com.liulishuo.filedownloader.model.FileDownloadModel;
import com.liulishuo.filedownloader.model.FileDownloadStatus;
import com.liulishuo.filedownloader.util.FileDownloadHelper;
import com.liulishuo.filedownloader.util.FileDownloadLog;
import com.liulishuo.filedownloader.util.FileDownloadProperties;
import com.liulishuo.filedownloader.util.FileDownloadUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.net.HttpURLConnection;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The real downloading runnable, what works in the {@link FileDownloadThreadPool}.
 *
 * @see #loop(FileDownloadModel)
 * @see #fetch(Response, boolean, long, long)
 * @see FileDownloadThreadPool
 */
@SuppressWarnings("WeakerAccess")
public class FileDownloadRunnable implements Runnable {

    /**
     * None of the ranges in the request's Range header field overlap the current extent of the
     * selected resource or that the set of ranges requested has been rejected due to invalid
     * ranges or an excessive request of small or overlapping ranges.
     */
    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;
    private static final int NO_ANY_PROGRESS_CALLBACK = -1;
    private static final int TOTAL_VALUE_IN_CHUNKED_RESOURCE = -1;


    private static final int CALLBACK_SAFE_MIN_INTERVAL_BYTES = 1;//byte
    private static final int CALLBACK_SAFE_MIN_INTERVAL_MILLIS = 5;//ms

    private static final int BUFFER_SIZE = 1024 * 4;

    private int maxProgressCount = 0;
    private final boolean isForceReDownload;
    private boolean isResumeDownloadAvailable;
    private boolean isResuming;
    private Throwable throwable;
    private int retryingTimes;

    private FileDownloadModel model;

    private volatile boolean isRunning = false;
    private volatile boolean isPending = false;

    private final IFileDownloadDBHelper helper;
    private final OkHttpClient client;
    private final int autoRetryTimes;

    private final FileDownloadHeader header;

    private volatile boolean isCanceled = false;

    private final int callbackMinIntervalMillis;
    private long callbackMinIntervalBytes;

    private final IThreadPoolMonitor threadPoolMonitor;

    public FileDownloadRunnable(final OkHttpClient client, final IThreadPoolMonitor threadPoolMonitor,
                                final FileDownloadModel model,
                                final IFileDownloadDBHelper helper, final int autoRetryTimes,
                                final FileDownloadHeader header, final int minIntervalMillis,
                                final int callbackProgressTimes, final boolean isForceReDownload) {
        isPending = true;
        isRunning = false;

        this.client = client;
        this.threadPoolMonitor = threadPoolMonitor;
        this.helper = helper;
        this.header = header;

        this.callbackMinIntervalMillis =
                minIntervalMillis < CALLBACK_SAFE_MIN_INTERVAL_MILLIS ?
                        CALLBACK_SAFE_MIN_INTERVAL_MILLIS : minIntervalMillis;

        this.maxProgressCount = callbackProgressTimes;
        this.isForceReDownload = isForceReDownload;

        this.isResumeDownloadAvailable = false;

        this.model = model;

        this.autoRetryTimes = autoRetryTimes;
    }

    public int getId() {
        return model.getId();
    }

    public boolean isExist() {
        return isPending || isRunning;
    }

    public boolean isResuming() {
        return isResuming;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public int getRetryingTimes() {
        return retryingTimes;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        isPending = false;
        isRunning = true;

        try {
            // Step 1, check model
            if (model == null) {
                FileDownloadLog.e(this, "start runnable but model == null?? %s", getId());

                this.model = helper.find(getId());

                if (this.model == null) {
                    FileDownloadLog.e(this, "start runnable but downloadMode == null?? %s", getId());
                    return;
                }
            }

            // Step 2, check status
            if (model.getStatus() != FileDownloadStatus.pending) {
                if (model.getStatus() == FileDownloadStatus.paused) {
                    if (FileDownloadLog.NEED_LOG) {
                        /**
                         * @see FileDownloadThreadPool#cancel(int), the invoking simultaneously
                         * with here. And this area is invoking before there, so, {@code cancel(int)}
                         * is fail.
                         *
                         * High concurrent cause.
                         */
                        FileDownloadLog.d(this, "High concurrent cause, start runnable but " +
                                "already paused %d", getId());
                    }
                } else {
                    onError(new RuntimeException(
                            FileDownloadUtils.formatString("Task[%d] can't start the download" +
                                            " runnable, because its status is %d not %d",
                                    getId(), model.getStatus(), FileDownloadStatus.pending)));
                }

                return;
            }

            onStarted();

            // Step 3, start download
            loop(model);

        } finally {
            isRunning = false;
        }

    }

    @SuppressWarnings("ConstantConditions")
    private void loop(FileDownloadModel model) {
        int retryingTimes = 0;
        boolean revisedInterval = false;

        do {
            // loop for retry
            Response response = null;
            long soFar = 0;
            try {

                // Step 1, check is paused
                if (isCancelled()) {
                    if (FileDownloadLog.NEED_LOG) {
                        FileDownloadLog.d(this, "already canceled %d %d", model.getId(), model.getStatus());
                    }
                    onPause();
                    break;
                }

                if (FileDownloadLog.NEED_LOG) {
                    FileDownloadLog.d(FileDownloadRunnable.class, "start download %s %s", getId(), model.getUrl());
                }

                // Step 2, handle resume from breakpoint
                checkIsResumeAvailable();

                Request.Builder requestBuilder = new Request.Builder().url(model.getUrl());
                addHeader(requestBuilder);
                requestBuilder.tag(this.getId());
                // 目前没有指定cache，下载任务非普通REST请求，用户已经有了存储的地方
                requestBuilder.cacheControl(CacheControl.FORCE_NETWORK);

                // start download----------------
                // Step 3, init request
                final Request request = requestBuilder.get().build();
                if (FileDownloadLog.NEED_LOG) {
                    FileDownloadLog.d(this, "%s request header %s", getId(), request.headers());
                }

                Call call = client.newCall(request);

                // Step 4, build connect
                response = call.execute();
                MediaType targetType = MediaType.parse(request.header(BaseDownloadTask.TARGET_MIME_TYPE));
                if(targetType != null){
                    MediaType responseType = response.body().contentType();
                    if(responseType == null || !targetType.type().equals(responseType.type())){
                        throw new MimeTypeMismatchException(responseType,targetType);
                    }
                }
                final boolean isSucceedStart = response.code() == HttpURLConnection.HTTP_OK;
                final boolean isSucceedResume = response.code() == HttpURLConnection.HTTP_PARTIAL &&
                        isResumeDownloadAvailable;

                if (isResumeDownloadAvailable && !isSucceedResume) {
                    FileDownloadLog.w(this, "tried to resume from the break point[%d], but the " +
                                    "response code is %d, not 206(PARTIAL).", model.getSoFar(),
                            response.code());
                }

                if (isSucceedStart || isSucceedResume) {
                    long total = model.getTotal();
                    final String transferEncoding = response.header("Transfer-Encoding");

                    // Step 5, check response's header
                    if (isSucceedStart || total <= 0) {
                        if (transferEncoding == null) {
                            total = response.body().contentLength();
                        } else {
                            // if transfer not nil, ignore content-length
                            total = TOTAL_VALUE_IN_CHUNKED_RESOURCE;
                        }
                    }

                    // TODO consider if not is chunked & http 1.0/(>=http1.1 & connect not be keep live) may not give content-length
                    if (total < 0) {
                        // invalid total length
                        final boolean isEncodingChunked = transferEncoding != null
                                && transferEncoding.equals("chunked");
                        if (!isEncodingChunked) {
                            // not chunked transfer encoding data
                            if (FileDownloadProperties.getImpl().HTTP_LENIENT) {
                                // do not response content-length either not chunk transfer encoding,
                                // but HTTP lenient is true, so handle as the case of transfer encoding chunk
                                total = TOTAL_VALUE_IN_CHUNKED_RESOURCE;
                                if (FileDownloadLog.NEED_LOG) {
                                    FileDownloadLog.d(this, "%d response header is not legal but " +
                                            "HTTP lenient is true, so handle as the case of " +
                                            "transfer encoding chunk", getId());
                                }
                            } else {
                                throw new FileDownloadGiveUpRetryException("can't know the size of the " +
                                        "download file, and its Transfer-Encoding is not Chunked " +
                                        "either.\nyou can ignore such exception by add " +
                                        "http.lenient=true to the filedownloader.properties");
                            }
                        }
                    }

                    if (isSucceedResume) {
                        soFar = model.getSoFar();
                    }

                    // Step 6, callback on connected, and update header to db. for save etag.
                    onConnected(isSucceedResume, total, findEtag(response), findFilename(response));

                    // Step 7, check whether has same task running after got filename from server/local generate.
                    if (model.isPathAsDirectory()) {
                        final int fileCaseId = FileDownloadUtils.generateId(model.getUrl(),
                                model.getTargetFilePath());

                        if (FileDownloadHelper.inspectAndInflowDownloaded(getId(),
                                model.getTargetFilePath(), isForceReDownload, false)) {
                            helper.remove(getId());
                            break;
                        }

                        final FileDownloadModel fileCaseModel = helper.find(fileCaseId);

                        if (fileCaseModel != null) {
                            if (FileDownloadHelper.inspectAndInflowDownloading(getId(), fileCaseModel,
                                    threadPoolMonitor, false)) {
                                helper.remove(getId());
                                break;
                            }

                            helper.remove(fileCaseId);
                            deleteTargetFile();

                            if (FileDownloadMgr.isBreakpointAvailable(fileCaseId, fileCaseModel)) {
                                model.setSoFar(fileCaseModel.getSoFar());
                                model.setTotal(fileCaseModel.getTotal());
                                model.setETag(fileCaseModel.getETag());
                                helper.update(model);
                                // re connect to resume from breakpoint.
                                continue;
                            }
                        }
                    }

                    // Step 8, start fetch datum from input stream & write to file
                    if (fetch(response, isSucceedResume, soFar, total)) {
                        break;
                    }

                } else {
                    final FileDownloadHttpException httpException =
                            new FileDownloadHttpException(request, response);

                    if (revisedInterval) {
                        throw httpException;
                    }
                    revisedInterval = true;

                    switch (response.code()) {
                        case HTTP_REQUESTED_RANGE_NOT_SATISFIABLE:
                            deleteTaskFiles();
                            FileDownloadLog.w(FileDownloadRunnable.class, "%d response code %d, " +
                                            "range[%d] isn't make sense, so delete the dirty file[%s]" +
                                            ", and try to redownload it from byte-0.", getId(),
                                    response.code(), model.getSoFar(), model.getTempFilePath());
                            onRetry(httpException, retryingTimes++);
                            break;
                        default:
                            throw httpException;
                    }
                }


            } catch (Throwable ex) {
                // TODO 决策是否需要重试，是否是用户决定，或者根据错误码处理
                if (autoRetryTimes > retryingTimes++
                        && !(ex instanceof FileDownloadGiveUpRetryException)) {
                    // retry
                    onRetry(ex, retryingTimes);
                } else {


                    // error
                    onError(ex);
                    break;
                }
            } finally {
                if (response != null && response.body() != null) {
                    response.body().close();
                }
            }

        } while (true);
    }

    /**
     * @return Whether finish looper or not.
     */
    @SuppressWarnings("SameReturnValue")
    private boolean fetch(Response response, boolean isSucceedContinue,
                          long soFar, long total) throws Throwable {
        // fetching datum
        InputStream inputStream = null;
        final RandomAccessFile accessFile = getRandomAccessFile(isSucceedContinue, total);
        final FileDescriptor fd = accessFile.getFD();
        try {
            // Step 1, get input stream
            inputStream = response.body().byteStream();
            byte[] buff = new byte[BUFFER_SIZE];

            callbackMinIntervalBytes = calculateCallbackMinIntervalBytes(total, maxProgressCount);

            // enter fetching loop(Step 2->6)
            do {
                // Step 2, read from input stream.
                int byteCount = inputStream.read(buff);
                if (byteCount == -1) {
                    break;
                }

                // Step 3, writ to file
                accessFile.write(buff, 0, byteCount);

                // Step 4, adapter sofar
                soFar += byteCount;

                // Step 5, check whether file is changed by others
                if (accessFile.length() < soFar) {
                    throw new RuntimeException(
                            FileDownloadUtils.formatString("the file was changed by others when" +
                                    " downloading. %d %d", accessFile.length(), soFar));
                } else {
                    // callback on progressing
                    onProgress(soFar, total, fd);
                }

                // Step 6, check pause
                if (isCancelled()) {
                    // callback on paused
                    onPause();
                    return true;
                }

            } while (true);

            // Step 7, adapter chunked transfer encoding
            if (total == TOTAL_VALUE_IN_CHUNKED_RESOURCE) {
                total = soFar;
            }

            // Step 8, Compare between the downloaded so far bytes with the total bytes.
            if (soFar == total) {

                // Step 9, rename the temp file to the completed file.
                renameTempFile();

                // Step 10, remove data from DB.
                helper.remove(getId());

                // callback completed
                onComplete(total);

                return true;
            } else {
                throw new RuntimeException(
                        FileDownloadUtils.formatString("sofar[%d] not equal total[%d]", soFar, total));
            }
        } finally {
            if (inputStream != null) {
                //noinspection ThrowFromFinallyBlock
                inputStream.close();
            }

            try {
                if (fd != null) {
                    //noinspection ThrowFromFinallyBlock
                    fd.sync();
                }
            } finally {
                //noinspection ConstantConditions
                if (accessFile != null) {
                    //noinspection ThrowFromFinallyBlock
                    accessFile.close();
                }
            }
        }
    }

    private void renameTempFile() {
        final String tempPath = model.getTempFilePath();
        final String targetPath = model.getTargetFilePath();

        final File tempFile = new File(tempPath);
        try {
            final File targetFile = new File(targetPath);

            if (targetFile.exists()) {
                final long oldTargetFileLength = targetFile.length();
                if (!targetFile.delete()) {
                    throw new IllegalStateException(FileDownloadUtils.formatString(
                            "Can't delete the old file([%s], [%d]), " +
                                    "so can't replace it with the new downloaded one.",
                            targetPath, oldTargetFileLength
                    ));
                } else {
                    FileDownloadLog.w(this, "The target file([%s], [%d]) will be replaced with" +
                                    " the new downloaded file[%d]",
                            targetPath, oldTargetFileLength, tempFile.length());
                }
            }

            if (!tempFile.renameTo(targetFile)) {
                throw new IllegalStateException(FileDownloadUtils.formatString(
                        "Can't rename the  temp downloaded file(%s) to the target file(%s)",
                        tempPath, targetPath
                ));
            }
        } finally {
            if (tempFile.exists()) {
                if (!tempFile.delete()) {
                    FileDownloadLog.w(this, "delete the temp file(%s) failed, on completed downloading.",
                            tempPath);
                }
            }
        }
    }

    private long calculateCallbackMinIntervalBytes(final long total, final long maxProgressCount) {
        if (maxProgressCount <= 0) {
            return NO_ANY_PROGRESS_CALLBACK;
        } else if (total == TOTAL_VALUE_IN_CHUNKED_RESOURCE) {
            return CALLBACK_SAFE_MIN_INTERVAL_BYTES;
        } else {
            final long minIntervalBytes = total / (maxProgressCount + 1);
            return minIntervalBytes <= 0 ? CALLBACK_SAFE_MIN_INTERVAL_BYTES : minIntervalBytes;
        }
    }

    private void addHeader(Request.Builder builder) {
        final Headers additionHeaders;
        if (header != null) {
            if (FileDownloadProperties.getImpl().PROCESS_NON_SEPARATE) {
                /**
                 * In case of the FileDownloadService is not in separate process, the
                 * function {@link FileDownloadHeader#writeToParcel} would never be invoked, so
                 * {@link FileDownloadHeader#checkAndInitValues} would never be invoked.
                 *
                 * Instead, we can use {@link FileDownloadHeader#headerBuilder} directly.
                 */
                additionHeaders = header.getHeaders();
            } else {
                /**
                 * In case of the FileDownloadService is in separate process to UI process,
                 * the headers value would be carried by the parcel with
                 * {@link FileDownloadHeader#checkAndInitValues()} .
                 */
                if (header.getNamesAndValues() != null) {
                    additionHeaders = Headers.of(header.getNamesAndValues());
                } else {
                    additionHeaders = null;
                }
            }

            if (additionHeaders != null) {
                if (FileDownloadLog.NEED_LOG) {
                    FileDownloadLog.v(this, "%d add outside header: %s", getId(), additionHeaders);
                }
                builder.headers(additionHeaders);
            }
        }

        if (isResumeDownloadAvailable) {
            if (!TextUtils.isEmpty(model.getETag())) {
                builder.addHeader("If-Match", model.getETag());
            }
            builder.addHeader("Range", FileDownloadUtils.formatString("bytes=%d-", model.getSoFar()));
        }
    }

    private String findEtag(Response response) {
        if (response == null) {
            throw new RuntimeException("response is null when findEtag");
        }

        final String newEtag = response.header("Etag");

        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "etag find by header %d %s", getId(), newEtag);
        }

        return newEtag;
    }

    private String findFilename(Response response) {
        String filename;
        if (model.isPathAsDirectory() && model.getFilename() == null) {
            filename = FileDownloadUtils.parseContentDisposition(response.
                    header("Content-Disposition"));
            if (TextUtils.isEmpty(filename)) {
                filename = FileDownloadUtils.generateFileName(model.getUrl());
            }
        } else {
            // no need generate filename.
            filename = null;
        }

        return filename;
    }

    public void cancelRunnable() {
        this.isCanceled = true;
        onPause();
    }

    private void onConnected(final boolean resuming, final long total, final String etag,
                             final String filename) {
        helper.updateConnected(model, total, etag, filename);

        this.isResuming = resuming;

        onStatusChanged(model.getStatus());
    }

    private long lastCallbackBytes = 0;
    private long lastCallbackTime = 0;

    private long lastUpdateBytes = 0;
    private long lastUpdateTime = 0;


    private void onProgress(final long soFar, final long total, final FileDescriptor fd) {
        if (soFar == total) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long bytesDelta = soFar - lastUpdateBytes;
        final long timeDelta = now - lastUpdateTime;

        if (bytesDelta > FileDownloadUtils.getMinProgressStep() &&
                timeDelta > FileDownloadUtils.getMinProgressTime()) {
            try {
                fd.sync();
            } catch (SyncFailedException e) {
                e.printStackTrace();
            }
            helper.updateProgress(model, soFar);
            lastUpdateBytes = soFar;
            lastUpdateTime = now;
        } else {
            if (model.getStatus() != FileDownloadStatus.progress) {
                model.setStatus(FileDownloadStatus.progress);
            }
            model.setSoFar(soFar);
        }

        final long callbackBytesDelta = soFar - lastCallbackBytes;
        final long callbackTimeDelta = now - lastCallbackTime;

        if (callbackMinIntervalBytes == NO_ANY_PROGRESS_CALLBACK ||
                callbackBytesDelta < callbackMinIntervalBytes) {
            return;
        }

        if (callbackTimeDelta < callbackMinIntervalMillis) {
            return;
        }

        lastCallbackTime = now;
        lastCallbackBytes = soFar;

        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "On progress %d %d %d", getId(), soFar, total);
        }

        onStatusChanged(model.getStatus());

    }

    private void onRetry(Throwable ex, final int retryTimes) {
        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "On retry %d %s %d %d", getId(), ex,
                    retryTimes, autoRetryTimes);
        }

        ex = exFiltrate(ex);
        helper.updateRetry(model, ex);

        this.throwable = ex;
        this.retryingTimes = retryTimes;

        onStatusChanged(model.getStatus());
    }

    private void onError(final Throwable originError) {
        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "On error %d %s", getId(), originError);
        }

        Throwable processError = exFiltrate(originError);

        if (processError instanceof SQLiteFullException) {
            // If the error is sqLite full exception already, no need to  update it to the database
            // again.
            handleSQLiteFullException((SQLiteFullException) processError);
        } else {
            // Normal case.
            try {
                helper.updateError(model, processError, model.getSoFar());
                processError = originError;
            } catch (SQLiteFullException fullException) {
                processError = fullException;
                handleSQLiteFullException((SQLiteFullException) processError);
            }

        }

        this.throwable = processError;

        onStatusChanged(model.getStatus());
    }

    private void handleSQLiteFullException(final SQLiteFullException sqLiteFullException) {
        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "the data of the task[%d] is dirty, because the SQLite " +
                            "full exception[%s], so remove it from the database directly.",
                    getId(), sqLiteFullException.toString());
        }

        model.setErrMsg(sqLiteFullException.toString());
        model.setStatus(FileDownloadStatus.error);

        helper.remove(getId());
    }

    private void onComplete(final long total) {
        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "On completed %d %d %B", getId(), total, isCancelled());
        }
        helper.updateComplete(model, total);

        onStatusChanged(model.getStatus());
    }

    private void onPause() {
        this.isRunning = false;
        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "On paused %d %d %d", getId(),
                    model.getSoFar(), model.getTotal());
        }

        helper.updatePause(model, model.getSoFar());

        onStatusChanged(model.getStatus());
    }

    private void onStarted() {
        model.setStatus(FileDownloadStatus.started);
        onStatusChanged(model.getStatus());
    }

    public void onPending() {
        if (FileDownloadLog.NEED_LOG) {
            FileDownloadLog.d(this, "On resume %d", getId());
        }

        this.isPending = true;

        helper.updatePending(model);

        onStatusChanged(model.getStatus());
    }

    private final Object statusChangedNotifyLock = new Object();

    @SuppressWarnings("UnusedParameters")
    private void onStatusChanged(final byte status) {
        // In current situation, it maybe invoke this method simultaneously between #onPause() and
        // others.
        synchronized (statusChangedNotifyLock) {
            if (model.getStatus() == FileDownloadStatus.paused) {
                if (FileDownloadLog.NEED_LOG) {
                    /**
                     * Already paused or the current status is paused.
                     *
                     * We don't need to call-back to Task in here, because the pause status has
                     * already handled by {@link BaseDownloadTask#pause()} manually.
                     *
                     * In some case, it will arrive here by High concurrent cause.  For performance
                     * more make sense.
                     *
                     * High concurrent cause.
                     */
                    FileDownloadLog.d(this, "High concurrent cause, Already paused and we don't " +
                            "need to call-back to Task in here, %d", getId());
                }
                return;
            }

            MessageSnapshotFlow.getImpl().
                    inflow(MessageSnapshotTaker.take(status, model, this));
        }
    }

    private boolean isCancelled() {
        return isCanceled;
    }

    // ----------------------------------
    private RandomAccessFile getRandomAccessFile(final boolean append, final long totalBytes)
            throws IOException {
        final String tempPath = model.getTempFilePath();
        if (TextUtils.isEmpty(tempPath)) {
            throw new RuntimeException("found invalid internal destination path, empty");
        }

        //noinspection ConstantConditions
        if (!FileDownloadUtils.isFilenameValid(tempPath)) {
            throw new RuntimeException(
                    FileDownloadUtils.formatString("found invalid internal destination filename" +
                            " %s", tempPath));
        }

        File file = new File(tempPath);

        if (file.exists() && file.isDirectory()) {
            throw new RuntimeException(
                    FileDownloadUtils.formatString("found invalid internal destination path[%s]," +
                            " & path is directory[%B]", tempPath, file.isDirectory()));
        }
        if (!file.exists()) {
            if (!file.createNewFile()) {
                throw new IOException(
                        FileDownloadUtils.formatString("create new file error  %s",
                                file.getAbsolutePath()));
            }
        }

        RandomAccessFile outFd = new RandomAccessFile(file, "rw");

        // check the available space bytes whether enough or not.
        if (totalBytes > 0) {
            final long breakpointBytes = outFd.length();
            final long requiredSpaceBytes = totalBytes - breakpointBytes;

            final long freeSpaceBytes = FileDownloadUtils.getFreeSpaceBytes(tempPath);

            if (freeSpaceBytes < requiredSpaceBytes) {
                outFd.close();
                // throw a out of space exception.
                throw new FileDownloadOutOfSpaceException(freeSpaceBytes,
                        requiredSpaceBytes, breakpointBytes);
            } else {
                // pre allocate.
                outFd.setLength(totalBytes);
            }
        }

        if (append) {
            outFd.seek(model.getSoFar());
        }

        return outFd;
    }

    private void checkIsResumeAvailable() {
        if (FileDownloadMgr.isBreakpointAvailable(getId(), this.model)) {
            this.isResumeDownloadAvailable = true;
        } else {
            this.isResumeDownloadAvailable = false;
            deleteTaskFiles();
        }
    }

    private void deleteTaskFiles() {
        deleteTempFile();
        deleteTargetFile();
    }

    private void deleteTempFile() {
        final String tempFilePath = model.getTempFilePath();

        if (tempFilePath != null) {
            final File tempFile = new File(tempFilePath);
            if (tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    private void deleteTargetFile() {
        final String targetFilePath = model.getTargetFilePath();
        if (targetFilePath != null) {
            final File targetFile = new File(targetFilePath);
            if (targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.delete();
            }
        }
    }

    private Throwable exFiltrate(Throwable ex) {
        final String tempPath = model.getTempFilePath();
        /**
         * Only handle the case of Chunked resource, if it is not chunked, has already been handled
         * in {@link #getRandomAccessFile(boolean, long)}.
         */
        if (model.getTotal() == TOTAL_VALUE_IN_CHUNKED_RESOURCE && ex instanceof IOException &&
                new File(tempPath).exists()) {
            // chunked
            final long freeSpaceBytes = FileDownloadUtils.
                    getFreeSpaceBytes(tempPath);
            if (freeSpaceBytes <= BUFFER_SIZE) {
                // free space is not enough.
                long downloadedSize = 0;
                final File file = new File(tempPath);
                if (!file.exists()) {
                    FileDownloadLog.e(FileDownloadRunnable.class, ex, "Exception with: free " +
                            "space isn't enough, and the target file not exist.");
                } else {
                    downloadedSize = file.length();
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    ex = new FileDownloadOutOfSpaceException(freeSpaceBytes, BUFFER_SIZE,
                            downloadedSize, ex);
                } else {
                    ex = new FileDownloadOutOfSpaceException(freeSpaceBytes, BUFFER_SIZE,
                            downloadedSize);
                }

            }
        }

        return ex;
    }
}
