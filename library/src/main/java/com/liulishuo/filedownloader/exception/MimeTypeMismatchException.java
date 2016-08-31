package com.liulishuo.filedownloader.exception;

import okhttp3.MediaType;

/**
 * Created by Administrator on 2016-8-19.
 */

public class MimeTypeMismatchException extends RuntimeException {
    public MimeTypeMismatchException(MediaType responseType, MediaType targetType){
        super("Response Content-type " + responseType +" doesn't match the target Content-type " + targetType);
    }
}
